package com.minidb.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minidb.shared.Constants;

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
}
