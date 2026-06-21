package com.versiondb.tools;

import com.versiondb.shared.TransactionConflictException;
import com.versiondb.txn.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The concurrency / isolation scenarios from {@code part3.md} §14, driven
 * through the full {@link VersionDb} stack (16.5). The isolation scenarios use
 * explicit transactions to control snapshot timing deterministically; a final
 * test drives genuinely parallel autocommit writers to exercise thread-safety.
 */
class ConcurrencyTest {

    @TempDir
    Path dir;

    private VersionDb db;

    @BeforeEach
    void setUp() {
        db = VersionDb.openWithoutVacuum(dir);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private int rowCount(Transaction txn, String sql) {
        return db.execute(sql, txn).size();
    }

    private long firstLong(String sql, int col) {
        return db.execute(sql).rows().get(0).get(col).asInt();
    }

    /** Scenario 1 — basic isolation across begin/commit boundaries. */
    @Test
    void scenario1_basicIsolation() {
        db.execute("CREATE TABLE t (id INT)");

        Transaction t1 = db.begin();
        db.execute("INSERT INTO t VALUES (1)", t1);   // not yet committed

        Transaction t2 = db.begin();                  // snapshot before t1 commits
        assertEquals(0, rowCount(t2, "SELECT id FROM t"), "t2 cannot see t1's uncommitted row");

        db.commit(t1);
        assertEquals(0, rowCount(t2, "SELECT id FROM t"),
                "t2's snapshot is stable: still no row even after t1 commits");

        Transaction t3 = db.begin();                  // started after t1 committed
        assertEquals(1, rowCount(t3, "SELECT id FROM t"), "t3 sees the committed row");

        db.commit(t2);
        db.commit(t3);
    }

    /** Scenario 2 — read consistency: a long reader sees none of a later writer's rows. */
    @Test
    void scenario2_readConsistency() {
        db.execute("CREATE TABLE t (id INT)");

        Transaction t1 = db.begin(); // snapshot of an empty table

        StringBuilder values = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            values.append(i == 1 ? "" : ",").append("(").append(i).append(")");
        }
        db.execute("INSERT INTO t VALUES " + values); // 100 rows, autocommitted

        assertEquals(0, rowCount(t1, "SELECT id FROM t"), "t1's snapshot predates the 100 inserts");
        assertEquals(100, db.execute("SELECT id FROM t").size(), "a fresh transaction sees all 100");

        db.commit(t1);
    }

    /** Scenario 3 — an aborted insert is invisible to everyone. */
    @Test
    void scenario3_abortCleanup() {
        db.execute("CREATE TABLE t (id INT)");

        Transaction t1 = db.begin();
        db.execute("INSERT INTO t VALUES (1)", t1);
        db.abort(t1);

        assertEquals(0, db.execute("SELECT id FROM t").size(), "aborted insert is invisible");
    }

    /** Scenario 4 — concurrent updates to one row: first-writer-wins, the loser conflicts. */
    @Test
    void scenario4_concurrentUpdateConflict() {
        db.execute("CREATE TABLE t (id INT, v INT)");
        db.execute("INSERT INTO t VALUES (1, 0)");

        Transaction t1 = db.begin();
        Transaction t2 = db.begin();

        db.execute("UPDATE t SET v = 1 WHERE id = 1", t1); // first writer stamps xmax

        // Second writer touching the same row must detect the write conflict.
        assertThrows(TransactionConflictException.class,
                () -> db.execute("UPDATE t SET v = 2 WHERE id = 1", t2));

        db.commit(t1);
        db.abort(t2);

        assertEquals(1L, firstLong("SELECT v FROM t WHERE id = 1", 0),
                "the first writer's value wins");
    }

    /** Scenario 5 — version-chain correctness: an old snapshot sees an old version. */
    @Test
    void scenario5_versionChain() {
        db.execute("CREATE TABLE t (id INT, v INT)");
        db.execute("INSERT INTO t VALUES (1, 1)"); // version 1
        db.execute("UPDATE t SET v = 2 WHERE id = 1"); // version 2 (committed)

        Transaction reader = db.begin(); // snapshot sees version 2, before versions 3-5

        db.execute("UPDATE t SET v = 3 WHERE id = 1");
        db.execute("UPDATE t SET v = 4 WHERE id = 1");
        db.execute("UPDATE t SET v = 5 WHERE id = 1");

        assertEquals(2L, db.execute("SELECT v FROM t WHERE id = 1", reader).rows().get(0).get(0).asInt(),
                "the reader's snapshot still sees version 2");
        assertEquals(5L, firstLong("SELECT v FROM t WHERE id = 1", 0),
                "a fresh transaction sees the latest version 5");

        db.commit(reader);
    }

    /** Thread-safety: many parallel autocommit inserts all land exactly once. */
    @Test
    void parallelAutocommitInsertsAllLand() throws InterruptedException {
        db.execute("CREATE TABLE t (id INT)");

        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        List<Runnable> ignored = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int base = i * perThread;
            pool.submit(() -> {
                try {
                    go.await();
                    for (int j = 0; j < perThread; j++) {
                        db.execute("INSERT INTO t VALUES (" + (base + j) + ")");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    errors.incrementAndGet();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "all writers finished");

        assertEquals(0, errors.get(), "no writer errored");
        assertEquals((long) threads * perThread,
                db.execute("SELECT id FROM t").size(), "every inserted row is present exactly once");
    }
}
