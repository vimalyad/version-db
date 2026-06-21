package com.versiondb.tools;

import com.versiondb.query.exec.ResultSet;
import com.versiondb.txn.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Durability end-to-end (16.4): write, simulate a crash that drops the buffer
 * pool without flushing, reopen, and assert that committed data is present and
 * uncommitted data is gone — exercising ARIES recovery plus the WAL-replay
 * version-store reseed through the full stack.
 *
 * <p>Each test flushes once after DDL: the catalog and heap page-chain pointers
 * are not WAL-logged, so the table's structure must reach disk before the crash.
 * The row data itself is left only in the buffer pool and the WAL, so recovery's
 * redo (for committed transactions) and undo (for losers) are what reconstruct
 * the post-crash state.
 */
class DurabilityTest {

    @TempDir
    Path dir;

    private static long count(VersionDb db, String table) {
        return db.execute("SELECT COUNT(*) FROM " + table).rows().get(0).get(0).asInt();
    }

    @Test
    void committedSurvivesAndUncommittedIsRolledBack() {
        VersionDb db = VersionDb.openWithoutVacuum(dir);
        db.execute("CREATE TABLE t (id INT, v INT)");
        db.flush(); // persist catalog + the (empty) heap page before the crash

        // Committed data — durable via the WAL even though its heap page is never flushed.
        db.execute("INSERT INTO t VALUES (1, 100), (2, 200)");

        // Uncommitted data — an explicit transaction we never commit.
        Transaction ghost = db.begin();
        db.execute("INSERT INTO t VALUES (3, 300)", ghost);

        db.crash(); // drop the buffer pool without flushing

        try (VersionDb reopened = VersionDb.openWithoutVacuum(dir)) {
            assertEquals(2L, count(reopened, "t"), "both committed rows survive the crash");
            ResultSet rs = reopened.execute("SELECT id, v FROM t ORDER BY id");
            assertEquals(1L, rs.rows().get(0).get(0).asInt());
            assertEquals(200L, rs.rows().get(1).get(1).asInt());

            // The uncommitted row must be gone.
            assertEquals(0, reopened.execute("SELECT id FROM t WHERE id = 3").size());
        }
    }

    @Test
    void committedDeleteAndUpdateSurviveCrash() {
        VersionDb db = VersionDb.openWithoutVacuum(dir);
        db.execute("CREATE TABLE t (id INT, v INT)");
        db.flush();

        db.execute("INSERT INTO t VALUES (1, 10), (2, 20), (3, 30)");
        db.execute("DELETE FROM t WHERE id = 2");      // committed delete
        db.execute("UPDATE t SET v = 99 WHERE id = 3"); // committed update

        db.crash();

        try (VersionDb reopened = VersionDb.openWithoutVacuum(dir)) {
            // id=2 stays deleted; id=3's new value persists; id=1 unchanged.
            assertEquals(2L, count(reopened, "t"));
            assertEquals(0, reopened.execute("SELECT id FROM t WHERE id = 2").size());

            ResultSet rs = reopened.execute("SELECT id, v FROM t ORDER BY id");
            assertEquals(2, rs.size());
            assertEquals(10L, rs.rows().get(0).get(1).asInt());
            assertEquals(99L, rs.rows().get(1).get(1).asInt());
        }
    }

    @Test
    void rolledBackTransactionLeavesNoTrace() {
        VersionDb db = VersionDb.openWithoutVacuum(dir);
        db.execute("CREATE TABLE t (id INT)");
        db.flush();
        db.execute("INSERT INTO t VALUES (1)");

        // Explicitly abort a transaction, then crash before any further work.
        Transaction txn = db.begin();
        db.execute("INSERT INTO t VALUES (2)", txn);
        db.execute("INSERT INTO t VALUES (3)", txn);
        db.abort(txn);

        db.crash();

        try (VersionDb reopened = VersionDb.openWithoutVacuum(dir)) {
            assertEquals(1L, count(reopened, "t"), "only the committed row remains");
            assertEquals(1L, reopened.execute("SELECT id FROM t").rows().get(0).get(0).asInt());
        }
    }

    @Test
    void recoveryIsIdempotentAcrossRepeatedCrashes() {
        VersionDb db = VersionDb.openWithoutVacuum(dir);
        db.execute("CREATE TABLE t (id INT)");
        db.flush();
        db.execute("INSERT INTO t VALUES (1), (2)");
        db.crash();

        // First reopen recovers; crash again immediately without new writes.
        VersionDb second = VersionDb.openWithoutVacuum(dir);
        assertEquals(2L, count(second, "t"));
        second.crash();

        // Second reopen must see the same committed state (recovery is idempotent).
        try (VersionDb third = VersionDb.openWithoutVacuum(dir)) {
            assertEquals(2L, count(third, "t"));
        }
    }
}
