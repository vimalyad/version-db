package com.versiondb.query.ast;

import com.versiondb.shared.Value;

/**
 * A constant value in a query. A non-null {@link Value} carries the concrete
 * integer/float/string/boolean constant; a {@code null} {@code value} field
 * represents the untyped SQL {@code NULL} literal (its type is resolved later by
 * the execution engine, since {@code NULL} has no type on its own).
 */
public record Literal(Value value) implements Expression {

    /** The untyped SQL {@code NULL} literal. */
    public static final Literal NULL = new Literal(null);

    public static Literal ofInt(long v) {
        return new Literal(Value.ofInt(v));
    }

    public static Literal ofFloat(double v) {
        return new Literal(Value.ofFloat(v));
    }

    public static Literal ofString(String v) {
        return new Literal(Value.ofString(v));
    }

    public static Literal ofBool(boolean v) {
        return new Literal(Value.ofBool(v));
    }

    /** Whether this is the SQL {@code NULL} literal. */
    public boolean isNull() {
        return value == null;
    }
}
