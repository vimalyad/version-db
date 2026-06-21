package com.versiondb.query.exec;

import com.versiondb.query.ast.ColumnRef;
import com.versiondb.query.ast.FunctionCall;
import com.versiondb.query.ast.OrderByItem;
import com.versiondb.query.ast.SelectItem;
import com.versiondb.query.ast.Star;
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

class SortLimitAggregateTest {

    @TempDir
    Path tmp;
    private ExecFixture fx;

    @BeforeEach
    void setUp() throws IOException {
        fx = new ExecFixture(tmp);
        fx.createTable("t", List.of(
                new ColumnDef("id", ColumnType.INT, false),
                new ColumnDef("v", ColumnType.INT, true)));
        Transaction txn = fx.txnManager.beginTransaction();
        int[] vs = {30, 10, 50, 20, 40};
        for (int i = 0; i < vs.length; i++) {
            fx.insert(txn, "t", List.of(Value.ofInt(i), Value.ofInt(vs[i])));
        }
        fx.txnManager.commitTransaction(txn);
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private Operator scan() {
        return new SeqScanOperator(fx.context(fx.txnManager.beginTransaction()), "t", null);
    }

    @Test
    void sortAscendingAndDescending() {
        SortOperator asc = new SortOperator(scan(), List.of(new OrderByItem(ColumnRef.of("v"), true)));
        List<List<Value>> rows = ExecFixture.drain(asc);
        assertEquals(10, rows.get(0).get(1).asInt());
        assertEquals(50, rows.get(4).get(1).asInt());

        SortOperator desc = new SortOperator(scan(), List.of(new OrderByItem(ColumnRef.of("v"), false)));
        List<List<Value>> drows = ExecFixture.drain(desc);
        assertEquals(50, drows.get(0).get(1).asInt());
        assertEquals(10, drows.get(4).get(1).asInt());
    }

    @Test
    void limitWithOffset() {
        SortOperator sorted = new SortOperator(scan(), List.of(new OrderByItem(ColumnRef.of("v"), true)));
        LimitOperator limit = new LimitOperator(sorted, 2, 1); // skip 10, take 20,30
        List<List<Value>> rows = ExecFixture.drain(limit);
        assertEquals(2, rows.size());
        assertEquals(20, rows.get(0).get(1).asInt());
        assertEquals(30, rows.get(1).get(1).asInt());
    }

    @Test
    void limitStopsEarly() {
        LimitOperator limit = new LimitOperator(scan(), 1, null);
        assertEquals(1, ExecFixture.drain(limit).size());
    }

    @Test
    void countStarCountsAllRows() {
        SelectItem countStar = new SelectItem(new FunctionCall("COUNT", List.of(new Star())), null);
        AggregateOperator agg = new AggregateOperator(scan(), List.of(countStar));
        List<List<Value>> rows = ExecFixture.drain(agg);
        assertEquals(1, rows.size());
        assertEquals(5, rows.get(0).get(0).asInt());
    }

    @Test
    void countColumnIgnoresNulls() {
        // Insert a row with NULL v.
        Transaction txn = fx.txnManager.beginTransaction();
        fx.insert(txn, "t", List.of(Value.ofInt(99), Value.nullValue(ColumnType.INT)));
        fx.txnManager.commitTransaction(txn);

        SelectItem countV = new SelectItem(new FunctionCall("COUNT", List.of(ColumnRef.of("v"))), "n");
        AggregateOperator agg = new AggregateOperator(scan(), List.of(countV));
        agg.open();
        Tuple row = agg.next();
        agg.close();
        assertNotNull(row);
        assertEquals(5, row.value(0).asInt()); // 6 rows, one NULL v → count 5
        assertEquals("n", row.column(0));      // alias used as the output column name
    }

    @Test
    void isAggregateDetectsFunctions() {
        assertTrue(AggregateOperator.isAggregate(List.of(
                new SelectItem(new FunctionCall("COUNT", List.of(new Star())), null))));
        assertFalse(AggregateOperator.isAggregate(List.of(SelectItem.of(ColumnRef.of("v")))));
    }
}
