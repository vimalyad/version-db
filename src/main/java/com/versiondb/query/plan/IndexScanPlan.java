package com.versiondb.query.plan;

import com.versiondb.query.ast.Expression;
import com.versiondb.shared.Value;

/**
 * B+Tree range scan over {@code indexColumn} between {@code low} and {@code high}
 * (either bound may be {@code null} for an open end), followed by an optional
 * {@code postFilterPredicate} for any condition the index range does not fully
 * enforce. {@code rootPageId} locates the tree.
 */
public record IndexScanPlan(String tableName, String indexColumn, int rootPageId,
                            Value low, Value high, Expression postFilterPredicate,
                            double estimatedRows, double estimatedCost)
        implements PhysicalPlan {
}
