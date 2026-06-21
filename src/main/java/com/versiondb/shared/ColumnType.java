package com.versiondb.shared;

/** The column data types VersionDB supports. */
public enum ColumnType {
    /** 64-bit signed integer (stored as a Java {@code long}). */
    INT,
    /** 64-bit IEEE 754 floating point (stored as a Java {@code double}). */
    FLOAT,
    /** Variable-length UTF-8 string. */
    VARCHAR,
    /** Boolean. */
    BOOL
}
