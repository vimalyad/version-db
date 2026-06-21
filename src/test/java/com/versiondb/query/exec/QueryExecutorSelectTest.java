package com.versiondb.query.exec;

import com.versiondb.shared.Value;
import com.versiondb.txn.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryExecutorSelectTest {

    @TempDir
    Path tmp;
    private ExecFixture fx;
    private QueryExecutor exec;

    @BeforeEach
    void setUp() throws IOException {
        fx = new ExecFixture(tmp);
        exec = fx.executor();
        Transaction setup = fx.txnManager.beginTransaction();
        exec.execute("CREATE TABLE users (id INT, age INT, name VARCHAR)", setup);
        exec.execute("INSERT INTO users VALUES (1,30,'alice'),(2,20,'bob'),(3,40,'carol'),(4,25,'dave')", setup);
        fx.txnManager.commitTransaction(setup);
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private ResultSet query(String sql) {
        return exec.execute(sql, fx.txnManager.beginTransaction());
    }

    @Test
    void selectStarReturnsAllRows() {
        ResultSet rs = query("SELECT * FROM users");
        assertEquals(4, rs.size());
        assertEquals(List.of("id", "age", "name"), rs.columns());
    }

    @Test
    void selectWithWhere() {
        ResultSet rs = query("SELECT * FROM users WHERE age > 25");
        assertEquals(2, rs.size()); // 30, 40
    }

    @Test
    void selectProjection() {
        ResultSet rs = query("SELECT name FROM users WHERE id = 1");
        assertEquals(1, rs.size());
        assertEquals(List.of("name"), rs.columns());
        assertEquals("alice", rs.rows().get(0).get(0).asString());
    }

    @Test
    void selectOrderByLimit() {
        ResultSet rs = query("SELECT age FROM users ORDER BY age LIMIT 2");
        assertEquals(2, rs.size());
        assertEquals(20, rs.rows().get(0).get(0).asInt());
        assertEquals(25, rs.rows().get(1).get(0).asInt());
    }

    @Test
    void countStar() {
        ResultSet rs = query("SELECT COUNT(*) FROM users");
        assertEquals(1, rs.size());
        assertEquals(4, rs.rows().get(0).get(0).asInt());
    }

    @Test
    void indexedQueryReturnsCorrectRows() {
        exec.execute("CREATE INDEX age_idx ON users (age)", fx.txnManager.beginTransaction());
        ResultSet rs = query("SELECT * FROM users WHERE age = 40");
        assertEquals(1, rs.size());
        assertEquals(3, rs.rows().get(0).get(0).asInt());
    }

    @Test
    void joinQueryEndToEnd() {
        Transaction t = fx.txnManager.beginTransaction();
        exec.execute("CREATE TABLE orders (uid INT, amount INT)", t);
        exec.execute("INSERT INTO orders VALUES (1,100),(1,200),(3,50)", t);
        fx.txnManager.commitTransaction(t);

        ResultSet rs = query("SELECT * FROM users JOIN orders ON users.id = orders.uid");
        assertEquals(3, rs.size()); // alice x2, carol x1
    }

    @Test
    void insertThenSelectWithinSameTransaction() {
        Transaction t = fx.txnManager.beginTransaction();
        exec.execute("INSERT INTO users VALUES (5,99,'eve')", t);
        ResultSet rs = exec.execute("SELECT * FROM users WHERE id = 5", t);
        assertEquals(1, rs.size());
    }

    @Test
    void crossTransactionVisibility() {
        Transaction writer = fx.txnManager.beginTransaction();
        exec.execute("INSERT INTO users VALUES (9,1,'zoe')", writer);

        // Reader started before writer commits: must not see the new row.
        Transaction reader = fx.txnManager.beginTransaction();
        assertEquals(4, exec.execute("SELECT * FROM users", reader).size());

        fx.txnManager.commitTransaction(writer);
        // A transaction started after the commit sees 5 rows.
        assertEquals(5, exec.execute("SELECT * FROM users", fx.txnManager.beginTransaction()).size());
    }

    @Test
    void emptyResultStillReportsColumns() {
        ResultSet rs = query("SELECT * FROM users WHERE age > 1000");
        assertEquals(0, rs.size());
        assertEquals(List.of("id", "age", "name"), rs.columns());
    }
}
