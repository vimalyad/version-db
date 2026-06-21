package com.versiondb.txn;

/**
 * The isolation level of a transaction, which decides how often it takes a
 * snapshot and therefore which committed data its reads can see.
 *
 * <ul>
 *   <li>{@link #REPEATABLE_READ} — one snapshot, taken at begin time, is reused
 *       for the transaction's whole lifetime. Every read sees the database as it
 *       was the instant the transaction started; commits by others are never
 *       observed mid-transaction. This is VersionDB's Snapshot-Isolation default.</li>
 *   <li>{@link #READ_COMMITTED} — a fresh snapshot is taken at the start of each
 *       statement, so a statement sees everything committed before it began but a
 *       later statement in the same transaction may see newer commits.</li>
 * </ul>
 *
 * <p>Both levels read only committed data (no dirty reads). The difference is
 * the snapshot's lifetime: per-transaction vs. per-statement.
 */
public enum IsolationLevel {

    /** Fresh snapshot per statement; later statements may see newer commits. */
    READ_COMMITTED,

    /** Single begin-time snapshot reused for the whole transaction (SI default). */
    REPEATABLE_READ;

    /** The default isolation level: Snapshot Isolation via {@link #REPEATABLE_READ}. */
    public static final IsolationLevel DEFAULT = REPEATABLE_READ;
}
