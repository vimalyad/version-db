package com.minidb.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minidb.shared.ColumnDef;
import com.minidb.shared.ColumnMeta;
import com.minidb.shared.ColumnType;
import com.minidb.shared.StorageException;
import com.minidb.shared.TableMeta;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogTest {

    @TempDir
    Path tmp;

    /** A buffer pool large enough that nothing is evicted mid-test. */
    private BufferPool pool(DiskManager dm) {
        return new BufferPool(64, dm, lsn -> { });
    }

    private static List<ColumnDef> usersSchema() {
        return List.of(
                new ColumnDef("id", ColumnType.INT, false),
                new ColumnDef("name", ColumnType.VARCHAR, true),
                new ColumnDef("active", ColumnType.BOOL, false));
    }

    @Test
    void freshDatabaseAllocatesThreeSystemHeaps() {
        try (DiskManager dm = new DiskManager(tmp.resolve("a.db"))) {
            new Catalog(pool(dm), dm);
            // Pages 0, 1, 2 are the tables, columns and indexes heaps.
            assertEquals(3, dm.getNumPages());
        }
    }

    @Test
    void existingDatabaseReopensWithoutReallocating() {
        Path path = tmp.resolve("b.db");
        try (DiskManager dm = new DiskManager(path)) {
            new Catalog(pool(dm), dm);
        }
        try (DiskManager dm = new DiskManager(path)) {
            assertDoesNotThrow(() -> new Catalog(pool(dm), dm));
            assertEquals(3, dm.getNumPages());
        }
    }

    @Test
    void createdTableIsLookedUpByName() {
        try (DiskManager dm = new DiskManager(tmp.resolve("c.db"))) {
            Catalog catalog = new Catalog(pool(dm), dm);
            TableMeta created = catalog.createTable("users", usersSchema());

            TableMeta found = catalog.getTable("users");
            assertEquals(created, found);
            assertEquals("users", found.tableName());
            // First user heap is allocated after the three system pages.
            assertEquals(3, found.firstPageId());
        }
    }

    @Test
    void columnOrderAndAttributesArePreserved() {
        try (DiskManager dm = new DiskManager(tmp.resolve("d.db"))) {
            Catalog catalog = new Catalog(pool(dm), dm);
            TableMeta t = catalog.createTable("users", usersSchema());

            List<ColumnMeta> columns = catalog.getColumns(t.tableId());
            assertEquals(3, columns.size());
            assertEquals("id", columns.get(0).columnName());
            assertEquals(ColumnType.INT, columns.get(0).columnType());
            assertEquals(0, columns.get(0).columnIndex());
            assertEquals("name", columns.get(1).columnName());
            assertEquals(ColumnType.VARCHAR, columns.get(1).columnType());
            assertTrue(columns.get(1).isNullable());
            assertEquals("active", columns.get(2).columnName());
            assertEquals(2, columns.get(2).columnIndex());
        }
    }

    @Test
    void duplicateTableNameIsRejected() {
        try (DiskManager dm = new DiskManager(tmp.resolve("e.db"))) {
            Catalog catalog = new Catalog(pool(dm), dm);
            catalog.createTable("users", usersSchema());
            assertThrows(StorageException.class, () -> catalog.createTable("users", usersSchema()));
        }
    }

    @Test
    void distinctTablesGetDistinctIdsAndHeaps() {
        try (DiskManager dm = new DiskManager(tmp.resolve("f.db"))) {
            Catalog catalog = new Catalog(pool(dm), dm);
            TableMeta a = catalog.createTable("a", usersSchema());
            TableMeta b = catalog.createTable("b", usersSchema());
            assertNotEquals(a.tableId(), b.tableId());
            assertNotEquals(a.firstPageId(), b.firstPageId());
        }
    }

    @Test
    void unknownTableReturnsNull() {
        try (DiskManager dm = new DiskManager(tmp.resolve("g.db"))) {
            Catalog catalog = new Catalog(pool(dm), dm);
            assertNull(catalog.getTable("missing"));
            assertTrue(catalog.getColumns(999).isEmpty());
        }
    }

    @Test
    void tablesAndColumnsSurviveReopen() {
        Path path = tmp.resolve("h.db");
        int tableId;
        try (DiskManager dm = new DiskManager(path)) {
            BufferPool bp = pool(dm);
            Catalog catalog = new Catalog(bp, dm);
            tableId = catalog.createTable("users", usersSchema()).tableId();
            bp.flushAll(); // make catalog writes durable before close
        }
        try (DiskManager dm = new DiskManager(path)) {
            Catalog catalog = new Catalog(pool(dm), dm);
            TableMeta found = catalog.getTable("users");
            assertEquals(tableId, found.tableId());
            List<ColumnMeta> columns = catalog.getColumns(tableId);
            assertEquals(3, columns.size());
            assertEquals("id", columns.get(0).columnName());
            assertEquals("name", columns.get(1).columnName());
            assertEquals("active", columns.get(2).columnName());
        }
    }

    @Test
    void tableIdsDoNotCollideAfterReopen() {
        Path path = tmp.resolve("i.db");
        int firstId;
        try (DiskManager dm = new DiskManager(path)) {
            BufferPool bp = pool(dm);
            Catalog catalog = new Catalog(bp, dm);
            firstId = catalog.createTable("a", usersSchema()).tableId();
            bp.flushAll();
        }
        try (DiskManager dm = new DiskManager(path)) {
            Catalog catalog = new Catalog(pool(dm), dm);
            TableMeta b = catalog.createTable("b", usersSchema());
            assertNotEquals(firstId, b.tableId());
        }
    }
}
