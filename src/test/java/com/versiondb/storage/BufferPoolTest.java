package com.versiondb.storage;

import com.versiondb.shared.StorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    // ---- 2.4: flushPage / flushAll + WAL rule hook ---------------------------

    @Test
    void walCallbackInvokedBeforeDirtyEviction() {
        int p0 = diskManager.allocatePage();
        int p1 = diskManager.allocatePage();
        List<Long> flushedLsns = new ArrayList<>();
        BufferPool pool = new BufferPool(1, diskManager, flushedLsns::add);
        Page page = pool.fetchPage(p0);
        page.setLsn(42L);
        pool.unpin(p0, true);

        pool.fetchPage(p1); // evicts dirty p0 — WAL callback must fire with lsn=42

        assertEquals(1, flushedLsns.size());
        assertEquals(42L, flushedLsns.get(0));
    }

    @Test
    void flushPageWritesDirtyPageAndMarksClean() {
        int pid = diskManager.allocatePage();
        BufferPool pool = new BufferPool(4, diskManager, noOpWal);
        Page page = pool.fetchPage(pid);
        byte[] payload = new byte[]{7, 8, 9};
        page.insertTuple(payload);
        pool.markDirty(pid);

        pool.flushPage(pid);

        assertFalse(findFrame(pool, pid).isDirty);
        Page reread = diskManager.readPage(pid);
        assertArrayEquals(payload, reread.getTuple(0));
    }

    @Test
    void flushPageIsNoOpForCleanPage() {
        int pid = diskManager.allocatePage();
        List<Long> walCalls = new ArrayList<>();
        BufferPool pool = new BufferPool(4, diskManager, walCalls::add);
        pool.fetchPage(pid); // not marked dirty
        pool.flushPage(pid);
        assertTrue(walCalls.isEmpty());
    }

    @Test
    void flushPageInvokesWalCallback() {
        int pid = diskManager.allocatePage();
        List<Long> walLsns = new ArrayList<>();
        BufferPool pool = new BufferPool(4, diskManager, walLsns::add);
        Page page = pool.fetchPage(pid);
        page.setLsn(99L);
        pool.markDirty(pid);
        pool.flushPage(pid);
        assertEquals(List.of(99L), walLsns);
    }

    @Test
    void flushAllFlushesEveryDirtyFrame() {
        int p0 = diskManager.allocatePage();
        int p1 = diskManager.allocatePage();
        int p2 = diskManager.allocatePage();
        List<Long> walCalls = new ArrayList<>();
        BufferPool pool = new BufferPool(4, diskManager, lsn -> walCalls.add(lsn));

        pool.fetchPage(p0); pool.markDirty(p0); pool.unpin(p0, false);
        pool.fetchPage(p1);                     pool.unpin(p1, false); // clean
        pool.fetchPage(p2); pool.markDirty(p2); pool.unpin(p2, false);

        pool.flushAll();

        assertFalse(findFrame(pool, p0).isDirty);
        assertFalse(findFrame(pool, p2).isDirty);
        assertEquals(2, walCalls.size()); // p1 is clean — no WAL call
    }

    // ---- 2.5: Thread safety smoke test ---------------------------------------

    @Test
    void concurrentFetchUnpinDoesNotCorrupt() throws InterruptedException {
        int numPages = 8;
        int poolSize = 4;
        for (int i = 0; i < numPages; i++) diskManager.allocatePage();
        BufferPool pool = new BufferPool(poolSize, diskManager, noOpWal);

        int threads = 4;
        int opsPerThread = 60;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        AtomicInteger unexpectedErrors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            executor.submit(() -> {
                ready.countDown();
                try { ready.await(); } catch (InterruptedException e) { return; }
                for (int i = 0; i < opsPerThread; i++) {
                    int pid = (tid * opsPerThread + i) % numPages;
                    try {
                        Page page = pool.fetchPage(pid);
                        assertNotNull(page);
                        pool.unpin(pid, false);
                    } catch (StorageException ignored) {
                        // pool exhausted under contention is acceptable
                    } catch (Exception e) {
                        unexpectedErrors.incrementAndGet();
                    }
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
        assertEquals(0, unexpectedErrors.get());
    }

    // ---- helpers -------------------------------------------------------------

    private Frame findFrame(BufferPool pool, int pageId) {
        for (Frame f : pool.frames) {
            if (f.pageId == pageId) return f;
        }
        return null;
    }
}
