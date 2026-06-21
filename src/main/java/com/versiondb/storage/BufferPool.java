package com.versiondb.storage;

import com.versiondb.shared.StorageException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory cache of database pages. Every read and write of a page by any
 * upper layer goes through the buffer pool; only the buffer pool calls
 * {@link DiskManager} directly.
 *
 * <p>The pool holds a fixed number of {@link Frame frames}. When all frames
 * are occupied, Clock eviction selects a victim. A dirty victim is flushed to
 * disk first, respecting the WAL rule: the injected {@link WalFlushCallback}
 * is called before the write to guarantee WAL records are durable first.
 *
 * <p><b>Thread safety:</b> a single {@link ReentrantLock} guards all
 * frame-array and page-table mutations. Disk I/O (reads and writes) is always
 * performed with the lock released, so concurrent callers do not serialize on
 * I/O. During I/O, the frame being loaded or flushed is marked with
 * {@code pinCount = 1} before the lock is released so the Clock eviction
 * algorithm never selects it as a victim.
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

    /** Guards all frame-array and page-table mutations. Released around I/O. */
    private final ReentrantLock lock = new ReentrantLock();

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
        lock.lock();
        try {
            Integer idx = pageTable.get(pageId);
            if (idx != null) {
                Frame f = frames[idx];
                f.pinCount++;
                f.refBit = true;
                return f.page;
            }

            // Cache miss — evict a victim frame and claim it with pinCount=1
            int victimIdx = evict();
            Frame f = frames[victimIdx];
            f.pageId = pageId;
            f.pinCount = 1;  // prevents re-eviction during the I/O window below
            f.isDirty = false;
            f.refBit = true;
            f.page = null;

            // Release lock for disk read — frame is safe (pinCount=1)
            lock.unlock();
            try {
                f.page = diskManager.readPage(pageId);
            } finally {
                lock.lock();
            }

            pageTable.put(pageId, victimIdx);
            return f.page;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Decrement the pin count for a page. If {@code dirty} is true, mark the
     * frame dirty so it will be flushed before eviction.
     */
    public void unpin(int pageId, boolean dirty) {
        lock.lock();
        try {
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
        } finally {
            lock.unlock();
        }
    }

    /** Mark a page dirty — it has been modified and must be flushed before eviction. */
    public void markDirty(int pageId) {
        lock.lock();
        try {
            Integer idx = pageTable.get(pageId);
            if (idx == null) {
                throw new StorageException("markDirty: page " + pageId + " not in buffer pool");
            }
            frames[idx].isDirty = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Allocate a new page via the disk manager, bring it into the pool pinned,
     * and return it. The caller must call {@link #unpin} when done.
     */
    public Page newPage() {
        // allocatePage involves disk I/O — do it outside the pool lock
        int newPageId = diskManager.allocatePage();

        lock.lock();
        try {
            int victimIdx = evict();
            Frame f = frames[victimIdx];
            f.pageId = newPageId;
            f.pinCount = 1;
            f.isDirty = false;
            f.refBit = true;
            f.page = null;

            lock.unlock();
            try {
                f.page = diskManager.readPage(newPageId);
            } finally {
                lock.lock();
            }

            pageTable.put(newPageId, victimIdx);
            return f.page;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Flush a dirty page to disk, honouring the WAL rule: call
     * {@code walFlushCallback.flushToLsn(page.lsn)} before writing the page so
     * that all WAL records describing changes to this page are durable first.
     * No-op if the page is not in the pool or is not dirty.
     */
    public void flushPage(int pageId) {
        lock.lock();
        try {
            Integer idx = pageTable.get(pageId);
            if (idx == null || !frames[idx].isDirty) {
                return;
            }
            writeDirtyFrame(frames[idx]);
        } finally {
            lock.unlock();
        }
    }

    /** Flush every dirty frame in the pool. Used by checkpointing and shutdown. */
    public void flushAll() {
        lock.lock();
        try {
            for (Frame f : frames) {
                if (f.pageId != -1 && f.isDirty) {
                    writeDirtyFrame(f);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Write a dirty frame to disk under the WAL rule, then mark it clean.
     * Must be called while holding {@code lock}. Releases and reacquires the
     * lock around the actual I/O so other threads are not blocked waiting.
     */
    private void writeDirtyFrame(Frame f) {
        long lsn = f.page.getLsn();
        int fPageId = f.pageId;
        Page fPage = f.page;

        lock.unlock();
        try {
            walFlushCallback.flushToLsn(lsn);
            diskManager.writePage(fPageId, fPage);
        } finally {
            lock.lock();
        }
        f.isDirty = false;
    }

    /**
     * Clock eviction: sweep the frame array starting at {@code clockHand},
     * giving each unpinned frame with {@code refBit=true} a second chance
     * (clear the bit and continue). Evict the first unpinned frame with
     * {@code refBit=false}. A dirty victim is flushed (WAL rule) before the
     * frame is cleared and returned.
     *
     * <p>Must be called while holding {@code lock}. May release and reacquire
     * it internally when flushing a dirty victim.
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

            // Victim found — claim it before any lock release
            clockHand = (idx + 1) % capacity;
            int oldPageId = f.pageId;

            if (f.isDirty && oldPageId != -1) {
                // Temporarily pin to prevent re-eviction during dirty flush I/O
                f.pinCount = 1;
                pageTable.remove(oldPageId);
                writeDirtyFrame(f); // releases and reacquires lock
                f.pinCount = 0;
            } else if (oldPageId != -1) {
                pageTable.remove(oldPageId);
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
