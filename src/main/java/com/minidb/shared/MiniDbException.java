package com.minidb.shared;

/** Base type for all MiniDB runtime errors. */
public class MiniDbException extends RuntimeException {

    public MiniDbException(String message) {
        super(message);
    }

    public MiniDbException(String message, Throwable cause) {
        super(message, cause);
    }
}
