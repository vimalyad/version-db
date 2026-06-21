package com.versiondb.storage;

import com.versiondb.shared.Constants;
import com.versiondb.shared.RID;
import com.versiondb.shared.StorageException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * An unordered collection of tuples for one table, stored as a singly-linked
 * list of pages. Each page's {@code nextPageId} header field points to the next
 * page in the chain; the last page points to {@link Constants#INVALID_PAGE_ID}.
 * The heap file knows the {@code firstPageId} of its chain (persisted by the
 * catalog) and rebuilds its in-memory view of the chain when opened.
 *
 * <p>All page access goes through the {@link BufferPool}; the heap file never
 * touches the disk directly.
 */
public final class HeapFile {

    /** Largest tuple that can ever fit on a page (an empty page's free space). */
    static final int MAX_TUPLE_SIZE = Constants.PAGE_SIZE - Page.HEADER_SIZE - Page.SLOT_SIZE;

    private final BufferPool bufferPool;
    private final int firstPageId;

    /** Page ids in chain order, rebuilt on open and extended as pages are added. */
    private final List<Integer> pageIds = new ArrayList<>();

    /**
     * Free Space Map: pageId &rarr; bytes available for the next insert on that
     * page (as reported by {@link Page#getFreeSpace()}, which already accounts
     * for the slot entry a new tuple needs). Rebuilt on open and kept current as
     * tuples are inserted, so an insert can pick a page in O(pages) without
     * scanning page contents. Note: a delete tombstones a slot but does not
     * reclaim space, so deletes leave free space unchanged.
     */
    private final Map<Integer, Integer> freeSpaceMap = new HashMap<>();

    /** Tail of the chain — where a newly allocated page is appended. */
    private int lastPageId;

    /**
     * Open an existing heap file whose chain starts at {@code firstPageId}.
     * Walks the chain once to build the in-memory page list.
     */
    public HeapFile(BufferPool bufferPool, int firstPageId) {
        this.bufferPool = bufferPool;
        this.firstPageId = firstPageId;
        this.lastPageId = firstPageId;
        rebuild();
    }

    /**
     * Create a brand-new, empty heap file: allocate its first page and return a
     * heap file positioned on it. The caller (eventually the catalog) is
     * responsible for persisting {@link #getFirstPageId()}.
     */
    public static HeapFile create(BufferPool bufferPool) {
        Page page = bufferPool.newPage();
        int pid = page.getPageId();
        // The page is already empty and durable (allocatePage wrote and synced it).
        bufferPool.unpin(pid, false);
        return new HeapFile(bufferPool, pid);
    }

    /** The first page of this heap's chain. Stored in the catalog as table metadata. */
    public int getFirstPageId() {
        return firstPageId;
    }

    /** Number of pages currently in the chain. */
    public int getPageCount() {
        return pageIds.size();
    }

    /**
     * Walk the chain from {@code firstPageId}, (re)populating the page list and
     * the free space map.
     */
    private void rebuild() {
        pageIds.clear();
        freeSpaceMap.clear();
        int current = firstPageId;
        while (current != Constants.INVALID_PAGE_ID) {
            Page page = bufferPool.fetchPage(current);
            int next;
            try {
                pageIds.add(current);
                freeSpaceMap.put(current, page.getFreeSpace());
                next = page.getNextPageId();
            } finally {
                bufferPool.unpin(current, false);
            }
            lastPageId = current;
            current = next;
        }
    }

    /** Free bytes available for the next insert on {@code pageId}, or -1 if unknown. */
    int freeSpaceOf(int pageId) {
        return freeSpaceMap.getOrDefault(pageId, -1);
    }

    // ---- Tuple operations -----------------------------------------------------

    /**
     * Insert a tuple into the heap. Uses the free space map to find a page with
     * room; if none has space, allocates a new page and links it onto the chain.
     *
     * @return the RID where the tuple was stored
     * @throws StorageException if the tuple is larger than a page can ever hold
     */
    public synchronized RID insertTuple(byte[] data) {
        if (data.length > MAX_TUPLE_SIZE) {
            throw new StorageException("tuple of " + data.length
                    + " bytes exceeds maximum tuple size " + MAX_TUPLE_SIZE);
        }
        int targetPageId = findPageWithSpace(data.length);
        if (targetPageId == Constants.INVALID_PAGE_ID) {
            targetPageId = appendNewPage();
        }
        Page page = bufferPool.fetchPage(targetPageId);
        int slotId;
        try {
            slotId = page.insertTuple(data);
            freeSpaceMap.put(targetPageId, page.getFreeSpace());
        } finally {
            bufferPool.unpin(targetPageId, true);
        }
        return new RID(targetPageId, slotId);
    }

    /**
     * Read the tuple at the given RID.
     *
     * @return the tuple bytes, or {@code null} if the slot has been deleted
     */
    public byte[] getTuple(RID rid) {
        Page page = bufferPool.fetchPage(rid.pageId());
        try {
            return page.getTuple(rid.slotId());
        } finally {
            bufferPool.unpin(rid.pageId(), false);
        }
    }

    /**
     * Delete the tuple at the given RID. The slot is tombstoned; the bytes stay
     * physically in place (MVCC version chains may still reach them) and the
     * space is reclaimed later by garbage collection, not here.
     */
    public synchronized void deleteTuple(RID rid) {
        Page page = bufferPool.fetchPage(rid.pageId());
        try {
            page.deleteTuple(rid.slotId());
        } finally {
            bufferPool.unpin(rid.pageId(), true);
        }
    }

    /** First page in the chain with at least {@code len} free bytes, or INVALID_PAGE_ID. */
    private int findPageWithSpace(int len) {
        for (int pid : pageIds) {
            Integer free = freeSpaceMap.get(pid);
            if (free != null && free >= len) {
                return pid;
            }
        }
        return Constants.INVALID_PAGE_ID;
    }

    // ---- Scan -----------------------------------------------------------------

    /** One live tuple produced by a {@link #scan()}: its RID and its bytes. */
    public record Entry(RID rid, byte[] data) {
    }

    /**
     * Iterate over every live tuple in the heap. The scan walks the page chain
     * by following each page's {@code nextPageId} and yields one {@link Entry}
     * per non-tombstoned slot. Pages are loaded one at a time and unpinned after
     * being read, so the scan holds at most one page pinned and buffers only the
     * current page's tuples.
     */
    public Iterator<Entry> scan() {
        return new ScanIterator();
    }

    private final class ScanIterator implements Iterator<Entry> {

        private int currentPageId = firstPageId;
        private final Deque<Entry> buffer = new ArrayDeque<>();

        @Override
        public boolean hasNext() {
            fillBuffer();
            return !buffer.isEmpty();
        }

        @Override
        public Entry next() {
            fillBuffer();
            if (buffer.isEmpty()) {
                throw new NoSuchElementException();
            }
            return buffer.poll();
        }

        /** Advance through pages until the buffer holds tuples or the chain ends. */
        private void fillBuffer() {
            while (buffer.isEmpty() && currentPageId != Constants.INVALID_PAGE_ID) {
                Page page = bufferPool.fetchPage(currentPageId);
                int next;
                try {
                    int numSlots = page.getNumSlots();
                    for (int slot = 0; slot < numSlots; slot++) {
                        byte[] data = page.getTuple(slot);
                        if (data != null) {
                            buffer.add(new Entry(new RID(currentPageId, slot), data));
                        }
                    }
                    next = page.getNextPageId();
                } finally {
                    bufferPool.unpin(currentPageId, false);
                }
                currentPageId = next;
            }
        }
    }

    /** Allocate a new page, link it onto the tail of the chain, and register it. */
    private int appendNewPage() {
        Page newPage = bufferPool.newPage();
        int newPid = newPage.getPageId();
        int free = newPage.getFreeSpace();
        bufferPool.unpin(newPid, false);

        Page tail = bufferPool.fetchPage(lastPageId);
        try {
            tail.setNextPageId(newPid);
        } finally {
            bufferPool.unpin(lastPageId, true);
        }

        pageIds.add(newPid);
        freeSpaceMap.put(newPid, free);
        lastPageId = newPid;
        return newPid;
    }
}
