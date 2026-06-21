package com.versiondb.query.plan;

/**
 * A node in a physical query plan: a descriptor of <em>how</em> to compute a
 * part of a query ({@code part2.md} §5.3). The optimizer produces a tree of
 * these; the execution engine (Phase 15) builds the actual Volcano operators
 * from them. Every node carries the optimizer's estimates so plans can be
 * compared and join orders chosen.
 *
 * <p>Nodes are immutable: the optimizer computes a node's estimates from its
 * children before constructing it (bottom-up), so the values are final.
 */
public sealed interface PhysicalPlan
        permits SeqScanPlan, IndexScanPlan, FilterPlan, ProjectionPlan,
                NestedLoopJoinPlan, HashJoinPlan, SortPlan, LimitPlan {

    /** Estimated number of rows this node outputs. */
    double estimatedRows();

    /** Estimated cumulative cost of producing this node's output (incl. children). */
    double estimatedCost();
}
