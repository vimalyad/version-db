package com.versiondb.query.exec;

import com.versiondb.query.ast.BinaryOp;
import com.versiondb.query.ast.ColumnRef;
import com.versiondb.query.ast.Literal;
import com.versiondb.query.ast.SelectItem;
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

class FilterProjectionTest {

    @TempDir
    Path tmp;
    private ExecFixture fx;

    @BeforeEach
    void setUp() throws IOException {
        fx = new ExecFixture(tmp);
        fx.createTable("users", List.of(
                new ColumnDef("id", ColumnType.INT, false),
                new ColumnDef("age", ColumnType.INT, false)));
        Transaction t = fx.txnManager.beginTransaction();
        for (int i = 1; i <= 5; i++) {
            fx.insert(t, "users", List.of(Value.ofInt(i), Value.ofInt(i * 10)));
        }
        fx.txnManager.commitTransaction(t);
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    private Operator scan() {
        return new SeqScanOperator(fx.context(fx.txnManager.beginTransaction()), "users", null);
    }

    @Test
    void filterPassesOnlyMatchingRows() {
        BinaryOp pred = new BinaryOp(ColumnRef.of("age"), ">=", Literal.ofInt(30));
        List<List<Value>> rows = ExecFixture.drain(new FilterOperator(scan(), pred));
        assertEquals(3, rows.size()); // 30,40,50
    }

    @Test
    void projectionSelectsRequestedColumns() {
        ProjectionOperator proj = new ProjectionOperator(scan(),
                List.of(SelectItem.of(ColumnRef.of("age"))));
        List<List<Value>> rows = ExecFixture.drain(proj);
        assertEquals(5, rows.size());
        for (List<Value> row : rows) {
            assertEquals(1, row.size()); // only the age column
        }
    }

    @Test
    void projectionEvaluatesExpressionsAndAliases() {
        // SELECT age + 1 AS next_age
        SelectItem item = new SelectItem(new BinaryOp(ColumnRef.of("age"), "+", Literal.ofInt(1)), "next_age");
        ProjectionOperator proj = new ProjectionOperator(scan(), List.of(item));
        proj.open();
        Tuple first = proj.next();
        proj.close();
        assertNotNull(first);
        assertEquals("next_age", first.column(0));
        // ages are multiples of ten, so age+1 ends in 1
        assertEquals(1, first.value(0).asInt() % 10);
    }

    @Test
    void filterThenProjectionPipelines() {
        BinaryOp pred = new BinaryOp(ColumnRef.of("age"), ">", Literal.ofInt(25));
        ProjectionOperator proj = new ProjectionOperator(
                new FilterOperator(scan(), pred), List.of(SelectItem.of(ColumnRef.of("id"))));
        assertEquals(3, ExecFixture.drain(proj).size());
    }
}
