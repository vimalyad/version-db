package com.versiondb.query.plan;

import com.versiondb.query.ast.Expression;

/**
 * Full table scan with an optional pushed-down predicate applied tuple-by-tuple
 * during the scan ({@code predicate} may be {@code null}).
 */
public record SeqScanPlan(String tableName, Expression predicate,
                          double estimatedRows, double estimatedCost)
        implements PhysicalPlan {
}
