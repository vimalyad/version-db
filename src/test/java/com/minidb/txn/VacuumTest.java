package com.minidb.txn;

import com.minidb.shared.RID;
import com.minidb.storage.BufferPool;
import com.minidb.storage.DiskManager;
import com.minidb.wal.WALManager;
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
