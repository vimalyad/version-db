package com.versiondb.shared;

/**
 * Per-column statistics used by the optimizer for selectivity estimation. The
 * min/max bounds may be {@code null} when statistics have not been gathered.
 */
public record ColumnStats(long numDistinct, Value minValue, Value maxValue, long nullCount) {
}
