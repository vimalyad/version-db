package com.versiondb.shared;

/** Thrown on disk, page, or buffer-pool errors (I/O failures, corruption). */
public class StorageException extends VersionDbException {

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
