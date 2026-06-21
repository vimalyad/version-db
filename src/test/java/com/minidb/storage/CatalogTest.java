package com.minidb.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CatalogTest {

    @TempDir
    Path tmp;

    /** A buffer pool large enough that nothing is evicted mid-test. */
    private BufferPool pool(DiskManager dm) {
        return new BufferPool(64, dm, lsn -> { });
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
}
