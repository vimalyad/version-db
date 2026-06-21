package com.versiondb.txn;

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

    // ---- 9.3: isolation-level snapshot switch --------------------------------

    @Test
    void beginDefaultsToRepeatableRead() {
        Transaction txn = tm.beginTransaction();
        assertEquals(IsolationLevel.REPEATABLE_READ, txn.isolationLevel,
                "default isolation level is REPEATABLE READ (Snapshot Isolation)");
        tm.commitTransaction(txn);
    }

    @Test
    void beginRespectsExplicitIsolationLevel() {
        Transaction txn = tm.beginTransaction(IsolationLevel.READ_COMMITTED);
        assertEquals(IsolationLevel.READ_COMMITTED, txn.isolationLevel);
        tm.commitTransaction(txn);
    }

    @Test
    void repeatableReadReusesBeginSnapshotAcrossStatements() {
        Transaction t = tm.beginTransaction(IsolationLevel.REPEATABLE_READ);
        Snapshot begin = t.getSnapshot();

        // A concurrent transaction begins and commits while t is running.
        Transaction other = tm.beginTransaction();
        tm.commitTransaction(other);

        Snapshot s1 = tm.snapshotForStatement(t);
        Snapshot s2 = tm.snapshotForStatement(t);

        assertSame(begin, s1, "REPEATABLE READ reuses the begin-time snapshot");
        assertSame(begin, s2, "and keeps reusing it across statements");
        // other started after t, so it was never visible and stays invisible:
        // its XID is >= the stable snapshot's xmax.
        assertTrue(other.xid >= begin.xmax(),
                "a transaction that began after t is beyond t's stable snapshot");

        tm.commitTransaction(t);
    }

    @Test
    void readCommittedTakesFreshSnapshotPerStatement() {
        Transaction t = tm.beginTransaction(IsolationLevel.READ_COMMITTED);
        long beginXmax = t.getSnapshot().xmax();

        // Statement 1: another transaction is still in progress.
        Transaction other = tm.beginTransaction();
        Snapshot s1 = tm.snapshotForStatement(t);
        assertTrue(s1.isInProgress(other.xid),
                "statement 1 sees the concurrent txn as in-progress");
        assertSame(s1, t.getSnapshot(), "the fresh snapshot becomes the current one");

        // The concurrent transaction commits between statements.
        tm.commitTransaction(other);

        // Statement 2: a brand-new snapshot now reflects that commit.
        Snapshot s2 = tm.snapshotForStatement(t);
        assertNotSame(s1, s2, "READ COMMITTED takes a new snapshot each statement");
        assertFalse(s2.isInProgress(other.xid),
                "statement 2 no longer sees the now-committed txn as in-progress");
        assertTrue(s2.xmax() >= s1.xmax(), "xmax advances as transactions complete");
        assertTrue(s2.xmax() > beginXmax,
                "the per-statement snapshot has moved past the begin snapshot");

        tm.commitTransaction(t);
    }

    @Test
    void repeatableReadDoesNotObserveCommitThatReadCommittedWould() {
        // Same scenario, two isolation levels, contrasting outcomes.
        Transaction rr = tm.beginTransaction(IsolationLevel.REPEATABLE_READ);
        Transaction rc = tm.beginTransaction(IsolationLevel.READ_COMMITTED);

        Transaction writer = tm.beginTransaction();
        tm.commitTransaction(writer);

        // REPEATABLE READ: stable snapshot, writer was beyond it from the start.
        Snapshot rrSnap = tm.snapshotForStatement(rr);
        assertTrue(writer.xid >= rrSnap.xmax(),
                "writer is invisible to the stable REPEATABLE READ snapshot");

        // READ COMMITTED: fresh snapshot now includes the committed writer in range
        // and not in-progress → visible.
        Snapshot rcSnap = tm.snapshotForStatement(rc);
        assertTrue(writer.xid < rcSnap.xmax() && !rcSnap.isInProgress(writer.xid),
                "writer is visible to the refreshed READ COMMITTED snapshot");

        tm.commitTransaction(rr);
        tm.commitTransaction(rc);
    }
}
