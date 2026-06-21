package com.versiondb.storage;

import com.versiondb.shared.Constants;
import com.versiondb.shared.StorageException;

import java.nio.ByteBuffer;
import java.util.Arrays;

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

    // ---- Tuple / slot operations ----------------------------------------------

    /**
     * Bytes available for the next insert: the gap between the slot array and the
     * tuple data, minus the slot entry a new tuple would itself require.
     */
    public int getFreeSpace() {
        return getFreeSpaceEnd() - getFreeSpaceStart() - SLOT_SIZE;
    }

    /**
     * Append a tuple to this page. The tuple bytes are written growing backward
     * from the end of the free space and a new slot entry is appended growing
     * forward. Existing tuples never move.
     *
     * @return the slot id of the new tuple (an index into the slot array)
     * @throws StorageException if the tuple plus its slot do not fit
     */
    public int insertTuple(byte[] tuple) {
        if (tuple.length > getFreeSpace()) {
            throw new StorageException("tuple of " + tuple.length
                    + " bytes does not fit in page " + getPageId()
                    + " (free space " + getFreeSpace() + ")");
        }
        int newDataOffset = getFreeSpaceEnd() - tuple.length;
        System.arraycopy(tuple, 0, data, newDataOffset, tuple.length);

        int slotId = getNumSlots();
        int slotPos = HEADER_SIZE + slotId * SLOT_SIZE;
        buf.putInt(slotPos, newDataOffset);
        buf.putInt(slotPos + 4, tuple.length);

        buf.putInt(OFF_FREE_SPACE_END, newDataOffset);
        buf.putInt(OFF_FREE_SPACE_START, slotPos + SLOT_SIZE);
        buf.putInt(OFF_NUM_SLOTS, slotId + 1);
        return slotId;
    }

    /**
     * Read the tuple at the given slot.
     *
     * @return the tuple bytes, or {@code null} if the slot is a tombstone
     *         (deleted)
     * @throws StorageException if the slot id is out of range
     */
    public byte[] getTuple(int slotId) {
        int slotPos = slotPosition(slotId);
        int offset = buf.getInt(slotPos);
        if (offset == TOMBSTONE_OFFSET) {
            return null;
        }
        int length = buf.getInt(slotPos + 4);
        return Arrays.copyOfRange(data, offset, offset + length);
    }

    /**
     * Mark the slot as a tombstone. The tuple bytes are left physically in place
     * — MVCC version chains may still reach them — only the slot's offset is
     * cleared so the tuple becomes logically invisible. Space is reclaimed later
     * by garbage collection, not here.
     *
     * @throws StorageException if the slot id is out of range
     */
    public void deleteTuple(int slotId) {
        int slotPos = slotPosition(slotId);
        buf.putInt(slotPos, TOMBSTONE_OFFSET);
    }

    private int slotPosition(int slotId) {
        if (slotId < 0 || slotId >= getNumSlots()) {
            throw new StorageException("slot id " + slotId + " out of range on page "
                    + getPageId() + " (numSlots " + getNumSlots() + ")");
        }
        return HEADER_SIZE + slotId * SLOT_SIZE;
    }

    /**
     * Recovery-only: (re)place a tuple at a specific slot id. Used by redo, to
     * recreate an insert at the exact slot recorded in the WAL, and by undo, to
     * restore a deleted tuple — both must preserve the tuple's original RID,
     * which {@link #insertTuple} (which always appends) cannot guarantee.
     *
     * <p>If {@code slotId == numSlots} the slot array grows by one (an append);
     * if {@code slotId < numSlots} the existing slot is repointed at freshly
     * written bytes (its previous bytes are abandoned and reclaimed later by GC).
     * A gap ({@code slotId > numSlots}) is rejected.
     *
     * @throws StorageException if the slot id leaves a gap or the tuple does not fit
     */
    public void putTupleAtSlot(int slotId, byte[] tuple) {
        int numSlots = getNumSlots();
        if (slotId < 0 || slotId > numSlots) {
            throw new StorageException("redo slot id " + slotId + " leaves a gap on page "
                    + getPageId() + " (numSlots " + numSlots + ")");
        }
        boolean appending = (slotId == numSlots);
        int needed = tuple.length + (appending ? SLOT_SIZE : 0);
        int available = getFreeSpaceEnd() - getFreeSpaceStart();
        if (needed > available) {
            throw new StorageException("redo tuple of " + tuple.length
                    + " bytes does not fit on page " + getPageId() + " (available " + available + ")");
        }

        int newDataOffset = getFreeSpaceEnd() - tuple.length;
        System.arraycopy(tuple, 0, data, newDataOffset, tuple.length);

        int slotPos = HEADER_SIZE + slotId * SLOT_SIZE;
        buf.putInt(slotPos, newDataOffset);
        buf.putInt(slotPos + 4, tuple.length);
        buf.putInt(OFF_FREE_SPACE_END, newDataOffset);
        if (appending) {
            buf.putInt(OFF_FREE_SPACE_START, slotPos + SLOT_SIZE);
            buf.putInt(OFF_NUM_SLOTS, numSlots + 1);
        }
    }

    // ---- Raw payload (index pages) --------------------------------------------

    /**
     * Largest payload that {@link #writePayload} accepts: the whole page body
     * after the header, minus the 4-byte length prefix.
     */
    public static final int MAX_PAYLOAD_SIZE = Constants.PAGE_SIZE - HEADER_SIZE - Integer.BYTES;

    /**
     * Overwrite the page body in place with an opaque payload, length-prefixed.
     * Unlike the slotted tuple operations, this rewrites the same bytes every
     * time, so a page used as a single mutable record (e.g. a B+Tree node) does
     * not accumulate abandoned bytes across updates. The page's slot/free-space
     * header fields are not used by payload pages.
     *
     * @throws StorageException if the payload exceeds {@link #MAX_PAYLOAD_SIZE}
     */
    public void writePayload(byte[] payload) {
        if (payload.length > MAX_PAYLOAD_SIZE) {
            throw new StorageException("payload of " + payload.length
                    + " bytes exceeds page capacity " + MAX_PAYLOAD_SIZE
                    + " on page " + getPageId());
        }
        buf.putInt(HEADER_SIZE, payload.length);
        System.arraycopy(payload, 0, data, HEADER_SIZE + Integer.BYTES, payload.length);
    }

    /**
     * Read back the payload written by {@link #writePayload}. A freshly created
     * page (all-zero body) returns an empty array.
     *
     * @throws StorageException if the stored length is invalid (corrupt page)
     */
    public byte[] readPayload() {
        int len = buf.getInt(HEADER_SIZE);
        if (len < 0 || len > MAX_PAYLOAD_SIZE) {
            throw new StorageException("invalid payload length " + len + " on page " + getPageId());
        }
        int start = HEADER_SIZE + Integer.BYTES;
        return Arrays.copyOfRange(data, start, start + len);
    }

    // ---- Serialization --------------------------------------------------------

    /**
     * Produce the on-disk image of this page. The checksum field is recomputed
     * over the page body before returning. Returns the backing array directly
     * (no copy): the buffer pool serializes a page only to immediately hand it to
     * the disk manager for writing.
     */
    public byte[] serialize() {
        buf.putInt(OFF_CHECKSUM, computeBodyChecksum());
        return data;
    }

    /**
     * Parse a page from its on-disk image and verify its checksum. The array must
     * be exactly {@link Constants#PAGE_SIZE} bytes; it is adopted as the page's
     * backing store without copying.
     *
     * @throws StorageException if the stored checksum does not match the body —
     *         the page is corrupt (partial write, hardware fault, etc.)
     */
    public static Page deserialize(byte[] bytes) {
        if (bytes.length != Constants.PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "page image must be " + Constants.PAGE_SIZE + " bytes, got " + bytes.length);
        }
        Page page = new Page(bytes);
        int stored = page.buf.getInt(OFF_CHECKSUM);
        int actual = page.computeBodyChecksum();
        if (stored != actual) {
            throw new StorageException("checksum mismatch on page " + page.getPageId()
                    + ": stored " + stored + ", computed " + actual + " (corrupt page)");
        }
        return page;
    }

    /** CRC32 over the page body — everything after the fixed header. */
    private int computeBodyChecksum() {
        return Crc32Util.compute(data, HEADER_SIZE, Constants.PAGE_SIZE - HEADER_SIZE);
    }
}
