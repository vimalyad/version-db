package com.versiondb.wal;

import com.versiondb.storage.BufferPool;
import com.versiondb.storage.Page;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
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

    private static final byte[] EMPTY = new byte[0];

    /** Outcome of the Analysis pass. */
    record Analysis(Map<Long, Long> att, Map<Integer, Long> dpt, Set<Long> committed) {
    }

    /**
     * Outcome of a full {@link #recover()}: the transactions that had committed,
     * and the loser transactions that were rolled back. (Used by the commit log
     * and integration layers to reconcile transaction status.)
     */
    public record RecoveryResult(Set<Long> committed, Set<Long> undone) {
    }

    /**
     * Run full crash recovery: Analysis, then Redo (repeat history), then Undo
     * (roll back losers, writing CLRs). Dirty pages are flushed at the end so the
     * recovered state is durable. Idempotent: a second run is a no-op.
     */
    public RecoveryResult recover() {
        List<LogRecord> log = readAllRecords();
        Analysis analysis = analyze(log);
        redo(log, analysis.dpt());
        Set<Long> undone = undo(log, analysis.att());
        bufferPool.flushAll();
        return new RecoveryResult(analysis.committed(), undone);
    }

    /**
     * Write a checkpoint: flush all dirty pages to disk, then log a CHECKPOINT
     * record capturing the caller-supplied Active Transaction Table and Dirty
     * Page Table. Because dirty pages are flushed first, recovery can safely
     * begin Analysis from this record. The {@code att}/{@code dpt} are supplied
     * by the transaction manager and buffer pool at the integration layer.
     *
     * @return the LSN of the checkpoint record
     */
    public long checkpoint(Map<Long, Long> activeTxnTable, Map<Integer, Long> dirtyPageTable) {
        bufferPool.flushAll();
        return wal.logCheckpoint(activeTxnTable, dirtyPageTable);
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
     * Undo pass. Rolls back every loser transaction in reverse chronological
     * order using a max-heap of LSNs. Each undone INSERT/DELETE writes a CLR
     * (so the work is not repeated if recovery itself crashes) and applies the
     * compensating action to the page; a CLR is skipped (never re-undone) and
     * its {@code undoNextLsn} resumes past the already-undone record; reaching a
     * transaction's BEGIN emits its ABORT.
     *
     * @return the set of loser transactions that were rolled back
     */
    Set<Long> undo(List<LogRecord> log, Map<Long, Long> att) {
        Map<Long, LogRecord> byLsn = new HashMap<>();
        for (LogRecord r : log) {
            byLsn.put(r.lsn, r);
        }
        Set<Long> losers = new HashSet<>(att.keySet());

        // Max-heap so the most recent change across all losers is undone first.
        PriorityQueue<Long> heap = new PriorityQueue<>(Collections.reverseOrder());
        heap.addAll(att.values());

        while (!heap.isEmpty()) {
            LogRecord r = byLsn.get(heap.poll());
            if (r == null) {
                continue;
            }
            switch (r.logType) {
                case INSERT -> {
                    long clrLsn = wal.logClr(r.txnId, r.pageId, r.slotId, r.prevLsn, EMPTY);
                    applyUndo(r.pageId, r.slotId, null, clrLsn); // undo insert = delete
                    advance(heap, r);
                }
                case DELETE -> {
                    long clrLsn = wal.logClr(r.txnId, r.pageId, r.slotId, r.prevLsn, r.data);
                    applyUndo(r.pageId, r.slotId, r.data, clrLsn); // undo delete = reinsert
                    advance(heap, r);
                }
                case CLR -> {
                    if (r.undoNextLsn != LogRecord.NO_PREVIOUS_LSN) {
                        heap.add(r.undoNextLsn);
                    } else {
                        wal.logAbort(r.txnId);
                    }
                }
                case BEGIN -> wal.logAbort(r.txnId); // transaction fully undone
                default -> advance(heap, r); // COMMIT/ABORT/CHECKPOINT: not expected for a loser
            }
        }
        return losers;
    }

    /** Continue a loser's chain at its previous record, or abort it if there is none. */
    private void advance(PriorityQueue<Long> heap, LogRecord r) {
        if (r.prevLsn != LogRecord.NO_PREVIOUS_LSN) {
            heap.add(r.prevLsn);
        } else {
            wal.logAbort(r.txnId);
        }
    }

    /**
     * Apply a compensating action to a page during undo: {@code data == null}
     * deletes the tuple (undo of an insert); otherwise the tuple is restored at
     * its slot (undo of a delete). The page's LSN is set to the CLR's LSN.
     */
    private void applyUndo(int pageId, int slotId, byte[] data, long clrLsn) {
        Page page = bufferPool.fetchPage(pageId);
        try {
            if (data == null) {
                page.deleteTuple(slotId);
            } else {
                page.putTupleAtSlot(slotId, data);
            }
            page.setLsn(clrLsn);
        } finally {
            bufferPool.unpin(pageId, true);
        }
    }

    /**
     * Apply a single record's effect to a page. INSERT recreates the tuple at
     * its logged slot; DELETE tombstones it. A CLR carries the compensating
     * action: non-empty data means a reinsert (undo of a delete), empty data
     * means a delete (undo of an insert). VersionDB tuples are always non-empty
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
