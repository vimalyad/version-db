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

import static org.junit.jupiter.api.Assertions.*;

class VacuumTest {

    @TempDir
    Path tempDir;

    private DiskManager dm;
    private WALManager wal;
    private BufferPool bp;
    private CommitLog clog;
    private TransactionManager tm;
    private VersionStore vs;
    private Vacuum vacuum;

    @BeforeEach
    void setUp() throws IOException {
        dm   = new DiskManager(tempDir.resolve("test.db"));
        wal  = new WALManager(tempDir.resolve("wal.log"));
        bp   = new BufferPool(8, dm, wal::flushToLsn);
        clog = new CommitLog(tempDir.resolve(CommitLog.FILE_NAME));
        tm   = new TransactionManager(1L, wal, clog);
        vs   = new VersionStore();
        vacuum = new Vacuum(vs, clog, tm);
    }

    @AfterEach
    void tearDown() throws IOException {
        wal.close();
        clog.close();
        dm.close();
    }

    private static VersionRecord version(long xmin, long xmax) {
        return new VersionRecord(xmin, xmax, new RID(1, 0), -1L, new byte[]{1});
    }

    /** A vacuum whose horizon is fixed by a fresh manager with no active txns. */
    private Vacuum vacuumWithHorizon(long horizon) {
        return new Vacuum(vs, clog, new TransactionManager(horizon, wal, clog));
    }

    /** Allocate one version, register the chain head, and return its id. */
    private long addVersion(RID rid, long xmin, long xmax, long prev, byte[] data) {
        long id = vs.allocate(new VersionRecord(xmin, xmax, rid, prev, data));
        vs.setHead(rid, id);
        return id;
    }

    // ---- 11.1: reclaim predicate ---------------------------------------------

    @Test
    void liveHeadIsNeverReclaimable() {
        // xmax == 0 means the version is the current head.
        assertFalse(vacuum.isReclaimable(version(5L, 0L), 100L));
    }

    @Test
    void committedDeleterOlderThanHorizonIsReclaimable() {
        clog.setStatus(7L, TxStatus.COMMITTED);
        assertTrue(vacuum.isReclaimable(version(5L, 7L), 100L));
    }

    @Test
    void deleterAtOrAfterHorizonIsNotReclaimable() {
        clog.setStatus(100L, TxStatus.COMMITTED);
        // xmax == horizon: an active transaction at the horizon may still see it.
        assertFalse(vacuum.isReclaimable(version(5L, 100L), 100L));
        clog.setStatus(150L, TxStatus.COMMITTED);
        assertFalse(vacuum.isReclaimable(version(5L, 150L), 100L));
    }

    @Test
    void uncommittedDeleterIsNotReclaimable() {
        // Deleter still in progress — the delete might yet abort.
        assertFalse(vacuum.isReclaimable(version(5L, 7L), 100L));
        // Explicitly aborted deleter: in practice abort resets xmax to 0, but
        // even if xmax lingered, an ABORTED deleter must not be reclaimed.
        clog.setStatus(8L, TxStatus.ABORTED);
        assertFalse(vacuum.isReclaimable(version(5L, 8L), 100L));
    }

    // ---- 11.2: GC pass over version chains -----------------------------------

    @Test
    void gcRemovesReclaimableTailVersions() {
        RID rid = new RID(2, 0);
        // tail v1 (deleted by 2) → v2 (deleted by 3) → head v3 (live)
        long v1 = addVersion(rid, 1L, 2L, -1L, new byte[]{1});
        long v2 = addVersion(rid, 2L, 3L, v1, new byte[]{2});
        long v3 = addVersion(rid, 3L, 0L, v2, new byte[]{3});
        clog.setStatus(2L, TxStatus.COMMITTED);
        clog.setStatus(3L, TxStatus.COMMITTED);

        Vacuum vac = vacuumWithHorizon(10L); // both deleters < 10 and committed
        assertEquals(2, vac.runOnce());

        assertNull(vs.get(v1));
        assertNull(vs.get(v2));
        assertNotNull(vs.get(v3));
        assertEquals(-1L, vs.get(v3).prevVersionId); // v3 is the new tail
    }

    @Test
    void gcStopsAtFirstNonReclaimableFromTail() {
        RID rid = new RID(3, 0);
        long v1 = addVersion(rid, 1L, 2L, -1L, new byte[]{1}); // reclaimable
        long v2 = addVersion(rid, 2L, 8L, v1, new byte[]{2});  // deleter >= horizon
        long v3 = addVersion(rid, 3L, 0L, v2, new byte[]{3});  // live head
        clog.setStatus(2L, TxStatus.COMMITTED);
        clog.setStatus(8L, TxStatus.COMMITTED);

        Vacuum vac = vacuumWithHorizon(5L);
        assertEquals(1, vac.runOnce());

        assertNull(vs.get(v1));
        assertNotNull(vs.get(v2));
        assertEquals(-1L, vs.get(v2).prevVersionId); // v2 detached as new tail
        assertNotNull(vs.get(v3));
        assertEquals(v2, vs.get(v3).prevVersionId);  // head still points at v2
    }

    @Test
    void gcRemovesWholeChainWhenAllReclaimable() {
        RID rid = new RID(4, 0);
        long v1 = addVersion(rid, 1L, 2L, -1L, new byte[]{1});
        long v2 = addVersion(rid, 2L, 3L, v1, new byte[]{2}); // head, but deleted
        clog.setStatus(2L, TxStatus.COMMITTED);
        clog.setStatus(3L, TxStatus.COMMITTED);

        Vacuum vac = vacuumWithHorizon(10L);
        assertEquals(2, vac.runOnce());
        assertNull(vs.get(v1));
        assertNull(vs.get(v2));
    }

    @Test
    void gcIsNoOpWhenNothingReclaimable() {
        RID rid = new RID(5, 0);
        long v1 = addVersion(rid, 1L, 0L, -1L, new byte[]{1}); // live head
        Vacuum vac = vacuumWithHorizon(10L);
        assertEquals(0, vac.runOnce());
        assertNotNull(vs.get(v1));
    }

    @Test
    void readsRemainCorrectAfterGc() {
        // Scenario 6 (part3.md §14): once a row's old versions are reclaimed,
        // reads still return the correct current version.
        MVCCManager mvcc = new MVCCManager(vs, clog, wal, bp);
        RID rid = new RID(6, 0);
        byte[] live = new byte[]{9, 9};
        long old = addVersion(rid, 1L, 2L, -1L, new byte[]{1}); // deleted by 2
        long cur = addVersion(rid, 2L, 0L, old, live);          // live head
        clog.setStatus(2L, TxStatus.COMMITTED);

        Snapshot snap = new Snapshot(10L, 11L, java.util.Set.of());
        assertArrayEquals(live, mvcc.getVisibleVersion(rid, 10L, snap));

        Vacuum vac = vacuumWithHorizon(10L);
        assertEquals(1, vac.runOnce());
        assertNull(vs.get(old));

        // The live version is still readable after the old one was reclaimed.
        assertArrayEquals(live, mvcc.getVisibleVersion(rid, 10L, snap));
    }

    // ---- 11.3: physical heap reclamation + background scheduler --------------

    @Test
    void gcFreesHeapSlotForFullyDeadRow() {
        com.versiondb.storage.HeapFile heap = com.versiondb.storage.HeapFile.create(bp);
        RID rid = heap.insertTuple(new byte[]{7, 7, 7});
        assertNotNull(heap.getTuple(rid)); // present before GC

        addVersion(rid, 1L, 2L, -1L, new byte[]{7, 7, 7}); // head, deleted by 2
        clog.setStatus(2L, TxStatus.COMMITTED);

        Vacuum vac = new Vacuum(vs, clog, new TransactionManager(10L, wal, clog), heap::deleteTuple);
        assertEquals(1, vac.runOnce());

        assertNull(heap.getTuple(rid));            // heap slot physically freed
        assertEquals(-1L, vs.getHeadVersionId(rid)); // RID no longer tracked
    }

    @Test
    void gcDoesNotFreeHeapSlotForLiveRow() {
        java.util.List<RID> freed = new java.util.ArrayList<>();
        RID rid = new RID(8, 0);
        addVersion(rid, 1L, 0L, -1L, new byte[]{1}); // live head

        Vacuum vac = new Vacuum(vs, clog, new TransactionManager(10L, wal, clog), freed::add);
        assertEquals(0, vac.runOnce());
        assertTrue(freed.isEmpty());
        assertNotEquals(-1L, vs.getHeadVersionId(rid)); // still tracked
    }

    @Test
    void backgroundSchedulerReclaimsAutomatically() throws InterruptedException {
        RID rid = new RID(9, 0);
        long v = addVersion(rid, 1L, 2L, -1L, new byte[]{1});
        clog.setStatus(2L, TxStatus.COMMITTED);

        Vacuum vac = new Vacuum(vs, clog, new TransactionManager(10L, wal, clog));
        vac.start(20L);
        try {
            assertTrue(vac.isRunning());
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (vs.get(v) != null && System.nanoTime() < deadline) {
                Thread.sleep(20L);
            }
            assertNull(vs.get(v), "background vacuum should have reclaimed the version");
        } finally {
            vac.stop();
        }
        assertFalse(vac.isRunning());
    }

    @Test
    void longRunningTransactionHoldsTheHorizon() {
        // A still-active transaction pins the horizon and prevents reclamation;
        // once it commits, the version becomes collectable.
        Transaction longRunner = tm.beginTransaction();  // xid 1, stays active
        Transaction deleter = tm.beginTransaction();     // xid 2
        tm.commitTransaction(deleter);                   // xid 2 committed

        RID rid = new RID(10, 0);
        long v = addVersion(rid, 1L, 2L, -1L, new byte[]{1});

        // Horizon is 1 (longRunner active), so xmax=2 is not yet collectable.
        assertEquals(0, vacuum.runOnce());
        assertNotNull(vs.get(v));

        tm.commitTransaction(longRunner); // horizon advances past xid 2
        assertEquals(1, vacuum.runOnce());
        assertNull(vs.get(v));
    }

    @Test
    void horizonReflectsOldestActiveTransaction() {
        assertEquals(1L, vacuum.currentHorizon()); // nothing active → nextXid
        Transaction t1 = tm.beginTransaction(); // xid 1
        Transaction t2 = tm.beginTransaction(); // xid 2
        assertEquals(1L, vacuum.currentHorizon());
        tm.commitTransaction(t1);
        assertEquals(2L, vacuum.currentHorizon());
        tm.commitTransaction(t2);
        assertEquals(3L, vacuum.currentHorizon()); // empty → nextXid
    }
}
