package com.minidb.query.plan;

import com.minidb.query.ast.SelectItem;

import java.util.List;

/** Compute the output columns/expressions ({@code projections}) of {@code child}. */
public record ProjectionPlan(List<SelectItem> projections, PhysicalPlan child,
                             double estimatedRows, double estimatedCost)
        implements PhysicalPlan {

    public ProjectionPlan {
        projections = List.copyOf(projections);
    }
}
