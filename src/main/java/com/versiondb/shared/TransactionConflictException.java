package com.versiondb.shared;

/**
 * Thrown when a transaction cannot proceed because of a concurrent conflict
 * (e.g. a write-write conflict under first-writer-wins). The caller is expected
 * to abort and optionally retry.
 */
public class TransactionConflictException extends VersionDbException {

    public TransactionConflictException(String message) {
        super(message);
    }

    public TransactionConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
