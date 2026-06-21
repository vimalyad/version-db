package com.versiondb.query.plan;

import com.versiondb.query.ast.Expression;

/** Apply {@code predicate} to the rows produced by {@code child}. */
public record FilterPlan(Expression predicate, PhysicalPlan child,
                         double estimatedRows, double estimatedCost)
        implements PhysicalPlan {
}
