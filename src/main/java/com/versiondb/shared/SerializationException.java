package com.versiondb.shared;

/** Thrown when encoding or decoding a tuple, page, or log record fails. */
public class SerializationException extends VersionDbException {

    public SerializationException(String message) {
        super(message);
    }

    public SerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
