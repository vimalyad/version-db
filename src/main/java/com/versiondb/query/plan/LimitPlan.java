package com.versiondb.query.plan;

/**
 * Restrict {@code child}'s output to at most {@code limit} rows after skipping
 * {@code offset} rows. Either bound may be {@code null} (no limit / no offset).
 */
public record LimitPlan(PhysicalPlan child, Integer limit, Integer offset,
                        double estimatedRows, double estimatedCost)
        implements PhysicalPlan {
}
