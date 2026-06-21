package com.versiondb.txn;

import com.versiondb.shared.RID;
import com.versiondb.storage.BufferPool;
import com.versiondb.storage.DiskManager;
import com.versiondb.wal.WALManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TransactionManagerTest {

    @TempDir
    Path tempDir;

    private DiskManager dm;
    private WALManager wal;
    private BufferPool bp;
    private CommitLog clog;
    private TransactionManager tm;

    @BeforeEach
    void setUp() throws IOException {
        dm   = new DiskManager(tempDir.resolve("test.db"));
        wal  = new WALManager(tempDir.resolve("wal.log"));
        bp   = new BufferPool(8, dm, wal::flushToLsn);
        clog = new CommitLog(tempDir.resolve(CommitLog.FILE_NAME));
        tm   = new TransactionManager(1L, wal, clog);
    }

    @AfterEach
    void tearDown() throws IOException {
        wal.close();
        clog.close();
        dm.close();
    }

    // ---- 8.1: Transaction object & Snapshot ----------------------------------

    @Test
    void snapshotIsImmutableRecord() {
        Snapshot s = new Snapshot(5L, 10L, Set.of(6L, 7L));
        assertEquals(5L, s.xmin());
        assertEquals(10L, s.xmax());
        assertTrue(s.isInProgress(6L));
        assertFalse(s.isInProgress(8L));
    }

    @Test
    void transactionInitialState() {
        Transaction txn = new Transaction(42L, new Snapshot(1L, 43L, Set.of()));
        assertEquals(42L, txn.xid);
        assertEquals(TxStatus.IN_PROGRESS, txn.getStatus());
        assertNotNull(txn.getSnapshot());
    }

    @Test
    void undoLogReversal() {
        Transaction txn = new Transaction(1L, new Snapshot(1L, 2L, Set.of()));
        RID r1 = new RID(0, 0), r2 = new RID(1, 0), r3 = new RID(2, 0);
        txn.addUndoRecord(r1, null);
        txn.addUndoRecord(r2, new byte[]{1});
        txn.addUndoRecord(r3, new byte[]{2});

        List<UndoRecord> rev = txn.reverseUndoLog();
        assertEquals(r3, rev.get(0).rid());
        assertEquals(r2, rev.get(1).rid());
        assertEquals(r1, rev.get(2).rid());
    }

    @Test
    void writeSetTracking() {
        Transaction txn = new Transaction(1L, new Snapshot(1L, 2L, Set.of()));
        RID r = new RID(0, 0);
        assertFalse(txn.inWriteSet(r));
        txn.addToWriteSet(r);
        assertTrue(txn.inWriteSet(r));
    }

    // ---- 8.2: TransactionManager state ---------------------------------------

    @Test
    void startXidRespected() {
        TransactionManager tm2 = new TransactionManager(100L, wal, clog);
        Transaction txn = tm2.beginTransaction();
        assertEquals(100L, txn.xid);
    }

    // ---- 8.3: beginTransaction -----------------------------------------------

    @Test
    void xidsStrictlyIncrease() {
        long x1 = tm.beginTransaction().xid;
        long x2 = tm.beginTransaction().xid;
        long x3 = tm.beginTransaction().xid;
        assertTrue(x1 < x2 && x2 < x3, "XIDs must strictly increase");
    }

    @Test
    void xidsNeverReuse() {
        Transaction t1 = tm.beginTransaction();
        tm.commitTransaction(t1);
        Transaction t2 = tm.beginTransaction();
        assertNotEquals(t1.xid, t2.xid,
                "XID must not be reused after commit");
        assertTrue(t2.xid > t1.xid);
    }

    @Test
    void xidNotReusedAfterAbort() {
        Transaction t1 = tm.beginTransaction();
        tm.abortTransaction(t1);
        Transaction t2 = tm.beginTransaction();
        assertNotEquals(t1.xid, t2.xid);
        assertTrue(t2.xid > t1.xid);
    }

    @Test
    void activeSetContainsBegunTransactions() {
        assertTrue(tm.activeXids().isEmpty());
        Transaction t1 = tm.beginTransaction();
        Transaction t2 = tm.beginTransaction();
        assertTrue(tm.activeXids().contains(t1.xid));
        assertTrue(tm.activeXids().contains(t2.xid));
    }

    @Test
    void snapshotCapturedAtBeginTime() {
        Transaction t1 = tm.beginTransaction();
        // t1 started before t2 — t1's snapshot should not include t2
        Transaction t2 = tm.beginTransaction();

        Snapshot s1 = t1.getSnapshot();
        Snapshot s2 = t2.getSnapshot();

        // t2 is not visible in t1's snapshot (it didn't exist yet)
        assertFalse(s1.isInProgress(t2.xid),
                "t1's snapshot must not include t2 (t2 started after t1's snapshot)");

        // t1 IS in-progress from t2's perspective
        assertTrue(s2.isInProgress(t1.xid),
                "t2's snapshot must include t1 (t1 was active when t2 began)");

        tm.commitTransaction(t1);
        tm.commitTransaction(t2);
    }

    // ---- 8.4: commitTransaction / abortTransaction ---------------------------

    @Test
    void activeSetRemovedOnCommit() {
        Transaction txn = tm.beginTransaction();
        tm.commitTransaction(txn);
        assertFalse(tm.activeXids().contains(txn.xid));
        assertEquals(TxStatus.COMMITTED, txn.getStatus());
    }

    @Test
    void activeSetRemovedOnAbort() {
        Transaction txn = tm.beginTransaction();
        tm.abortTransaction(txn);
        assertFalse(tm.activeXids().contains(txn.xid));
        assertEquals(TxStatus.ABORTED, txn.getStatus());
    }

    @Test
    void commitUpdatesCommitLog() {
        Transaction txn = tm.beginTransaction();
        tm.commitTransaction(txn);
        assertEquals(TxStatus.COMMITTED, clog.getStatus(txn.xid));
        assertTrue(clog.isCommitted(txn.xid));
    }

    @Test
    void abortUpdatesCommitLog() {
        Transaction txn = tm.beginTransaction();
        tm.abortTransaction(txn);
        assertEquals(TxStatus.ABORTED, clog.getStatus(txn.xid));
        assertFalse(clog.isCommitted(txn.xid));
    }

    @Test
    void undoCallbackCalledInReverseOrderOnAbort() {
        List<RID> seen = new ArrayList<>();
        tm.setUndoCallback((txnId, rid, oldData) -> seen.add(rid));

        Transaction txn = tm.beginTransaction();
        RID r1 = new RID(0, 0), r2 = new RID(1, 0), r3 = new RID(2, 0);
        txn.addUndoRecord(r1, null);
        txn.addUndoRecord(r2, new byte[]{1});
        txn.addUndoRecord(r3, new byte[]{2});

        tm.abortTransaction(txn);

        assertEquals(3, seen.size(), "Undo callback called once per undo entry");
        assertEquals(r3, seen.get(0), "Undo must process entries in reverse order");
        assertEquals(r2, seen.get(1));
        assertEquals(r1, seen.get(2));
    }

    @Test
    void undoCallbackPassesOldData() {
        List<byte[]> capturedOldData = new ArrayList<>();
        tm.setUndoCallback((txnId, rid, oldData) -> capturedOldData.add(oldData));

        Transaction txn = tm.beginTransaction();
        txn.addUndoRecord(new RID(0, 0), null);           // insert → null
        txn.addUndoRecord(new RID(0, 1), new byte[]{9});  // delete → bytes

        tm.abortTransaction(txn);

        // Reversed: delete entry first, insert entry second
        assertArrayEquals(new byte[]{9}, capturedOldData.get(0));
        assertNull(capturedOldData.get(1));
    }

    @Test
    void abortWithNoCallbackDoesNotThrow() {
        Transaction txn = tm.beginTransaction();
        txn.addUndoRecord(new RID(0, 0), null);
        assertDoesNotThrow(() -> tm.abortTransaction(txn));
    }

    // ---- 8.5: isCommitted / getOldestActiveXid ------------------------------

    @Test
    void isCommittedDelegatesToCommitLog() {
        Transaction txn = tm.beginTransaction();
        assertFalse(tm.isCommitted(txn.xid));
        tm.commitTransaction(txn);
        assertTrue(tm.isCommitted(txn.xid));
    }

    @Test
    void horizonEqualsMinActiveXid() {
        Transaction t1 = tm.beginTransaction();
        Transaction t2 = tm.beginTransaction();
        Transaction t3 = tm.beginTransaction();

        assertEquals(t1.xid, tm.getOldestActiveXid(),
                "horizon must be the smallest active XID");

        tm.commitTransaction(t1);
        assertEquals(t2.xid, tm.getOldestActiveXid(),
                "after t1 commits, horizon shifts to t2");

        tm.commitTransaction(t2);
        tm.commitTransaction(t3);
    }

    @Test
    void horizonIsNextXidWhenNoActiveTransactions() {
        long horizon = tm.getOldestActiveXid();
        Transaction txn = tm.beginTransaction();
        // horizon should equal txn.xid (the nextXid at that moment = the first assigned XID)
        assertEquals(txn.xid, horizon,
                "horizon with no active transactions = nextXid = first XID about to be assigned");
        tm.commitTransaction(txn);
    }

    // ---- Thread safety -------------------------------------------------------

    @Test
    void threadSafeBeginUnderContention() throws InterruptedException {
        int threads = 8;
        int perThread = 50;
        List<Long> xids = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < perThread; j++) {
                    Transaction txn = tm.beginTransaction();
                    xids.add(txn.xid);
                    tm.commitTransaction(txn);
                }
            });
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        int expected = threads * perThread;
        assertEquals(expected, xids.size(), "All XIDs collected");
        // All XIDs must be unique
        assertEquals(expected, xids.stream().distinct().count(), "XIDs must be unique");
        // XIDs must all be positive
        assertTrue(xids.stream().allMatch(x -> x >= 1), "All XIDs >= 1");
    }
}
