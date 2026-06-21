package com.versiondb.shared;

/** Thrown when a SQL string cannot be lexed or parsed. */
public class ParseException extends VersionDbException {

    public ParseException(String message) {
        super(message);
    }

    public ParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
