package com.versiondb.tools;

import com.versiondb.query.exec.ResultSet;
import com.versiondb.shared.Value;
import com.versiondb.txn.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VersionDbTest {

    @TempDir
    Path dir;

    @Test
    void createInsertSelectWithinOneSession() {
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            db.execute("CREATE TABLE users (id INT, name VARCHAR)");
            ResultSet ins = db.execute("INSERT INTO users (id, name) VALUES (1, 'Alice'), (2, 'Bob')");
            assertEquals(2, ins.affectedRows());

            ResultSet rs = db.execute("SELECT id, name FROM users ORDER BY id");
            assertEquals(2, rs.size());
            assertEquals(1L, rs.rows().get(0).get(0).asInt());
            assertEquals("Alice", rs.rows().get(0).get(1).asString());
            assertEquals("Bob", rs.rows().get(1).get(1).asString());
        }
    }

    @Test
    void dataPersistsAcrossCleanReopen() {
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            db.execute("CREATE TABLE t (id INT, v INT)");
            db.execute("INSERT INTO t VALUES (1, 10), (2, 20), (3, 30)");
        }
        // Reopen: recovery + version-store reseed must surface the committed rows.
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            ResultSet rs = db.execute("SELECT id, v FROM t ORDER BY id");
            assertEquals(3, rs.size());
            assertEquals(1L, rs.rows().get(0).get(0).asInt());
            assertEquals(30L, rs.rows().get(2).get(1).asInt());
        }
    }

    @Test
    void updateAndDeleteVisibleInSession() {
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            db.execute("CREATE TABLE t (id INT, v INT)");
            db.execute("INSERT INTO t VALUES (1, 10), (2, 20), (3, 30)");

            assertEquals(1, db.execute("UPDATE t SET v = 99 WHERE id = 2").affectedRows());
            assertEquals(1, db.execute("DELETE FROM t WHERE id = 3").affectedRows());

            ResultSet rs = db.execute("SELECT id, v FROM t ORDER BY id");
            assertEquals(2, rs.size());
            assertEquals(2L, rs.rows().get(1).get(0).asInt());
            assertEquals(99L, rs.rows().get(1).get(1).asInt());
        }
    }

    @Test
    void explicitTransactionCommit() {
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            db.execute("CREATE TABLE t (id INT)");
            Transaction txn = db.begin();
            db.execute("INSERT INTO t VALUES (1)", txn);
            db.execute("INSERT INTO t VALUES (2)", txn);
            // Same transaction sees its own uncommitted writes.
            assertEquals(2, db.execute("SELECT id FROM t", txn).size());
            db.commit(txn);

            assertEquals(2, db.execute("SELECT id FROM t").size());
        }
    }

    @Test
    void explicitTransactionAbortRollsBack() {
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            db.execute("CREATE TABLE t (id INT)");
            db.execute("INSERT INTO t VALUES (1)");

            Transaction txn = db.begin();
            db.execute("INSERT INTO t VALUES (2)", txn);
            db.abort(txn);

            // The aborted insert must not be visible.
            ResultSet rs = db.execute("SELECT id FROM t");
            assertEquals(1, rs.size());
            assertEquals(1L, rs.rows().get(0).get(0).asInt());
        }
    }

    @Test
    void snapshotIsolationHidesConcurrentCommit() {
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            db.execute("CREATE TABLE t (id INT)");
            db.execute("INSERT INTO t VALUES (1)");

            // Reader takes its snapshot before the writer commits.
            Transaction reader = db.begin();
            assertEquals(1, db.execute("SELECT id FROM t", reader).size());

            db.execute("INSERT INTO t VALUES (2)"); // autocommit by another transaction

            // Reader's snapshot is stable: it still sees only the original row.
            assertEquals(1, db.execute("SELECT id FROM t", reader).size());
            db.commit(reader);

            // A fresh transaction sees both rows.
            assertEquals(2, db.execute("SELECT id FROM t").size());
        }
    }

    @Test
    void indexCreationAndPersistence() {
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            db.execute("CREATE TABLE t (id INT, v INT)");
            db.execute("INSERT INTO t VALUES (1, 10), (2, 20), (3, 30)");
            db.execute("CREATE INDEX idx_id ON t (id)");
            ResultSet rs = db.execute("SELECT id, v FROM t WHERE id = 2");
            assertEquals(1, rs.size());
            assertEquals(20L, rs.rows().get(0).get(1).asInt());
        }
        // Reopen: the index registry must be rebuilt from the catalog.
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            ResultSet rs = db.execute("SELECT id, v FROM t WHERE id = 3");
            assertEquals(1, rs.size());
            assertEquals(3L, rs.rows().get(0).get(0).asInt());  // id
            assertEquals(30L, rs.rows().get(0).get(1).asInt()); // v
        }
    }
}
