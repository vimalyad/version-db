package com.versiondb.query.plan;

import com.versiondb.query.ast.Expression;

/**
 * Equi-join of {@code outer} and {@code inner}: build a hash table on the inner
 * input keyed by {@code innerKey}, then probe it with each outer row's
 * {@code outerKey}. {@code joinCondition} is the full equality predicate.
 */
public record HashJoinPlan(PhysicalPlan outer, PhysicalPlan inner, Expression joinCondition,
                           Expression outerKey, Expression innerKey,
                           double estimatedRows, double estimatedCost)
        implements PhysicalPlan {
}
