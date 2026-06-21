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

    /** Clock hand: next candidate frame for eviction. */
    private int clockHand = 0;

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
     * pool, increment its pin count and return the cached page. If not, evict
     * a victim frame via the Clock algorithm, load the page from disk, and
     * return it pinned. The caller must call {@link #unpin} when done.
     */
    public Page fetchPage(int pageId) {
        Integer idx = pageTable.get(pageId);
        if (idx != null) {
            Frame f = frames[idx];
            f.pinCount++;
            f.refBit = true;
            return f.page;
        }

        // Cache miss — evict a victim frame via Clock
        int victimIdx = evict();
        Frame f = frames[victimIdx];
        f.page = diskManager.readPage(pageId);
        f.pageId = pageId;
        f.pinCount = 1;
        f.isDirty = false;
        f.refBit = true;
        pageTable.put(pageId, victimIdx);
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
        int victimIdx = evict();
        Frame f = frames[victimIdx];
        f.page = diskManager.readPage(newPageId);
        f.pageId = newPageId;
        f.pinCount = 1;
        f.isDirty = false;
        f.refBit = true;
        pageTable.put(newPageId, victimIdx);
        return f.page;
    }

    /**
     * Clock eviction: sweep the frame array starting at {@code clockHand},
     * giving each unpinned frame with {@code refBit=true} a second chance
     * (clear the bit and continue). Evict the first unpinned frame with
     * {@code refBit=false}. Flush a dirty victim before evicting (WAL rule
     * applied in 2.4; here we write directly).
     *
     * @return the index of the evicted (now empty) frame
     * @throws StorageException if every frame is pinned
     */
    private int evict() {
        int capacity = frames.length;
        for (int i = 0; i < 2 * capacity; i++) {
            int idx = (clockHand + i) % capacity;
            Frame f = frames[idx];

            if (f.pinCount > 0) {
                continue; // cannot evict pinned frames
            }
            if (f.refBit) {
                f.refBit = false; // second chance
                continue;
            }

            // Victim found — evict it
            clockHand = (idx + 1) % capacity;

            if (f.isDirty && f.pageId != -1) {
                // Flush dirty victim (WAL hook wired in 2.4; write directly for now)
                diskManager.writePage(f.pageId, f.page);
            }
            if (f.pageId != -1) {
                pageTable.remove(f.pageId);
            }

            // Reset to empty
            f.pageId = -1;
            f.page = null;
            f.pinCount = 0;
            f.isDirty = false;
            f.refBit = false;
            return idx;
        }
        throw new StorageException(
                "buffer pool exhausted: all " + capacity + " frames are pinned");
    }
}
