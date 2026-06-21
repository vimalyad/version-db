package com.minidb.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minidb.shared.RID;
import com.minidb.shared.StorageException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    @Test
    void freeSpaceMapTracksEmptyFirstPage() {
        BufferPool bp = newPool(8);
        HeapFile heap = HeapFile.create(bp);

        int emptyPageFree = Page.create(0).getFreeSpace();
        assertEquals(emptyPageFree, heap.freeSpaceOf(heap.getFirstPageId()));
        assertEquals(-1, heap.freeSpaceOf(9999)); // unknown page
    }

    @Test
    void insertThenGetReturnsSameBytes() {
        BufferPool bp = newPool(8);
        HeapFile heap = HeapFile.create(bp);

        byte[] tuple = bytesOf(50, (byte) 7);
        RID rid = heap.insertTuple(tuple);

        assertArrayEquals(tuple, heap.getTuple(rid));
    }

    @Test
    void insertSpansMultiplePagesAndAllAreReadable() {
        BufferPool bp = newPool(16);
        HeapFile heap = HeapFile.create(bp);

        // Each tuple ~1000 bytes -> only a handful fit per 8 KB page, so 30
        // inserts must allocate several pages.
        int count = 30;
        List<RID> rids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rids.add(heap.insertTuple(bytesOf(1000, (byte) i)));
        }

        assertTrue(heap.getPageCount() > 1, "inserts should have allocated extra pages");
        for (int i = 0; i < count; i++) {
            assertArrayEquals(bytesOf(1000, (byte) i), heap.getTuple(rids.get(i)));
        }
    }

    @Test
    void insertDecreasesFreeSpace() {
        BufferPool bp = newPool(8);
        HeapFile heap = HeapFile.create(bp);
        int before = heap.freeSpaceOf(heap.getFirstPageId());

        RID rid = heap.insertTuple(bytesOf(100, (byte) 1));

        assertEquals(before - 100 - 8, heap.freeSpaceOf(rid.pageId()),
                "free space should drop by tuple length plus one slot entry");
    }

    @Test
    void deleteTombstonesTheTuple() {
        BufferPool bp = newPool(8);
        HeapFile heap = HeapFile.create(bp);
        RID rid = heap.insertTuple(bytesOf(20, (byte) 3));

        heap.deleteTuple(rid);

        assertNull(heap.getTuple(rid));
    }

    @Test
    void tupleLargerThanPageThrows() {
        BufferPool bp = newPool(8);
        HeapFile heap = HeapFile.create(bp);

        assertThrows(StorageException.class,
                () -> heap.insertTuple(bytesOf(HeapFile.MAX_TUPLE_SIZE + 1, (byte) 0)));
    }

    private static byte[] bytesOf(int len, byte fill) {
        byte[] b = new byte[len];
        Arrays.fill(b, fill);
        return b;
    }
}
