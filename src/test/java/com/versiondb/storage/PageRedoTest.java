package com.versiondb.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.versiondb.shared.StorageException;
import org.junit.jupiter.api.Test;

/** Tests for {@link Page#putTupleAtSlot}, the recovery-only slot-specific write. */
class PageRedoTest {

    @Test
    void putAtNextSlotAppends() {
        Page page = Page.create(1);
        page.putTupleAtSlot(0, new byte[]{1, 2, 3});
        page.putTupleAtSlot(1, new byte[]{4, 5});

        assertEquals(2, page.getNumSlots());
        assertArrayEquals(new byte[]{1, 2, 3}, page.getTuple(0));
        assertArrayEquals(new byte[]{4, 5}, page.getTuple(1));
    }

    @Test
    void putAtExistingSlotOverwrites() {
        Page page = Page.create(1);
        int slot = page.insertTuple(new byte[]{9, 9});
        page.deleteTuple(slot); // tombstone it

        // Recovery restores a (possibly different) tuple at the same slot.
        page.putTupleAtSlot(slot, new byte[]{7, 7, 7});

        assertEquals(1, page.getNumSlots(), "overwrite must not grow the slot array");
        assertArrayEquals(new byte[]{7, 7, 7}, page.getTuple(slot));
    }

    @Test
    void putLeavingAGapThrows() {
        Page page = Page.create(1);
        assertThrows(StorageException.class, () -> page.putTupleAtSlot(2, new byte[]{1}));
    }

    @Test
    void putTooLargeThrows() {
        Page page = Page.create(1);
        assertThrows(StorageException.class,
                () -> page.putTupleAtSlot(0, new byte[com.versiondb.shared.Constants.PAGE_SIZE]));
    }
}
