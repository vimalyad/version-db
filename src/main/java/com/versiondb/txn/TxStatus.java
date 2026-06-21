package com.versiondb.txn;

import com.versiondb.shared.StorageException;

/**
 * The final state of a transaction, as recorded in the commit log. Each value
 * has a 2-bit code so four statuses pack into one byte on disk.
 *
 * <p>Code {@code 0b11} is reserved and unused.
 */
public enum TxStatus {

    IN_PROGRESS(0),
    COMMITTED(1),
    ABORTED(2);

    private final int code;

    TxStatus(int code) {
        this.code = code;
    }

    /** The 2-bit on-disk encoding of this status. */
    public int code() {
        return code;
    }

    /** Decode a 2-bit status code back to its enum value. */
    public static TxStatus fromCode(int code) {
        return switch (code) {
            case 0 -> IN_PROGRESS;
            case 1 -> COMMITTED;
            case 2 -> ABORTED;
            default -> throw new StorageException("invalid transaction status code: " + code);
        };
    }
}
