package com.versiondb.query.plan;

import com.versiondb.query.ast.BinaryOp;
import com.versiondb.query.ast.ColumnRef;
import com.versiondb.query.ast.Literal;
import com.versiondb.query.ast.OrderByItem;
import com.versiondb.query.ast.SelectItem;
import com.versiondb.shared.Value;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhysicalPlanTest {

    private static BinaryOp pred() {
        return new BinaryOp(ColumnRef.of("age"), ">", Literal.ofInt(25));
    }

    @Test
    void seqScanCarriesPredicateAndEstimates() {
        SeqScanPlan p = new SeqScanPlan("users", pred(), 30.0, 12.5);
        assertEquals("users", p.tableName());
        assertEquals(pred(), p.predicate());
        assertEquals(30.0, p.estimatedRows());
        assertEquals(12.5, p.estimatedCost());
        assertInstanceOf(PhysicalPlan.class, p);
    }

    @Test
    void indexScanCarriesBoundsAndPostFilter() {
        IndexScanPlan p = new IndexScanPlan("users", "age", 7,
                Value.ofInt(26), null, pred(), 5.0, 9.0);
        assertEquals("age", p.indexColumn());
        assertEquals(7, p.rootPageId());
        assertEquals(Value.ofInt(26), p.low());
        assertNull(p.high());
        assertEquals(pred(), p.postFilterPredicate());
    }

    @Test
    void compositeNodesHoldChildren() {
        SeqScanPlan scan = new SeqScanPlan("users", null, 100, 100);
        FilterPlan filter = new FilterPlan(pred(), scan, 30, 101);
        ProjectionPlan proj = new ProjectionPlan(
                List.of(SelectItem.of(ColumnRef.of("name"))), filter, 30, 101.3);
        SortPlan sort = new SortPlan(proj, List.of(new OrderByItem(ColumnRef.of("name"), true)), 30, 150);
        LimitPlan limit = new LimitPlan(sort, 10, 0, 10, 150);

        assertSame(scan, filter.child());
        assertSame(filter, proj.child());
        assertSame(proj, sort.child());
        assertSame(sort, limit.child());
        assertEquals(10, limit.limit());
        assertEquals(0, limit.offset());
        assertEquals(1, proj.projections().size());
    }

    @Test
    void joinNodesHoldBothInputs() {
        SeqScanPlan a = new SeqScanPlan("a", null, 10, 10);
        SeqScanPlan b = new SeqScanPlan("b", null, 20, 20);
        BinaryOp cond = new BinaryOp(ColumnRef.of("x"), "=", ColumnRef.of("y"));

        NestedLoopJoinPlan nlj = new NestedLoopJoinPlan(a, b, cond, 200, 210);
        assertSame(a, nlj.outer());
        assertSame(b, nlj.inner());

        HashJoinPlan hj = new HashJoinPlan(a, b, cond, ColumnRef.of("x"), ColumnRef.of("y"), 200, 50);
        assertEquals(ColumnRef.of("x"), hj.outerKey());
        assertEquals(ColumnRef.of("y"), hj.innerKey());
    }

    @Test
    void projectionDefensivelyCopiesList() {
        var list = new java.util.ArrayList<SelectItem>();
        list.add(SelectItem.of(ColumnRef.of("name")));
        ProjectionPlan p = new ProjectionPlan(list, new SeqScanPlan("t", null, 1, 1), 1, 1);
        list.clear();
        assertEquals(1, p.projections().size());
    }
}
