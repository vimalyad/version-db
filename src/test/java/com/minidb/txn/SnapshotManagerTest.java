package com.minidb.txn;

import com.minidb.storage.BufferPool;
import com.minidb.storage.DiskManager;
import com.minidb.wal.WALManager;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotManagerTest {

    @TempDir
    Path tempDir;

    private DiskManager dm;
    private WALManager wal;
    private BufferPool bp;
    private CommitLog clog;
    private TransactionManager tm;
    private SnapshotManager sm;

    @BeforeEach
    void setUp() throws IOException {
        dm   = new DiskManager(tempDir.resolve("test.db"));
        wal  = new WALManager(tempDir.resolve("wal.log"));
        bp   = new BufferPool(8, dm, wal::flushToLsn);
        clog = new CommitLog(tempDir.resolve(CommitLog.FILE_NAME));
        tm   = new TransactionManager(1L, wal, clog);
        sm   = tm.getSnapshotManager();
    }

    @AfterEach
    void tearDown() throws IOException {
        wal.close();
        clog.close();
        dm.close();
    }

    // ---- 9.2: createSnapshot -------------------------------------------------

    @Test
    void emptySnapshotHasXminEqualXmaxEqualNextXid() {
        // No active transactions: xmin == xmax == nextXid (the first XID to assign).
        Snapshot s = sm.createSnapshot();
        assertEquals(1L, s.xmax(), "xmax is nextXid for a fresh manager");
        assertEquals(1L, s.xmin(), "xmin falls back to nextXid when no active txns");
        assertTrue(s.inProgressXids().isEmpty());
    }

    @Test
    void snapshotReflectsActiveSet() {
        Transaction t1 = tm.beginTransaction(); // xid 1
        Transaction t2 = tm.beginTransaction(); // xid 2

        Snapshot s = sm.createSnapshot();        // taken with {1,2} active, nextXid=3
        assertEquals(t1.xid, s.xmin(), "xmin is the smallest active XID");
        assertEquals(3L, s.xmax(), "xmax is nextXid (next to assign)");
        assertTrue(s.isInProgress(t1.xid));
        assertTrue(s.isInProgress(t2.xid));

        tm.commitTransaction(t1);
        tm.commitTransaction(t2);
    }

    @Test
    void xminAdvancesAsOldestCommits() {
        Transaction t1 = tm.beginTransaction(); // xid 1
        Transaction t2 = tm.beginTransaction(); // xid 2
        assertEquals(t1.xid, sm.createSnapshot().xmin());

        tm.commitTransaction(t1);
        // t1 gone from the active set → xmin advances to t2.
        Snapshot s = sm.createSnapshot();
        assertEquals(t2.xid, s.xmin());
        assertFalse(s.isInProgress(t1.xid), "committed t1 no longer in the active set");
        assertTrue(s.isInProgress(t2.xid));

        tm.commitTransaction(t2);
    }

    @Test
    void committedTxnStaysInProgressForAnEarlierSnapshot() {
        Transaction t1 = tm.beginTransaction();
        Snapshot s = sm.createSnapshot();   // t1 active here
        assertTrue(s.isInProgress(t1.xid));

        tm.commitTransaction(t1);           // commits AFTER the snapshot was taken

        // The snapshot is immutable: t1 must still read as in-progress for it,
        // so versions t1 created remain invisible to anyone holding this snapshot.
        assertTrue(s.isInProgress(t1.xid),
                "a transaction that commits after a snapshot stays in-progress for it");
    }

    @Test
    void snapshotInProgressSetIsImmutable() {
        Transaction t1 = tm.beginTransaction();
        Snapshot s = sm.createSnapshot();
        assertThrows(UnsupportedOperationException.class,
                () -> s.inProgressXids().add(999L),
                "the in-progress set must be unmodifiable");
        tm.commitTransaction(t1);
    }

    @Test
    void everyInProgressXidIsBelowXmax() {
        Transaction t1 = tm.beginTransaction();
        Transaction t2 = tm.beginTransaction();
        Snapshot s = sm.createSnapshot();
        for (long xid : s.inProgressXids()) {
            assertTrue(xid < s.xmax(), "in-progress XID " + xid + " must be < xmax " + s.xmax());
            assertTrue(xid >= s.xmin(), "in-progress XID " + xid + " must be >= xmin " + s.xmin());
        }
        tm.commitTransaction(t1);
        tm.commitTransaction(t2);
    }

    // ---- 9.2: atomicity / consistency under concurrency ----------------------

    @Test
    void snapshotsAreInternallyConsistentUnderContention() throws InterruptedException {
        // While many threads begin/commit transactions, a sampler keeps taking
        // snapshots. Because capture is atomic with XID assignment, every snapshot
        // must satisfy the SI structural invariants — no XID may be "assigned but
        // missing from the active set yet below xmax".
        int workers = 6;
        int perWorker = 200;
        ExecutorService workerPool = Executors.newFixedThreadPool(workers);
        CountDownLatch go = new CountDownLatch(1);
        AtomicBoolean workersDone = new AtomicBoolean(false);
        List<String> violations = Collections.synchronizedList(new ArrayList<>());

        // Sampler thread validates snapshot invariants until the workers finish.
        Thread sampler = new Thread(() -> {
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            while (!workersDone.get()) {
                Snapshot s = sm.createSnapshot();
                if (s.xmin() > s.xmax()) {
                    violations.add("xmin " + s.xmin() + " > xmax " + s.xmax());
                }
                for (long xid : s.inProgressXids()) {
                    if (xid >= s.xmax()) {
                        violations.add("in-progress " + xid + " >= xmax " + s.xmax());
                    }
                }
                long expectedXmin = s.inProgressXids().stream()
                        .mapToLong(Long::longValue).min().orElse(s.xmax());
                if (s.xmin() != expectedXmin) {
                    violations.add("xmin " + s.xmin() + " != min(inProgress)/xmax " + expectedXmin);
                }
            }
        });
        sampler.start();

        for (int i = 0; i < workers; i++) {
            workerPool.submit(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int j = 0; j < perWorker; j++) {
                    Transaction txn = tm.beginTransaction();
                    tm.commitTransaction(txn);
                }
            });
        }

        go.countDown();
        workerPool.shutdown();
        assertTrue(workerPool.awaitTermination(30, TimeUnit.SECONDS), "workers finished");
        workersDone.set(true);
        sampler.join(5_000);
        assertFalse(sampler.isAlive(), "sampler stopped");

        assertTrue(violations.isEmpty(), "snapshot invariants violated: " + violations);
    }

    @Test
    void createSnapshotMatchesActiveXids() {
        Transaction t1 = tm.beginTransaction();
        Transaction t2 = tm.beginTransaction();
        Transaction t3 = tm.beginTransaction();
        tm.commitTransaction(t2);

        Snapshot s = sm.createSnapshot();
        Set<Long> active = tm.activeXids();
        assertEquals(active, s.inProgressXids(),
                "snapshot in-progress set must equal the manager's active set");

        tm.commitTransaction(t1);
        tm.commitTransaction(t3);
    }
}
