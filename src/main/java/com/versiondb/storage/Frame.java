package com.versiondb.storage;

/**
 * One slot in the buffer pool's frame array. Holds a single in-memory page and
 * all the bookkeeping the pool needs to manage it: which page is loaded, how
 * many callers are using it, whether it has been modified, and the Clock
 * eviction second-chance bit.
 *
 * <p>All fields are accessed only while the buffer pool's lock is held (or
 * during the brief I/O window where the lock is deliberately released — in that
 * window only pinCount and pageId are read by other threads to decide whether
 * the frame can be evicted, and their values are set conservatively before the
 * lock is dropped).
 */
final class Frame {

    /** Which page is in this frame. -1 when the frame is empty. */
    int pageId = -1;

    /** The in-memory page object. Null when the frame is empty or being loaded. */
    Page page = null;

    /**
     * Number of active users of this frame. A page with pinCount &gt; 0 cannot
     * be evicted. Every {@code fetchPage} increments it; every {@code unpin}
     * decrements it.
     */
    int pinCount = 0;

    /** True if the page has been modified since it was last written to disk. */
    boolean isDirty = false;

    /**
     * Clock second-chance bit. Set to true on every access. The Clock hand
     * clears it before eviction; if the bit was already clear the frame is
     * evicted.
     */
    boolean refBit = false;
}
