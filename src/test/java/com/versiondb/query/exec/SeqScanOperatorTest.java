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

class SeqScanOperatorTest {

    @TempDir
    Path tmp;
    private ExecFixture fx;

    @BeforeEach
    void setUp() throws IOException {
        fx = new ExecFixture(tmp);
        fx.createTable("users", List.of(
                new ColumnDef("id", ColumnType.INT, false),
                new ColumnDef("age", ColumnType.INT, false)));
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void scanReturnsAllCommittedRows() {
        Transaction t = fx.txnManager.beginTransaction();
        for (int i = 1; i <= 5; i++) {
            fx.insert(t, "users", List.of(Value.ofInt(i), Value.ofInt(i * 10)));
        }
        fx.txnManager.commitTransaction(t);

        Transaction reader = fx.txnManager.beginTransaction();
        List<List<Value>> rows = ExecFixture.drain(new SeqScanOperator(fx.context(reader), "users", null));
        assertEquals(5, rows.size());
    }

    @Test
    void scanAppliesPushedPredicate() {
        Transaction t = fx.txnManager.beginTransaction();
        for (int i = 1; i <= 5; i++) {
            fx.insert(t, "users", List.of(Value.ofInt(i), Value.ofInt(i * 10)));
        }
        fx.txnManager.commitTransaction(t);

        // WHERE age > 25  →  rows with age 30, 40, 50
        BinaryOp pred = new BinaryOp(ColumnRef.of("age"), ">", Literal.ofInt(25));
        Transaction reader = fx.txnManager.beginTransaction();
        List<List<Value>> rows = ExecFixture.drain(new SeqScanOperator(fx.context(reader), "users", pred));
        assertEquals(3, rows.size());
        for (List<Value> row : rows) {
            assertTrue(row.get(1).asInt() > 25);
        }
    }

    @Test
    void scanRespectsSnapshotVisibility() {
        // Reader's snapshot is taken before the writer commits → sees nothing.
        Transaction writer = fx.txnManager.beginTransaction();
        fx.insert(writer, "users", List.of(Value.ofInt(1), Value.ofInt(10)));

        Transaction reader = fx.txnManager.beginTransaction(); // snapshot excludes writer's uncommitted row
        assertEquals(0, ExecFixture.drain(new SeqScanOperator(fx.context(reader), "users", null)).size());

        fx.txnManager.commitTransaction(writer);
        // A new transaction started after the commit sees the row.
        Transaction later = fx.txnManager.beginTransaction();
        assertEquals(1, ExecFixture.drain(new SeqScanOperator(fx.context(later), "users", null)).size());
    }

    @Test
    void scanSeesOwnUncommittedInserts() {
        Transaction t = fx.txnManager.beginTransaction();
        fx.insert(t, "users", List.of(Value.ofInt(1), Value.ofInt(10)));
        // Same transaction should see its own write.
        assertEquals(1, ExecFixture.drain(new SeqScanOperator(fx.context(t), "users", null)).size());
    }
}
