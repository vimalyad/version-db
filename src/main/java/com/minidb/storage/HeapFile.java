package com.minidb.storage;

import com.minidb.shared.Constants;

import java.util.ArrayList;
import java.util.List;

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

    private final BufferPool bufferPool;
    private final int firstPageId;

    /** Page ids in chain order, rebuilt on open and extended as pages are added. */
    private final List<Integer> pageIds = new ArrayList<>();

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

    /** Walk the chain from {@code firstPageId}, (re)populating the page list. */
    private void rebuild() {
        pageIds.clear();
        int current = firstPageId;
        while (current != Constants.INVALID_PAGE_ID) {
            Page page = bufferPool.fetchPage(current);
            int next;
            try {
                pageIds.add(current);
                next = page.getNextPageId();
            } finally {
                bufferPool.unpin(current, false);
            }
            lastPageId = current;
            current = next;
        }
    }
}
