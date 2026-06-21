package com.minidb.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class HeapFileTest {

    @TempDir
    Path tmp;

    private DiskManager dm;

    private BufferPool newPool(int capacity) {
        dm = new DiskManager(tmp.resolve("heap.db"));
        // No-op WAL callback until the WAL phase exists.
        return new BufferPool(capacity, dm, lsn -> { });
    }

    @AfterEach
    void closeDisk() {
        if (dm != null) {
            dm.close(); // release the file handle so @TempDir can delete it on Windows
        }
    }

    @Test
    void createAllocatesFirstPage() {
        BufferPool bp = newPool(8);
        HeapFile heap = HeapFile.create(bp);

        assertTrue(heap.getFirstPageId() >= 0);
        assertEquals(1, heap.getPageCount());
    }

    @Test
    void openExistingHeapWalksChain() {
        BufferPool bp = newPool(8);
        HeapFile created = HeapFile.create(bp);
        int firstPageId = created.getFirstPageId();

        HeapFile reopened = new HeapFile(bp, firstPageId);
        assertEquals(1, reopened.getPageCount());
        assertEquals(firstPageId, reopened.getFirstPageId());
    }
}
