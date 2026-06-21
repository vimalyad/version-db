package com.versiondb.query.exec;

import com.versiondb.query.ast.BinaryOp;
import com.versiondb.query.ast.ColumnRef;
import com.versiondb.query.ast.Literal;
import com.versiondb.shared.ColumnDef;
import com.versiondb.shared.ColumnType;
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

class IndexScanOperatorTest {

    @TempDir
    Path tmp;
    private ExecFixture fx;
    private int rootPageId;

    @BeforeEach
    void setUp() throws IOException {
        fx = new ExecFixture(tmp);
        fx.createTable("users", List.of(
                new ColumnDef("id", ColumnType.INT, false),
                new ColumnDef("age", ColumnType.INT, false)));
        Transaction t = fx.txnManager.beginTransaction();
        for (int i = 0; i < 50; i++) {
            fx.insert(t, "users", List.of(Value.ofInt(i), Value.ofInt(i)));
        }
        fx.txnManager.commitTransaction(t);
        rootPageId = fx.createIndex("users", "age"); // build index after data exists
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void rangeScanReturnsMatchingRows() {
        Transaction r = fx.txnManager.beginTransaction();
        IndexScanOperator op = new IndexScanOperator(
                fx.context(r), "users", rootPageId, Value.ofInt(10), Value.ofInt(20), null);
        List<List<Value>> rows = ExecFixture.drain(op);
        assertEquals(11, rows.size()); // 10..20 inclusive
        for (List<Value> row : rows) {
            long age = row.get(1).asInt();
            assertTrue(age >= 10 && age <= 20);
        }
    }

    @Test
    void postFilterIsApplied() {
        // Index range [10,20], post-filter age > 15 → 16..20
        BinaryOp post = new BinaryOp(ColumnRef.of("age"), ">", Literal.ofInt(15));
        Transaction r = fx.txnManager.beginTransaction();
        IndexScanOperator op = new IndexScanOperator(
                fx.context(r), "users", rootPageId, Value.ofInt(10), Value.ofInt(20), post);
        assertEquals(5, ExecFixture.drain(op).size());
    }

    @Test
    void openBoundsScanFromStart() {
        Transaction r = fx.txnManager.beginTransaction();
        IndexScanOperator op = new IndexScanOperator(
                fx.context(r), "users", rootPageId, null, Value.ofInt(4), null);
        assertEquals(5, ExecFixture.drain(op).size()); // 0..4
    }
}
