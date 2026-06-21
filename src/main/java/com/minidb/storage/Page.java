package com.minidb.storage;

import com.minidb.shared.Constants;

import java.nio.ByteBuffer;

/**
 * A single fixed-size ({@link Constants#PAGE_SIZE}) page: the atomic unit of
 * disk I/O. A page is a slotted page — a fixed header followed by a slot array
 * that grows forward from the header and tuple data that grows backward from the
 * end of the page, with one contiguous block of free space in between.
 *
 * <pre>
 *  +-----------------------------------------------------------+
 *  | HEADER (fixed size)                                       |
 *  +----------------------+------------------------------------+
 *  | slot[0] slot[1] ...  |  free space  | ... tuple[1] tuple[0]|
 *  | grows -->            |              |          <-- grows  |
 *  +----------------------+------------------------------------+
 * </pre>
 *
 * The page keeps its entire on-disk image in a single backing byte array and
 * reads/writes header and slot fields directly into it, so {@link #serialize()}
 * is essentially free and there is no risk of cached fields drifting out of sync
 * with the bytes.
 */
public final class Page {

    // ---- Header layout (byte offsets within the page) -------------------------
    private static final int OFF_PAGE_ID = 0;          // int
    private static final int OFF_LSN = 4;              // long
    private static final int OFF_FREE_SPACE_START = 12; // int
    private static final int OFF_FREE_SPACE_END = 16;   // int
    private static final int OFF_NUM_SLOTS = 20;        // int
    private static final int OFF_NEXT_PAGE_ID = 24;     // int
    private static final int OFF_CHECKSUM = 28;         // int (CRC32, lower 32 bits)

    /** Size of the fixed page header in bytes. The slot array begins here. */
    static final int HEADER_SIZE = 32;

    /** Size of one slot entry: a 4-byte offset followed by a 4-byte length. */
    static final int SLOT_SIZE = 8;

    /** A slot whose offset is this value is a tombstone (deleted tuple). */
    static final int TOMBSTONE_OFFSET = 0;

    private final byte[] data;
    private final ByteBuffer buf;

    private Page(byte[] data) {
        this.data = data;
        this.buf = ByteBuffer.wrap(data);
    }

    /**
     * Create a fresh, empty page with the given id. The slot array is empty and
     * all of the body is free space; {@code nextPageId} is set to "no page".
     */
    public static Page create(int pageId) {
        Page page = new Page(new byte[Constants.PAGE_SIZE]);
        page.buf.putInt(OFF_PAGE_ID, pageId);
        page.buf.putLong(OFF_LSN, 0L);
        page.buf.putInt(OFF_FREE_SPACE_START, HEADER_SIZE);
        page.buf.putInt(OFF_FREE_SPACE_END, Constants.PAGE_SIZE);
        page.buf.putInt(OFF_NUM_SLOTS, 0);
        page.buf.putInt(OFF_NEXT_PAGE_ID, Constants.INVALID_PAGE_ID);
        page.buf.putInt(OFF_CHECKSUM, 0);
        return page;
    }

    // ---- Header accessors -----------------------------------------------------

    public int getPageId() {
        return buf.getInt(OFF_PAGE_ID);
    }

    public long getLsn() {
        return buf.getLong(OFF_LSN);
    }

    /**
     * Set the LSN of the most recent WAL record that modified this page. Called
     * every time the page is changed; the buffer pool uses it to enforce the WAL
     * rule before flushing.
     */
    public void setLsn(long lsn) {
        buf.putLong(OFF_LSN, lsn);
    }

    public int getNumSlots() {
        return buf.getInt(OFF_NUM_SLOTS);
    }

    public int getNextPageId() {
        return buf.getInt(OFF_NEXT_PAGE_ID);
    }

    public void setNextPageId(int nextPageId) {
        buf.putInt(OFF_NEXT_PAGE_ID, nextPageId);
    }

    private int getFreeSpaceStart() {
        return buf.getInt(OFF_FREE_SPACE_START);
    }

    private int getFreeSpaceEnd() {
        return buf.getInt(OFF_FREE_SPACE_END);
    }

    // ---- Serialization --------------------------------------------------------

    /**
     * Produce the on-disk image of this page. Returns the backing array directly
     * (no copy): the buffer pool serializes a page only to immediately hand it to
     * the disk manager for writing.
     */
    public byte[] serialize() {
        return data;
    }

    /**
     * Parse a page from its on-disk image. The array must be exactly
     * {@link Constants#PAGE_SIZE} bytes; it is adopted as the page's backing
     * store without copying.
     */
    public static Page deserialize(byte[] bytes) {
        if (bytes.length != Constants.PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "page image must be " + Constants.PAGE_SIZE + " bytes, got " + bytes.length);
        }
        return new Page(bytes);
    }
}
