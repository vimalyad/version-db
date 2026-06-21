package com.versiondb.wal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.versiondb.storage.BufferPool;
import com.versiondb.storage.DiskManager;
import com.versiondb.storage.Page;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class RecoveryManagerTest {

    @TempDir
    Path tmp;

    // ---- 6.1: Analysis pass --------------------------------------------------

    @Test
    void analysisSeparatesWinnersFromLosers() {
        // txn 1 commits; txn 2 is still in progress at the crash (a loser).
        List<LogRecord> log = List.of(
                LogRecord.begin(0, 1),
                LogRecord.insert(1, 0, 1, 5, 0, new byte[]{1}),
                LogRecord.commit(2, 1, 1),
                LogRecord.begin(3, 2),
                LogRecord.insert(4, 3, 2, 6, 0, new byte[]{2}));

        RecoveryManager.Analysis a = RecoveryManager.analyze(log);

        assertTrue(a.committed().contains(1L));
        assertFalse(a.att().containsKey(1L), "committed txn must not be a loser");
        assertEquals(4L, a.att().get(2L), "loser's lastLsn is its most recent record");
    }

    @Test
    void dirtyPageTableRecordsFirstDirtyingLsn() {
        List<LogRecord> log = List.of(
                LogRecord.begin(0, 1),
                LogRecord.insert(1, 0, 1, 5, 0, new byte[]{1}), // first to dirty page 5
                LogRecord.insert(2, 1, 1, 5, 1, new byte[]{2}), // page 5 again
                LogRecord.insert(3, 2, 1, 7, 0, new byte[]{3})); // first to dirty page 7

        Map<Integer, Long> dpt = RecoveryManager.analyze(log).dpt();

        assertEquals(1L, dpt.get(5), "recLsn is the FIRST record that dirtied the page");
        assertEquals(3L, dpt.get(7));
    }

    @Test
    void abortedTransactionIsNotALoser() {
        List<LogRecord> log = List.of(
                LogRecord.begin(0, 1),
                LogRecord.insert(1, 0, 1, 5, 0, new byte[]{1}),
                LogRecord.abort(2, 1, 1));

        RecoveryManager.Analysis a = RecoveryManager.analyze(log);
        assertFalse(a.att().containsKey(1L), "explicitly aborted txn is resolved, not a loser");
    }

    @Test
    void analysisStartsFromLastCheckpoint() {
        // The checkpoint seeds txn 9 as already active with lastLsn 100; a later
        // insert advances it, and txn 9 never commits, so it stays a loser.
        Map<Long, Long> att = Map.of(9L, 100L);
        Map<Integer, Long> dpt = Map.of(42, 100L);
        List<LogRecord> log = List.of(
                LogRecord.begin(0, 1),          // before checkpoint — ignored
                LogRecord.commit(1, 0, 1),      // before checkpoint — ignored
                LogRecord.checkpoint(100, 0, att, dpt),
                LogRecord.insert(101, 100, 9, 42, 3, new byte[]{7}));

        RecoveryManager.Analysis a = RecoveryManager.analyze(log);

        assertEquals(101L, a.att().get(9L), "checkpoint-seeded txn advanced by post-checkpoint record");
        assertFalse(a.committed().contains(1L), "pre-checkpoint commit is not re-scanned");
        assertEquals(100L, a.dpt().get(42), "DPT seeded from checkpoint keeps earliest recLsn");
    }

    // ---- 6.2: Redo pass ------------------------------------------------------

    @Test
    void redoReappliesCommittedInsertLostFromBufferPool() throws IOException {
        DiskManager dm = new DiskManager(tmp.resolve("redo.db"));
        WALManager wal = new WALManager(tmp.resolve("redo.wal"));
        byte[] data = {10, 20, 30};
        try {
            // Pre-crash: a committed insert whose dirty page is never flushed.
            BufferPool bp1 = new BufferPool(8, dm, wal::flushToLsn);
            wal.logBegin(1);
            Page p = bp1.newPage();
            int pid = p.getPageId();
            int slot = p.insertTuple(data);
            long insLsn = wal.logInsert(1, pid, slot, data);
            p.setLsn(insLsn);
            bp1.unpin(pid, true);   // dirty, but bp1 is abandoned without flushing
            wal.logCommit(1);        // WAL (begin+insert+commit) is now durable

            // Crash + restart: a fresh buffer pool re-reads the empty page from disk.
            BufferPool bp2 = new BufferPool(8, dm, wal::flushToLsn);
            RecoveryManager rm = new RecoveryManager(wal, bp2);
            List<LogRecord> log = rm.readAllRecords();
            RecoveryManager.Analysis a = RecoveryManager.analyze(log);

            rm.redo(log, a.dpt());
            assertArrayEquals(data, fetch(bp2, pid, slot), "redo must re-apply the lost insert");

            // Redo is idempotent: running it again changes nothing (page.lsn guards).
            rm.redo(log, a.dpt());
            assertArrayEquals(data, fetch(bp2, pid, slot));
        } finally {
            wal.close();
            dm.close();
        }
    }

    // ---- 6.3: Undo pass + full recover() -------------------------------------

    @Test
    void recoverUndoesUncommittedInsertThatReachedDisk() throws IOException {
        DiskManager dm = new DiskManager(tmp.resolve("undo.db"));
        WALManager wal = new WALManager(tmp.resolve("undo.wal"));
        int pid;
        int slot;
        try {
            BufferPool bp1 = new BufferPool(8, dm, wal::flushToLsn);
            wal.logBegin(1);
            Page p = bp1.newPage();
            pid = p.getPageId();
            slot = p.insertTuple(new byte[]{1, 2, 3});
            long insLsn = wal.logInsert(1, pid, slot, new byte[]{1, 2, 3});
            p.setLsn(insLsn);
            bp1.unpin(pid, true);
            bp1.flushPage(pid);  // uncommitted change reaches disk (and flushes WAL)
            // crash: NO commit; abandon bp1

            BufferPool bp2 = new BufferPool(8, dm, wal::flushToLsn);
            RecoveryManager rm = new RecoveryManager(wal, bp2);
            RecoveryManager.RecoveryResult result = rm.recover();

            assertTrue(result.undone().contains(1L), "uncommitted txn must be rolled back");
            assertNull(fetch(bp2, pid, slot), "undone insert must be gone");
        } finally {
            wal.close();
            dm.close();
        }
    }

    @Test
    void recoverKeepsCommittedInsertAndUndoesConcurrentLoser() throws IOException {
        DiskManager dm = new DiskManager(tmp.resolve("mix.db"));
        WALManager wal = new WALManager(tmp.resolve("mix.wal"));
        int pid;
        int committedSlot;
        int loserSlot;
        try {
            BufferPool bp1 = new BufferPool(8, dm, wal::flushToLsn);
            Page p = bp1.newPage();
            pid = p.getPageId();

            // txn 1 commits its insert; txn 2 inserts on the same page but never commits.
            wal.logBegin(1);
            committedSlot = p.insertTuple(new byte[]{11});
            p.setLsn(wal.logInsert(1, pid, committedSlot, new byte[]{11}));
            wal.logBegin(2);
            loserSlot = p.insertTuple(new byte[]{22});
            p.setLsn(wal.logInsert(2, pid, loserSlot, new byte[]{22}));
            bp1.unpin(pid, true);
            bp1.flushPage(pid);   // both inserts reach disk
            wal.logCommit(1);     // only txn 1 commits
            // crash: abandon bp1

            BufferPool bp2 = new BufferPool(8, dm, wal::flushToLsn);
            RecoveryManager.RecoveryResult result = new RecoveryManager(wal, bp2).recover();

            assertTrue(result.committed().contains(1L));
            assertTrue(result.undone().contains(2L));
            assertArrayEquals(new byte[]{11}, fetch(bp2, pid, committedSlot), "committed insert survives");
            assertNull(fetch(bp2, pid, loserSlot), "loser insert is rolled back");
        } finally {
            wal.close();
            dm.close();
        }
    }

    @Test
    void recoverIsIdempotent() throws IOException {
        DiskManager dm = new DiskManager(tmp.resolve("idem.db"));
        WALManager wal = new WALManager(tmp.resolve("idem.wal"));
        int pid;
        int slot;
        try {
            BufferPool bp1 = new BufferPool(8, dm, wal::flushToLsn);
            wal.logBegin(1);
            Page p = bp1.newPage();
            pid = p.getPageId();
            slot = p.insertTuple(new byte[]{9});
            p.setLsn(wal.logInsert(1, pid, slot, new byte[]{9}));
            bp1.unpin(pid, true);
            bp1.flushPage(pid);   // uncommitted, on disk
            // crash

            BufferPool bp2 = new BufferPool(8, dm, wal::flushToLsn);
            new RecoveryManager(wal, bp2).recover();   // undoes txn 1

            // Re-run recovery: txn 1 is now resolved (aborted via CLR+abort), no-op.
            BufferPool bp3 = new BufferPool(8, dm, wal::flushToLsn);
            RecoveryManager.RecoveryResult second = new RecoveryManager(wal, bp3).recover();

            assertTrue(second.undone().isEmpty(), "second recovery has no losers");
            assertNull(fetch(bp3, pid, slot), "tuple stays deleted after re-recovery");
        } finally {
            wal.close();
            dm.close();
        }
    }

    // ---- 6.4: Checkpoint -----------------------------------------------------

    @Test
    void checkpointFlushesDirtyPagesAndLogsCheckpoint() throws IOException {
        DiskManager dm = new DiskManager(tmp.resolve("cp.db"));
        WALManager wal = new WALManager(tmp.resolve("cp.wal"));
        byte[] data = {7, 7};
        int pid;
        int slot;
        try {
            BufferPool bp1 = new BufferPool(8, dm, wal::flushToLsn);
            wal.logBegin(1);
            Page p = bp1.newPage();
            pid = p.getPageId();
            slot = p.insertTuple(data);
            long insLsn = wal.logInsert(1, pid, slot, data);
            p.setLsn(insLsn);
            bp1.unpin(pid, true);   // dirty, not yet on disk

            RecoveryManager rm = new RecoveryManager(wal, bp1);
            long cpLsn = rm.checkpoint(Map.of(1L, insLsn), Map.of(pid, insLsn));

            // The page was flushed by the checkpoint, so a fresh pool reads it
            // straight from disk with no recovery needed.
            BufferPool bp2 = new BufferPool(8, dm, wal::flushToLsn);
            assertArrayEquals(data, fetch(bp2, pid, slot), "checkpoint must flush dirty pages");

            // The checkpoint record is in the log and seeds Analysis.
            RecoveryManager.Analysis a = RecoveryManager.analyze(rm.readAllRecords());
            assertEquals(insLsn, a.att().get(1L), "checkpoint seeds the still-active txn");
            assertTrue(a.dpt().containsKey(pid));
            assertTrue(cpLsn > insLsn);
        } finally {
            wal.close();
            dm.close();
        }
    }

    private static byte[] fetch(BufferPool bp, int pageId, int slotId) {
        Page page = bp.fetchPage(pageId);
        try {
            return page.getTuple(slotId);
        } finally {
            bp.unpin(pageId, false);
        }
    }
}
