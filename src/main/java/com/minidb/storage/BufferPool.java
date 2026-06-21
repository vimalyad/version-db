package com.minidb.storage;

import com.minidb.shared.StorageException;

import java.util.HashMap;
import java.util.Map;

/**
 * In-memory cache of database pages. Every read and write of a page by any
 * upper layer goes through the buffer pool; only the buffer pool calls
 * {@link DiskManager} directly.
 *
 * <p>The pool holds a fixed number of {@link Frame frames}. When all frames
 * are occupied, Clock eviction selects a victim. A dirty victim is flushed to
 * disk first, respecting the WAL rule: the injected {@link WalFlushCallback}
 * is called before the write to guarantee WAL records are durable first.
 */
public final class BufferPool {

    private final DiskManager diskManager;
    private final WalFlushCallback walFlushCallback;

    /** Fixed-size frame array: the pool's backing store. */
    final Frame[] frames;

    /** Maps pageId &rarr; frame index for O(1) cache lookup. */
    private final Map<Integer, Integer> pageTable;

    /**
     * Create a buffer pool with the given capacity.
     *
     * @param capacity         number of frames; must be positive
     * @param diskManager      the disk manager used for all page I/O
     * @param walFlushCallback called before any dirty page is written to disk;
     *                         use a no-op lambda until WAL is implemented
     */
    public BufferPool(int capacity, DiskManager diskManager, WalFlushCallback walFlushCallback) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.diskManager = diskManager;
        this.walFlushCallback = walFlushCallback;
        this.frames = new Frame[capacity];
        this.pageTable = new HashMap<>(capacity * 2);
        for (int i = 0; i < capacity; i++) {
            frames[i] = new Frame();
        }
    }

    /** Return the number of frames in the pool. */
    public int capacity() {
        return frames.length;
    }

    /**
     * Bring a page into the pool, pinning it. If the page is already in the
     * pool, increment its pin count and return the cached page. If not, load
     * it from disk into a free frame. The caller must call {@link #unpin} when
     * done.
     */
    public Page fetchPage(int pageId) {
        Integer idx = pageTable.get(pageId);
        if (idx != null) {
            Frame f = frames[idx];
            f.pinCount++;
            f.refBit = true;
            return f.page;
        }

        // Cache miss — find a free frame (eviction added in 2.3)
        int freeIdx = findFreeFrame();
        if (freeIdx == -1) {
            throw new StorageException(
                    "buffer pool full: no free frame available (pool size " + frames.length + ")");
        }
        Frame f = frames[freeIdx];
        f.page = diskManager.readPage(pageId);
        f.pageId = pageId;
        f.pinCount = 1;
        f.isDirty = false;
        f.refBit = true;
        pageTable.put(pageId, freeIdx);
        return f.page;
    }

    /**
     * Decrement the pin count for a page. If {@code dirty} is true, mark the
     * frame dirty so it will be flushed before eviction.
     */
    public void unpin(int pageId, boolean dirty) {
        Integer idx = pageTable.get(pageId);
        if (idx == null) {
            throw new StorageException("unpin: page " + pageId + " not in buffer pool");
        }
        Frame f = frames[idx];
        if (f.pinCount <= 0) {
            throw new StorageException("unpin: page " + pageId + " pin count is already 0");
        }
        f.pinCount--;
        if (dirty) {
            f.isDirty = true;
        }
    }

    /** Mark a page dirty — it has been modified and must be flushed before eviction. */
    public void markDirty(int pageId) {
        Integer idx = pageTable.get(pageId);
        if (idx == null) {
            throw new StorageException("markDirty: page " + pageId + " not in buffer pool");
        }
        frames[idx].isDirty = true;
    }

    /**
     * Allocate a new page via the disk manager, bring it into the pool pinned,
     * and return it. The caller must call {@link #unpin} when done.
     */
    public Page newPage() {
        int newPageId = diskManager.allocatePage();
        int freeIdx = findFreeFrame();
        if (freeIdx == -1) {
            throw new StorageException(
                    "buffer pool full: no free frame for new page (pool size " + frames.length + ")");
        }
        Frame f = frames[freeIdx];
        f.page = diskManager.readPage(newPageId);
        f.pageId = newPageId;
        f.pinCount = 1;
        f.isDirty = false;
        f.refBit = true;
        pageTable.put(newPageId, freeIdx);
        return f.page;
    }

    /** Scan for a frame with pageId == -1 (empty). Returns -1 if none found. */
    private int findFreeFrame() {
        for (int i = 0; i < frames.length; i++) {
            if (frames[i].pageId == -1) return i;
        }
        return -1;
    }
}
