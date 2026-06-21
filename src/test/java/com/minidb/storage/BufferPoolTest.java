package com.minidb.storage;

import com.minidb.shared.StorageException;
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

    // ---- 2.2: fetchPage / unpin / newPage / markDirty ------------------------

    @Test
    void fetchPageLoadsFromDiskOnMiss() {
        int pid = diskManager.allocatePage();
        BufferPool pool = new BufferPool(4, diskManager, noOpWal);
        Page page = pool.fetchPage(pid);
        assertNotNull(page);
        assertEquals(pid, page.getPageId());
    }

    @Test
    void fetchPageCacheHitReturnsSamePage() {
        int pid = diskManager.allocatePage();
        BufferPool pool = new BufferPool(4, diskManager, noOpWal);
        Page first = pool.fetchPage(pid);
        pool.unpin(pid, false);
        Page second = pool.fetchPage(pid);
        assertSame(first, second);
    }

    @Test
    void fetchPageIncrementsPinCount() {
        int pid = diskManager.allocatePage();
        BufferPool pool = new BufferPool(4, diskManager, noOpWal);
        pool.fetchPage(pid);
        pool.fetchPage(pid);
        assertEquals(2, findFrame(pool, pid).pinCount);
    }

    @Test
    void unpinDecrementsPinCount() {
        int pid = diskManager.allocatePage();
        BufferPool pool = new BufferPool(4, diskManager, noOpWal);
        pool.fetchPage(pid);
        pool.fetchPage(pid);
        pool.unpin(pid, false);
        assertEquals(1, findFrame(pool, pid).pinCount);
    }

    @Test
    void unpinWithDirtyMarksDirty() {
        int pid = diskManager.allocatePage();
        BufferPool pool = new BufferPool(4, diskManager, noOpWal);
        pool.fetchPage(pid);
        pool.unpin(pid, true);
        assertTrue(findFrame(pool, pid).isDirty);
    }

    @Test
    void unpinWithoutDirtyDoesNotMarkDirty() {
        int pid = diskManager.allocatePage();
        BufferPool pool = new BufferPool(4, diskManager, noOpWal);
        pool.fetchPage(pid);
        pool.unpin(pid, false);
        assertFalse(findFrame(pool, pid).isDirty);
    }

    @Test
    void markDirtyMarksFrame() {
        int pid = diskManager.allocatePage();
        BufferPool pool = new BufferPool(4, diskManager, noOpWal);
        pool.fetchPage(pid);
        assertFalse(findFrame(pool, pid).isDirty);
        pool.markDirty(pid);
        assertTrue(findFrame(pool, pid).isDirty);
    }

    @Test
    void newPageAllocatesAndReturnsPinnedPage() {
        BufferPool pool = new BufferPool(4, diskManager, noOpWal);
        Page page = pool.newPage();
        assertNotNull(page);
        Frame f = findFrame(pool, page.getPageId());
        assertNotNull(f);
        assertEquals(1, f.pinCount);
    }

    // ---- 2.3: Clock eviction -------------------------------------------------

    @Test
    void evictionSkipsPinnedFrames() {
        int p0 = diskManager.allocatePage();
        int p1 = diskManager.allocatePage();
        int p2 = diskManager.allocatePage();
        int p3 = diskManager.allocatePage();
        BufferPool pool = new BufferPool(3, diskManager, noOpWal);
        pool.fetchPage(p0);
        pool.fetchPage(p1);
        pool.fetchPage(p2);
        // All 3 frames pinned — fetching a 4th must throw
        assertThrows(StorageException.class, () -> pool.fetchPage(p3));
    }

    @Test
    void evictionRespectsSecondChance() {
        int p0 = diskManager.allocatePage();
        int p1 = diskManager.allocatePage();
        int p2 = diskManager.allocatePage();
        BufferPool pool = new BufferPool(2, diskManager, noOpWal);
        pool.fetchPage(p0);
        pool.fetchPage(p1);
        // p1 stays pinned, p0 unpinned — p0 must be evicted (after second-chance clear)
        pool.unpin(p0, false);
        Page p2Page = pool.fetchPage(p2);
        assertNotNull(p2Page);
        assertNull(findFrame(pool, p0));  // p0 evicted
        assertNotNull(findFrame(pool, p1)); // p1 still present
    }

    @Test
    void cleanEvictionRemovesFromPageTable() {
        int p0 = diskManager.allocatePage();
        int p1 = diskManager.allocatePage();
        BufferPool pool = new BufferPool(1, diskManager, noOpWal);
        pool.fetchPage(p0);
        pool.unpin(p0, false);
        pool.fetchPage(p1);
        assertNull(findFrame(pool, p0));
    }

    @Test
    void dirtyVictimFlushedBeforeEviction() {
        int p0 = diskManager.allocatePage();
        int p1 = diskManager.allocatePage();
        BufferPool pool = new BufferPool(1, diskManager, noOpWal);
        Page page = pool.fetchPage(p0);
        byte[] payload = new byte[]{1, 2, 3};
        page.insertTuple(payload);
        pool.unpin(p0, true);

        pool.fetchPage(p1); // triggers eviction of dirty p0

        Page reread = diskManager.readPage(p0);
        assertArrayEquals(payload, reread.getTuple(0));
    }

    // ---- helpers -------------------------------------------------------------

    private Frame findFrame(BufferPool pool, int pageId) {
        for (Frame f : pool.frames) {
            if (f.pageId == pageId) return f;
        }
        return null;
    }
}
