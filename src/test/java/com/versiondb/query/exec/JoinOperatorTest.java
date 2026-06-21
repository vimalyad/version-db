package com.versiondb.query.exec;

import com.versiondb.query.ast.BinaryOp;
import com.versiondb.query.ast.ColumnRef;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JoinOperatorTest {

    @TempDir
    Path tmp;
    private ExecFixture fx;

    @BeforeEach
    void setUp() throws IOException {
        fx = new ExecFixture(tmp);
        fx.createTable("a", List.of(
                new ColumnDef("id", ColumnType.INT, false),
                new ColumnDef("av", ColumnType.INT, false)));
        fx.createTable("b", List.of(
                new ColumnDef("aid", ColumnType.INT, false),
                new ColumnDef("bv", ColumnType.INT, false)));

        Transaction t = fx.txnManager.beginTransaction();
        for (int i = 1; i <= 6; i++) {
            fx.insert(t, "a", List.of(Value.ofInt(i), Value.ofInt(i * 100)));
        }
        // b references a.id via aid; some ids repeat, some (like 99) have no match.
        int[] aids = {1, 2, 2, 3, 5, 99};
        for (int i = 0; i < aids.length; i++) {
            fx.insert(t, "b", List.of(Value.ofInt(aids[i]), Value.ofInt(i)));
        }
        fx.txnManager.commitTransaction(t);
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private Operator scan(Transaction txn, String table) {
        return new SeqScanOperator(fx.context(txn), table, null);
    }

    private static List<String> normalize(List<List<Value>> rows) {
        List<String> out = new ArrayList<>();
        for (List<Value> row : rows) {
            out.add(row.toString());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    @Test
    void nestedLoopAndHashJoinProduceIdenticalResults() {
        BinaryOp cond = new BinaryOp(ColumnRef.of("id"), "=", ColumnRef.of("aid"));

        Transaction t1 = fx.txnManager.beginTransaction();
        NestedLoopJoinOperator nlj = new NestedLoopJoinOperator(scan(t1, "a"), scan(t1, "b"), cond);
        List<String> nljRows = normalize(ExecFixture.drain(nlj));

        Transaction t2 = fx.txnManager.beginTransaction();
        HashJoinOperator hash = new HashJoinOperator(
                scan(t2, "a"), scan(t2, "b"), ColumnRef.of("id"), ColumnRef.of("aid"), cond);
        List<String> hashRows = normalize(ExecFixture.drain(hash));

        assertEquals(nljRows, hashRows);
        // ids 1,2,2,3,5 match (99 has no a-row) → 5 joined rows
        assertEquals(5, nljRows.size());
    }

    @Test
    void mergedRowHasBothTablesColumns() {
        BinaryOp cond = new BinaryOp(ColumnRef.of("id"), "=", ColumnRef.of("aid"));
        Transaction t = fx.txnManager.beginTransaction();
        HashJoinOperator hash = new HashJoinOperator(
                scan(t, "a"), scan(t, "b"), ColumnRef.of("id"), ColumnRef.of("aid"), cond);
        hash.open();
        Tuple row = hash.next();
        hash.close();
        assertNotNull(row);
        assertEquals(4, row.size()); // a.id, a.av, b.aid, b.bv
        assertEquals(row.get("a", "id").asInt(), row.get("b", "aid").asInt());
    }

    @Test
    void crossJoinProducesProduct() {
        Transaction t = fx.txnManager.beginTransaction();
        NestedLoopJoinOperator cross = new NestedLoopJoinOperator(scan(t, "a"), scan(t, "b"), null);
        assertEquals(6 * 6, ExecFixture.drain(cross).size());
    }
}
