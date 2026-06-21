package com.versiondb.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RidTest {

    @Test
    void equalityAndHashCodeByValue() {
        RID a = new RID(5, 12);
        RID b = new RID(5, 12);
        RID c = new RID(5, 13);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void accessorsExposeComponents() {
        RID rid = new RID(7, 3);
        assertEquals(7, rid.pageId());
        assertEquals(3, rid.slotId());
    }

    @Test
    void toStringIsReadable() {
        assertEquals("RID(7,3)", new RID(7, 3).toString());
    }

    @Test
    void invalidRidIsNotValid() {
        assertFalse(RID.INVALID.isValid());
        assertTrue(new RID(0, 0).isValid());
    }
}
