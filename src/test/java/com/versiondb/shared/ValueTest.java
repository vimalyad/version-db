package com.versiondb.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ValueTest {

    @Test
    void roundTripEachType() {
        assertEquals(42L, Value.ofInt(42).asInt());
        assertEquals(3.5, Value.ofFloat(3.5).asFloat());
        assertEquals("hi", Value.ofString("hi").asString());
        assertTrue(Value.ofBool(true).asBool());
    }

    @Test
    void typeIsReported() {
        assertEquals(ColumnType.INT, Value.ofInt(1).type());
        assertEquals(ColumnType.VARCHAR, Value.nullValue(ColumnType.VARCHAR).type());
    }

    @Test
    void nullHandling() {
        Value n = Value.nullValue(ColumnType.INT);
        assertTrue(n.isNull());
        assertFalse(Value.ofInt(1).isNull());
        assertEquals("NULL", n.toString());
    }

    @Test
    void wrongTypeAccessThrows() {
        assertThrows(IllegalStateException.class, () -> Value.ofInt(1).asString());
        assertThrows(IllegalStateException.class, () -> Value.nullValue(ColumnType.INT).asInt());
    }

    @Test
    void equalityByTypeAndValue() {
        assertEquals(Value.ofInt(5), Value.ofInt(5));
        assertEquals(Value.ofInt(5).hashCode(), Value.ofInt(5).hashCode());
        assertNotEquals(Value.ofInt(5), Value.ofInt(6));
        assertNotEquals(Value.nullValue(ColumnType.INT), Value.nullValue(ColumnType.FLOAT));
        assertEquals(Value.nullValue(ColumnType.INT), Value.nullValue(ColumnType.INT));
    }
}
