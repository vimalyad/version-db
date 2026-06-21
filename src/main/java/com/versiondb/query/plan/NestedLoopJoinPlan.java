package com.versiondb.query.plan;

import com.versiondb.query.ast.Expression;

/**
 * Join {@code outer} and {@code inner} by scanning the inner input once per
 * outer row, keeping pairs that satisfy {@code joinCondition} (which may be
 * {@code null} for a cross join).
 */
public record NestedLoopJoinPlan(PhysicalPlan outer, PhysicalPlan inner, Expression joinCondition,
                                 double estimatedRows, double estimatedCost)
        implements PhysicalPlan {
}
