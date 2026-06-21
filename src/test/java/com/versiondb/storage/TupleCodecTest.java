package com.versiondb.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.versiondb.shared.ColumnType;
import com.versiondb.shared.SerializationException;
import com.versiondb.shared.Value;
import java.util.List;
import org.junit.jupiter.api.Test;

class TupleCodecTest {

    private static final List<ColumnType> SCHEMA =
            List.of(ColumnType.INT, ColumnType.VARCHAR, ColumnType.BOOL, ColumnType.FLOAT);

    @Test
    void roundTripAllTypes() {
        List<Value> row = List.of(
                Value.ofInt(-9000000000L),
                Value.ofString("héllo"),
                Value.ofBool(true),
                Value.ofFloat(2.5));

        List<Value> decoded = TupleCodec.decode(TupleCodec.encode(row, SCHEMA), SCHEMA);
        assertEquals(row, decoded);
    }

    @Test
    void roundTripWithNulls() {
        List<Value> row = List.of(
                Value.nullValue(ColumnType.INT),
                Value.ofString("x"),
                Value.nullValue(ColumnType.BOOL),
                Value.nullValue(ColumnType.FLOAT));

        List<Value> decoded = TupleCodec.decode(TupleCodec.encode(row, SCHEMA), SCHEMA);
        assertEquals(row, decoded);
        assertTrue(decoded.get(0).isNull());
        assertTrue(decoded.get(2).isNull());
        assertEquals("x", decoded.get(1).asString());
    }

    @Test
    void emptyStringRoundTrips() {
        List<ColumnType> schema = List.of(ColumnType.VARCHAR);
        List<Value> row = List.of(Value.ofString(""));
        assertEquals(row, TupleCodec.decode(TupleCodec.encode(row, schema), schema));
    }

    @Test
    void manyColumnsCrossBitmapByteBoundary() {
        // 10 columns forces a 2-byte null bitmap; null out a few across the boundary.
        List<ColumnType> schema = java.util.Collections.nCopies(10, ColumnType.INT);
        java.util.List<Value> row = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            row.add(i == 3 || i == 8 ? Value.nullValue(ColumnType.INT) : Value.ofInt(i));
        }
        assertEquals(row, TupleCodec.decode(TupleCodec.encode(row, schema), schema));
    }

    @Test
    void mismatchedValueCountThrows() {
        assertThrows(SerializationException.class,
                () -> TupleCodec.encode(List.of(Value.ofInt(1)), SCHEMA));
    }

    @Test
    void wrongValueTypeThrows() {
        List<ColumnType> schema = List.of(ColumnType.INT);
        assertThrows(SerializationException.class,
                () -> TupleCodec.encode(List.of(Value.ofString("nope")), schema));
    }
}
