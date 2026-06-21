package com.versiondb.shared;

/** Base type for all VersionDB runtime errors. */
public class VersionDbException extends RuntimeException {

    public VersionDbException(String message) {
        super(message);
    }

    public VersionDbException(String message, Throwable cause) {
        super(message, cause);
    }
}
