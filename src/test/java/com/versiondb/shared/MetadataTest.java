package com.versiondb.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetadataTest {

    @Test
    void tableMetaExposesFields() {
        TableMeta t = new TableMeta(1, "users", 5, 100L, 4);
        assertEquals(1, t.tableId());
        assertEquals("users", t.tableName());
        assertEquals(5, t.firstPageId());
        assertEquals(100L, t.numTuples());
        assertEquals(4, t.numPages());
    }

    @Test
    void columnAndIndexMeta() {
        ColumnMeta c = new ColumnMeta(1, "age", ColumnType.INT, 2, true);
        assertEquals(2, c.columnIndex());
        assertEquals(ColumnType.INT, c.columnType());

        IndexMeta idx = new IndexMeta(7, 1, "age", 12);
        assertEquals(7, idx.indexId());
        assertEquals(12, idx.rootPageId());
    }

    @Test
    void tableStatsIsImmutableCopyOfInput() {
        Map<String, ColumnStats> cols = new HashMap<>();
        cols.put("age", new ColumnStats(50, Value.ofInt(0), Value.ofInt(99), 3));

        TableStats stats = new TableStats(100L, 4, cols);

        // Mutating the source map must not affect the stored stats.
        cols.put("name", new ColumnStats(80, null, null, 0));
        assertEquals(1, stats.columnStats().size());

        // And the stored map is unmodifiable.
        assertThrows(UnsupportedOperationException.class,
                () -> stats.columnStats().put("x", new ColumnStats(1, null, null, 0)));
    }

    @Test
    void columnStatsAllowsNullBounds() {
        ColumnStats s = new ColumnStats(0, null, null, 0);
        assertEquals(0, s.numDistinct());
    }
}
