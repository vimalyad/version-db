package com.minidb.query.plan;

import com.minidb.query.ast.OrderByItem;

import java.util.List;

/** Sort {@code child}'s output by the {@code orderBy} keys. */
public record SortPlan(PhysicalPlan child, List<OrderByItem> orderBy,
                       double estimatedRows, double estimatedCost)
        implements PhysicalPlan {

    public SortPlan {
        orderBy = List.copyOf(orderBy);
    }
}
