package com.versiondb.shared;

import java.util.Objects;

/**
 * An immutable typed value: a concrete value of some {@link ColumnType}, or the
 * SQL {@code NULL} of that type. The underlying representation is a {@code Long}
 * (INT), {@code Double} (FLOAT), {@code String} (VARCHAR), {@code Boolean}
 * (BOOL), or {@code null} for SQL NULL.
 */
public final class Value {

    private final ColumnType type;
    private final Object raw;

    private Value(ColumnType type, Object raw) {
        this.type = Objects.requireNonNull(type, "type");
        this.raw = raw;
    }

    public static Value ofInt(long v) {
        return new Value(ColumnType.INT, v);
    }

    public static Value ofFloat(double v) {
        return new Value(ColumnType.FLOAT, v);
    }

    public static Value ofString(String v) {
        return new Value(ColumnType.VARCHAR, Objects.requireNonNull(v, "string value"));
    }

    public static Value ofBool(boolean v) {
        return new Value(ColumnType.BOOL, v);
    }

    /** The SQL NULL of the given type. */
    public static Value nullValue(ColumnType type) {
        return new Value(type, null);
    }

    public ColumnType type() {
        return type;
    }

    public boolean isNull() {
        return raw == null;
    }

    public long asInt() {
        require(ColumnType.INT);
        return (Long) raw;
    }

    public double asFloat() {
        require(ColumnType.FLOAT);
        return (Double) raw;
    }

    public String asString() {
        require(ColumnType.VARCHAR);
        return (String) raw;
    }

    public boolean asBool() {
        require(ColumnType.BOOL);
        return (Boolean) raw;
    }

    private void require(ColumnType expected) {
        if (type != expected) {
            throw new IllegalStateException("value is " + type + ", not " + expected);
        }
        if (raw == null) {
            throw new IllegalStateException("value is NULL");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Value other)) {
            return false;
        }
        return type == other.type && Objects.equals(raw, other.raw);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, raw);
    }

    @Override
    public String toString() {
        return isNull() ? "NULL" : String.valueOf(raw);
    }
}
