package com.versiondb.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.versiondb.shared.Constants;
import com.versiondb.shared.StorageException;

import org.junit.jupiter.api.Test;

class PageTest {

    @Test
    void freshPageHasExpectedHeaderDefaults() {
        Page page = Page.create(7);
        assertEquals(7, page.getPageId());
        assertEquals(0L, page.getLsn());
        assertEquals(0, page.getNumSlots());
        assertEquals(Constants.INVALID_PAGE_ID, page.getNextPageId());
    }

    @Test
    void serializedImageIsExactlyPageSize() {
        Page page = Page.create(1);
        assertEquals(Constants.PAGE_SIZE, page.serialize().length);
    }

    @Test
    void headerSurvivesSerializeDeserializeRoundTrip() {
        Page page = Page.create(42);
        page.setLsn(99L);
        page.setNextPageId(43);

        Page restored = Page.deserialize(page.serialize());

        assertEquals(42, restored.getPageId());
        assertEquals(99L, restored.getLsn());
        assertEquals(43, restored.getNextPageId());
        assertEquals(0, restored.getNumSlots());
    }

    @Test
    void deserializeRejectsWrongSizedImage() {
        assertThrows(IllegalArgumentException.class, () -> Page.deserialize(new byte[10]));
    }

    @Test
    void serializeDeserializeIsByteForByteStable() {
        Page page = Page.create(5);
        page.setLsn(123L);
        byte[] image = page.serialize().clone();

        Page restored = Page.deserialize(image);
        assertArrayEquals(image, restored.serialize());
    }

    @Test
    void insertThenGetReturnsSameBytes() {
        Page page = Page.create(1);
        byte[] tuple = {1, 2, 3, 4, 5};
        int slot = page.insertTuple(tuple);

        assertEquals(0, slot);
        assertEquals(1, page.getNumSlots());
        assertArrayEquals(tuple, page.getTuple(slot));
    }

    @Test
    void multipleInsertsGetDistinctSlotsAndKeepTheirData() {
        Page page = Page.create(1);
        byte[] a = {10, 11};
        byte[] b = {20, 21, 22};
        byte[] c = {30};

        int sa = page.insertTuple(a);
        int sb = page.insertTuple(b);
        int sc = page.insertTuple(c);

        assertEquals(0, sa);
        assertEquals(1, sb);
        assertEquals(2, sc);
        assertArrayEquals(a, page.getTuple(sa));
        assertArrayEquals(b, page.getTuple(sb));
        assertArrayEquals(c, page.getTuple(sc));
    }

    @Test
    void deletedSlotReadsBackAsNullButKeepsSlotCount() {
        Page page = Page.create(1);
        int slot = page.insertTuple(new byte[] {7, 7, 7});
        page.deleteTuple(slot);

        assertNull(page.getTuple(slot));
        assertEquals(1, page.getNumSlots());
    }

    @Test
    void freeSpaceShrinksByTuplePlusSlotOnInsert() {
        Page page = Page.create(1);
        int before = page.getFreeSpace();
        byte[] tuple = {1, 2, 3, 4};
        page.insertTuple(tuple);

        assertEquals(before - tuple.length - Page.SLOT_SIZE, page.getFreeSpace());
    }

    @Test
    void insertedTuplesSurviveSerializeRoundTrip() {
        Page page = Page.create(1);
        byte[] a = {1, 2, 3};
        byte[] b = {9, 8};
        page.insertTuple(a);
        int sb = page.insertTuple(b);
        page.deleteTuple(0);

        Page restored = Page.deserialize(page.serialize().clone());
        assertNull(restored.getTuple(0));
        assertArrayEquals(b, restored.getTuple(sb));
    }

    @Test
    void insertThatDoesNotFitThrows() {
        Page page = Page.create(1);
        byte[] tooBig = new byte[Constants.PAGE_SIZE];
        assertThrows(StorageException.class, () -> page.insertTuple(tooBig));
    }

    @Test
    void outOfRangeSlotThrows() {
        Page page = Page.create(1);
        assertThrows(StorageException.class, () -> page.getTuple(0));
        page.insertTuple(new byte[] {1});
        assertThrows(StorageException.class, () -> page.getTuple(5));
    }

    @Test
    void corruptedBodyIsDetectedOnDeserialize() {
        Page page = Page.create(3);
        page.insertTuple(new byte[] {1, 2, 3, 4});
        byte[] image = page.serialize().clone();

        // Flip a byte in the body (past the header) to simulate corruption.
        image[Constants.PAGE_SIZE - 1] ^= 0xFF;
        assertThrows(StorageException.class, () -> Page.deserialize(image));
    }

    @Test
    void cleanPagePassesChecksumVerification() {
        Page page = Page.create(4);
        page.insertTuple(new byte[] {5, 6, 7});
        byte[] image = page.serialize().clone();
        // Round-trips without throwing.
        Page restored = Page.deserialize(image);
        assertArrayEquals(new byte[] {5, 6, 7}, restored.getTuple(0));
    }

    @Test
    void pageCanBeFilledUntilFull() {
        Page page = Page.create(1);
        byte[] tuple = new byte[100];
        int count = 0;
        while (page.getFreeSpace() >= tuple.length) {
            page.insertTuple(tuple);
            count++;
        }
        assertTrue(count > 0);
        // Next insert must fail now that free space is exhausted.
        assertThrows(StorageException.class, () -> page.insertTuple(tuple));
    }
}
