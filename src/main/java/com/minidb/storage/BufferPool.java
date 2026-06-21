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
}
