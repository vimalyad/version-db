package com.versiondb.shared;

/** System-wide constants fixed for the lifetime of a database. */
public final class Constants {

    private Constants() {
    }

    /**
     * Size in bytes of a page, the atomic unit of disk I/O. Chosen once and
     * never changed: an existing database file is laid out in pages of exactly
     * this size, so altering it would invalidate every file on disk.
     */
    public static final int PAGE_SIZE = 8192;

    /** Sentinel transaction id meaning "no transaction" (e.g. an undeleted version's xmax). */
    public static final long INVALID_XID = 0L;

    /** First transaction id handed to a real transaction; ids below this are reserved. */
    public static final long FIRST_NORMAL_XID = 1L;

    /** Sentinel page id meaning "no page" (e.g. the end of a heap page chain). */
    public static final int INVALID_PAGE_ID = -1;

    /** Sentinel slot id meaning "no slot". */
    public static final int INVALID_SLOT_ID = -1;
}
