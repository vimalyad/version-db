package com.versiondb.shared;

import java.util.Map;

/**
 * Table-level optimizer statistics: row and page counts plus per-column stats
 * keyed by column name. The column-stats map is defensively copied and exposed
 * as an unmodifiable view, keeping the record immutable.
 */
public record TableStats(long numTuples, int numPages, Map<String, ColumnStats> columnStats) {

    public TableStats {
        columnStats = Map.copyOf(columnStats);
    }
}
