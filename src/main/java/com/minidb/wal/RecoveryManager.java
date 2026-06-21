package com.minidb.wal;

import com.minidb.storage.BufferPool;
import com.minidb.storage.Page;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ARIES-style crash recovery: replays the WAL to restore the database to a
 * consistent state after a crash. Recovery runs in three passes — Analysis,
 * Redo, then Undo — and is driven by {@link #recover()} on startup.
 *
 * <p><b>Analysis</b> (this sub-phase) scans the log forward from the most recent
 * checkpoint and rebuilds:
 * <ul>
 *   <li>the Active Transaction Table (ATT): {@code txnId → lastLsn} for every
 *       transaction that was still in progress at the crash — these are the
 *       "losers" that Undo must roll back;</li>
 *   <li>the Dirty Page Table (DPT): {@code pageId → recLsn}, the LSN of the
 *       first log record that dirtied each page since it was last persisted —
 *       Redo begins at the smallest {@code recLsn}.</li>
 * </ul>
 */
public final class RecoveryManager {

    private final WALManager wal;
    private final BufferPool bufferPool;

    public RecoveryManager(WALManager wal, BufferPool bufferPool) {
        this.wal = wal;
        this.bufferPool = bufferPool;
    }

    /** Outcome of the Analysis pass. */
    record Analysis(Map<Long, Long> att, Map<Integer, Long> dpt, Set<Long> committed) {
    }

    /** Read the entire log into memory, fully draining the iterator. */
    List<LogRecord> readAllRecords() {
        List<LogRecord> all = new ArrayList<>();
        Iterator<LogRecord> it = wal.readLog(0);
        it.forEachRemaining(all::add);
        return all;
    }

    /**
     * Analysis pass. Seeds the ATT/DPT from the last checkpoint (if any), then
     * scans records at or after that checkpoint to bring both tables up to the
     * crash point. Pure function of the log, so it is independent of disk state.
     */
    static Analysis analyze(List<LogRecord> log) {
        Map<Long, Long> att = new HashMap<>();
        Map<Integer, Long> dpt = new HashMap<>();
        Set<Long> committed = new HashSet<>();

        // Find the most recent checkpoint to start from.
        LogRecord lastCheckpoint = null;
        for (LogRecord r : log) {
            if (r.logType == LogType.CHECKPOINT
                    && (lastCheckpoint == null || r.lsn > lastCheckpoint.lsn)) {
                lastCheckpoint = r;
            }
        }
        long startLsn = 0;
        if (lastCheckpoint != null) {
            att.putAll(lastCheckpoint.activeTxnTable);
            dpt.putAll(lastCheckpoint.dirtyPageTable);
            startLsn = lastCheckpoint.lsn;
        }

        for (LogRecord r : log) {
            if (r.lsn < startLsn) {
                continue;
            }
            switch (r.logType) {
                case BEGIN -> att.put(r.txnId, r.lsn);
                case INSERT, DELETE, CLR -> {
                    att.put(r.txnId, r.lsn);
                    dpt.putIfAbsent(r.pageId, r.lsn);
                }
                case COMMIT -> {
                    committed.add(r.txnId);
                    att.remove(r.txnId);
                }
                case ABORT -> att.remove(r.txnId);
                case CHECKPOINT -> {
                    // Already seeded from the last checkpoint above.
                }
            }
        }
        return new Analysis(att, dpt, committed);
    }

    /**
     * Redo pass — "repeat history". Re-applies every logged change (from any
     * transaction, committed or not) starting at the smallest {@code recLsn} in
     * the DPT, so the on-disk state matches the moment of the crash. A record is
     * skipped when its page was clean at crash time (not in the DPT), the change
     * predates the page's {@code recLsn}, or the page already reflects it
     * ({@code page.lsn >= record.lsn}).
     */
    void redo(List<LogRecord> log, Map<Integer, Long> dpt) {
        if (dpt.isEmpty()) {
            return;
        }
        long redoStart = Collections.min(dpt.values());
        for (LogRecord r : log) {
            if (r.lsn < redoStart || !touchesPage(r)) {
                continue;
            }
            Long recLsn = dpt.get(r.pageId);
            if (recLsn == null || r.lsn < recLsn) {
                continue; // page clean at crash, or this change is already on disk
            }
            Page page = bufferPool.fetchPage(r.pageId);
            boolean applied = false;
            try {
                if (page.getLsn() < r.lsn) {
                    applyRedo(page, r);
                    page.setLsn(r.lsn);
                    applied = true;
                }
            } finally {
                bufferPool.unpin(r.pageId, applied);
            }
        }
    }

    private static boolean touchesPage(LogRecord r) {
        return r.logType == LogType.INSERT
                || r.logType == LogType.DELETE
                || r.logType == LogType.CLR;
    }

    /**
     * Apply a single record's effect to a page. INSERT recreates the tuple at
     * its logged slot; DELETE tombstones it. A CLR carries the compensating
     * action: non-empty data means a reinsert (undo of a delete), empty data
     * means a delete (undo of an insert). MiniDB tuples are always non-empty
     * (the codec emits at least the null bitmap), so empty data is unambiguous.
     */
    private static void applyRedo(Page page, LogRecord r) {
        switch (r.logType) {
            case INSERT -> page.putTupleAtSlot(r.slotId, r.data);
            case DELETE -> page.deleteTuple(r.slotId);
            case CLR -> {
                if (r.data != null && r.data.length > 0) {
                    page.putTupleAtSlot(r.slotId, r.data);
                } else {
                    page.deleteTuple(r.slotId);
                }
            }
            default -> {
                // non-page records are never passed here
            }
        }
    }
}
