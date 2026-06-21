package com.versiondb.tools;

import com.versiondb.query.exec.ResultSet;
import com.versiondb.shared.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end SQL exercised through the full {@link VersionDb} stack (16.3):
 * create / insert / select / update / delete / join / index, including across a
 * clean reopen.
 */
class EndToEndSqlTest {

    @TempDir
    Path dir;

    private static long longAt(ResultSet rs, int row, int col) {
        return rs.rows().get(row).get(col).asInt();
    }

    @Test
    void fullSingleTableLifecycle() {
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            db.execute("CREATE TABLE users (id INT, age INT, name VARCHAR)");
            assertEquals(4, db.execute(
                    "INSERT INTO users VALUES (1,30,'alice'),(2,20,'bob'),(3,40,'carol'),(4,25,'dave')")
                    .affectedRows());

            // Filter
            assertEquals(2, db.execute("SELECT * FROM users WHERE age > 25").size());

            // Projection + order + limit
            ResultSet youngest = db.execute("SELECT age FROM users ORDER BY age LIMIT 2");
            assertEquals(2, youngest.size());
            assertEquals(20L, longAt(youngest, 0, 0));
            assertEquals(25L, longAt(youngest, 1, 0));

            // Aggregate
            assertEquals(4L, longAt(db.execute("SELECT COUNT(*) FROM users"), 0, 0));

            // Update then re-read
            assertEquals(1, db.execute("UPDATE users SET age = 21 WHERE id = 2").affectedRows());
            assertEquals(21L, longAt(db.execute("SELECT age FROM users WHERE id = 2"), 0, 0));

            // Delete then count
            assertEquals(1, db.execute("DELETE FROM users WHERE id = 3").affectedRows());
            assertEquals(3L, longAt(db.execute("SELECT COUNT(*) FROM users"), 0, 0));
        }
    }

    @Test
    void indexedLookupReturnsCorrectRow() {
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            db.execute("CREATE TABLE users (id INT, age INT, name VARCHAR)");
            db.execute("INSERT INTO users VALUES (1,30,'alice'),(2,20,'bob'),(3,40,'carol')");
            db.execute("CREATE INDEX age_idx ON users (age)");

            ResultSet rs = db.execute("SELECT name FROM users WHERE age = 40");
            assertEquals(1, rs.size());
            assertEquals("carol", rs.rows().get(0).get(0).asString());
        }
    }

    @Test
    void innerJoinAcrossTables() {
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            db.execute("CREATE TABLE users (id INT, name VARCHAR)");
            db.execute("CREATE TABLE orders (uid INT, amount INT)");
            db.execute("INSERT INTO users VALUES (1,'alice'),(2,'bob'),(3,'carol')");
            db.execute("INSERT INTO orders VALUES (1,100),(1,200),(3,50)");

            // alice has 2 orders, carol has 1, bob has none → 3 joined rows.
            ResultSet joined = db.execute("SELECT * FROM users JOIN orders ON users.id = orders.uid");
            assertEquals(3, joined.size());
        }
    }

    @Test
    void datasetSurvivesReopenAndStaysQueryable() {
        // Session 1: build a dataset and an index, then close cleanly.
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            db.execute("CREATE TABLE products (id INT, price INT, name VARCHAR)");
            db.execute("INSERT INTO products VALUES (1,100,'pen'),(2,250,'mug'),(3,75,'pad'),(4,500,'bag')");
            db.execute("CREATE INDEX price_idx ON products (price)");
            db.execute("DELETE FROM products WHERE id = 3");
            db.execute("UPDATE products SET price = 120 WHERE id = 1");
        }
        // Session 2: reopen and verify the recovered + reseeded state.
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            assertEquals(3L, longAt(db.execute("SELECT COUNT(*) FROM products"), 0, 0));
            assertEquals(120L, longAt(db.execute("SELECT price FROM products WHERE id = 1"), 0, 0));

            // Index still answers point lookups after rebuild from the catalog.
            ResultSet bag = db.execute("SELECT name FROM products WHERE price = 500");
            assertEquals(1, bag.size());
            assertEquals("bag", bag.rows().get(0).get(0).asString());

            // Further writes continue to work post-reopen.
            db.execute("INSERT INTO products VALUES (5,999,'desk')");
            assertEquals(4L, longAt(db.execute("SELECT COUNT(*) FROM products"), 0, 0));
        }
        // Session 3: the post-reopen insert is itself durable.
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            List<Value> top = db.execute("SELECT id, price FROM products ORDER BY price LIMIT 1").rows().get(0);
            assertEquals(1L, top.get(0).asInt());   // id 1 has the lowest price (120)
            assertEquals(120L, top.get(1).asInt());
            assertEquals(4L, longAt(db.execute("SELECT COUNT(*) FROM products"), 0, 0));
        }
    }

    @Test
    void nullsAndStringsRoundTrip() {
        try (VersionDb db = VersionDb.openWithoutVacuum(dir)) {
            db.execute("CREATE TABLE t (id INT, note VARCHAR)");
            db.execute("INSERT INTO t (id) VALUES (1)");            // note unspecified → NULL
            db.execute("INSERT INTO t (id, note) VALUES (2, 'hi')");

            ResultSet rs = db.execute("SELECT id, note FROM t ORDER BY id");
            assertEquals(2, rs.size());
            Value note1 = rs.rows().get(0).get(1);
            assertTrue(note1 == null || note1.isNull(), "unspecified column reads as SQL NULL");
            assertEquals("hi", rs.rows().get(1).get(1).asString());
        }
    }
}
