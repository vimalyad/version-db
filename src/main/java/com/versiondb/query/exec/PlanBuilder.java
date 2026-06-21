package com.versiondb.query.exec;

import com.versiondb.query.plan.FilterPlan;
import com.versiondb.query.plan.HashJoinPlan;
import com.versiondb.query.plan.IndexScanPlan;
import com.versiondb.query.plan.LimitPlan;
import com.versiondb.query.plan.NestedLoopJoinPlan;
import com.versiondb.query.plan.PhysicalPlan;
import com.versiondb.query.plan.ProjectionPlan;
import com.versiondb.query.plan.SeqScanPlan;
import com.versiondb.query.plan.SortPlan;
import com.versiondb.shared.StorageException;

/**
 * Builds a tree of Volcano {@link Operator}s from an optimizer {@link PhysicalPlan}.
 * A projection whose items contain an aggregate function becomes an
 * {@link AggregateOperator} rather than a plain projection.
 */
final class PlanBuilder {

    private PlanBuilder() {
    }

    static Operator build(PhysicalPlan plan, ExecutionContext ctx) {
        if (plan instanceof SeqScanPlan s) {
            return new SeqScanOperator(ctx, s.tableName(), s.predicate());
        }
        if (plan instanceof IndexScanPlan i) {
            return new IndexScanOperator(ctx, i.tableName(), i.rootPageId(),
                    i.low(), i.high(), i.postFilterPredicate());
        }
        if (plan instanceof FilterPlan f) {
            return new FilterOperator(build(f.child(), ctx), f.predicate());
        }
        if (plan instanceof ProjectionPlan p) {
            Operator child = build(p.child(), ctx);
            return AggregateOperator.isAggregate(p.projections())
                    ? new AggregateOperator(child, p.projections())
                    : new ProjectionOperator(child, p.projections());
        }
        if (plan instanceof NestedLoopJoinPlan n) {
            return new NestedLoopJoinOperator(build(n.outer(), ctx), build(n.inner(), ctx), n.joinCondition());
        }
        if (plan instanceof HashJoinPlan h) {
            return new HashJoinOperator(build(h.outer(), ctx), build(h.inner(), ctx),
                    h.outerKey(), h.innerKey(), h.joinCondition());
        }
        if (plan instanceof SortPlan s) {
            return new SortOperator(build(s.child(), ctx), s.orderBy());
        }
        if (plan instanceof LimitPlan l) {
            return new LimitOperator(build(l.child(), ctx), l.limit(), l.offset());
        }
        throw new StorageException("cannot build operator for plan " + plan.getClass().getSimpleName());
    }
}
