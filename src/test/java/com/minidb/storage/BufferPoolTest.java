package com.minidb.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BufferPoolTest {

    @TempDir
    Path tempDir;

    private DiskManager diskManager;
    private WalFlushCallback noOpWal;

    @BeforeEach
    void setUp() {
        diskManager = new DiskManager(tempDir.resolve("test.db"));
        noOpWal = lsn -> { };
    }

    @AfterEach
    void tearDown() {
        diskManager.close();
    }

    // ---- 2.1: Frame + frame array + page table --------------------------------

    @Test
    void constructorInitialisesEmptyFrameArray() {
        BufferPool pool = new BufferPool(8, diskManager, noOpWal);
        assertEquals(8, pool.capacity());
        for (Frame f : pool.frames) {
            assertEquals(-1, f.pageId);
            assertNull(f.page);
            assertEquals(0, f.pinCount);
            assertFalse(f.isDirty);
            assertFalse(f.refBit);
        }
    }

    @Test
    void constructorRejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class,
                () -> new BufferPool(0, diskManager, noOpWal));
        assertThrows(IllegalArgumentException.class,
                () -> new BufferPool(-1, diskManager, noOpWal));
    }
}
