package com.versiondb.query.exec;

import com.versiondb.shared.Value;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TupleTest {

    private static Tuple row(String table, String c1, Value v1, String c2, Value v2) {
        return new Tuple(List.of(table, table), List.of(c1, c2), Arrays.asList(v1, v2));
    }

    @Test
    void unqualifiedLookupReturnsFirstMatch() {
        Tuple t = row("users", "id", Value.ofInt(1), "age", Value.ofInt(30));
        assertEquals(Value.ofInt(1), t.get(null, "id"));
        assertEquals(Value.ofInt(30), t.get(null, "age"));
    }

    @Test
    void qualifiedLookupPrefersMatchingTable() {
        Tuple users = row("users", "id", Value.ofInt(1), "age", Value.ofInt(30));
        Tuple orders = row("orders", "id", Value.ofInt(9), "amount", Value.ofInt(50));
        Tuple joined = Tuple.merge(users, orders);
        assertEquals(Value.ofInt(1), joined.get("users", "id"));
        assertEquals(Value.ofInt(9), joined.get("orders", "id"));
    }

    @Test
    void qualifiedLookupFallsBackToNameWhenTableUnmatched() {
        Tuple t = row("users", "id", Value.ofInt(1), "age", Value.ofInt(30));
        // Alias "u" not carried in the tuple — falls back to name-only match.
        assertEquals(Value.ofInt(30), t.get("u", "age"));
    }

    @Test
    void unknownColumnThrows() {
        Tuple t = row("users", "id", Value.ofInt(1), "age", Value.ofInt(30));
        assertThrows(IllegalArgumentException.class, () -> t.get(null, "missing"));
    }

    @Test
    void mergeConcatenatesColumns() {
        Tuple a = row("a", "x", Value.ofInt(1), "y", Value.ofInt(2));
        Tuple b = row("b", "z", Value.ofInt(3), "w", Value.ofInt(4));
        Tuple m = Tuple.merge(a, b);
        assertEquals(4, m.size());
        assertEquals(List.of("x", "y", "z", "w"), m.columnNames());
        assertEquals(Value.ofInt(3), m.get("b", "z"));
    }

    @Test
    void nullValuesArePreserved() {
        Tuple t = new Tuple(List.of("t"), List.of("c"), Arrays.asList((Value) null));
        assertNull(t.get(null, "c"));
        assertEquals(1, t.size());
    }
}
