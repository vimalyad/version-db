package com.versiondb.shared;

import java.util.Objects;

/**
 * Definition of a single column in a CREATE TABLE statement: its name, type,
 * and whether it accepts NULL.
 */
public record ColumnDef(String name, ColumnType type, boolean nullable) {

    public ColumnDef {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
    }
}
