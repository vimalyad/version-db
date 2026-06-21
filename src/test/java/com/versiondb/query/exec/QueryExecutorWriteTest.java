package com.versiondb.query.exec;

import com.versiondb.txn.Transaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryExecutorWriteTest {

    @TempDir
    Path tmp;
    private ExecFixture fx;
    private QueryExecutor exec;

    @BeforeEach
    void setUp() throws IOException {
        fx = new ExecFixture(tmp);
        exec = fx.executor();
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private int rowCount(String table, Transaction txn) {
        return ExecFixture.drain(new SeqScanOperator(fx.context(txn), table, null)).size();
    }

    @Test
    void createTableThenInsertAndReadBack() {
        Transaction txn = fx.txnManager.beginTransaction();
        exec.execute("CREATE TABLE t (id INT, v INT)", txn);
        assertEquals(3, exec.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30)", txn).affectedRows());
        assertEquals(3, rowCount("t", txn));
    }

    @Test
    void deleteRemovesMatchingRows() {
        Transaction txn = fx.txnManager.beginTransaction();
        exec.execute("CREATE TABLE t (id INT, v INT)", txn);
        exec.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30)", txn);
        assertEquals(2, exec.execute("DELETE FROM t WHERE v > 15", txn).affectedRows());
        assertEquals(1, rowCount("t", txn));
    }

    @Test
    void updateChangesValues() {
        Transaction txn = fx.txnManager.beginTransaction();
        exec.execute("CREATE TABLE t (id INT, v INT)", txn);
        exec.execute("INSERT INTO t VALUES (1,10),(2,20)", txn);
        assertEquals(1, exec.execute("UPDATE t SET v = v + 5 WHERE id = 1", txn).affectedRows());

        // Read back id=1's v via a scan.
        List<List<com.versiondb.shared.Value>> rows =
                ExecFixture.drain(new SeqScanOperator(fx.context(txn), "t", null));
        long v1 = rows.stream().filter(r -> r.get(0).asInt() == 1).findFirst().orElseThrow().get(1).asInt();
        assertEquals(15, v1);
    }

    @Test
    void insertWithColumnListLeavesOthersNull() {
        Transaction txn = fx.txnManager.beginTransaction();
        exec.execute("CREATE TABLE t (id INT, v INT)", txn);
        exec.execute("INSERT INTO t (id) VALUES (9)", txn);
        List<List<com.versiondb.shared.Value>> rows =
                ExecFixture.drain(new SeqScanOperator(fx.context(txn), "t", null));
        assertEquals(1, rows.size());
        assertEquals(9, rows.get(0).get(0).asInt());
        assertTrue(rows.get(0).get(1).isNull()); // v defaulted to NULL
    }

    @Test
    void createIndexBuildsAndRegisters() {
        Transaction txn = fx.txnManager.beginTransaction();
        exec.execute("CREATE TABLE t (id INT, v INT)", txn);
        exec.execute("INSERT INTO t VALUES (1,10),(2,20),(3,30)", txn);
        fx.txnManager.commitTransaction(txn);

        assertEquals(0, exec.execute("CREATE INDEX idx ON t (v)", fx.txnManager.beginTransaction()).affectedRows());
        assertEquals(1, fx.indexes.size());

        int rootId = fx.indexes.keySet().iterator().next();
        Transaction r = fx.txnManager.beginTransaction();
        IndexScanOperator op = new IndexScanOperator(
                fx.context(r), "t", rootId, com.versiondb.shared.Value.ofInt(20), com.versiondb.shared.Value.ofInt(30), null);
        assertEquals(2, ExecFixture.drain(op).size());
    }

    @Test
    void dropTableRejected() {
        Transaction txn = fx.txnManager.beginTransaction();
        exec.execute("CREATE TABLE t (id INT)", txn);
        assertThrows(RuntimeException.class, () -> exec.execute("DROP TABLE t", txn));
    }
}
