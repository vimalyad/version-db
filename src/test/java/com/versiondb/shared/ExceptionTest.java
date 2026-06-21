package com.versiondb.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ExceptionTest {

    @Test
    void allExtendVersionDbException() {
        assertInstanceOf(VersionDbException.class, new StorageException("x"));
        assertInstanceOf(VersionDbException.class, new ParseException("x"));
        assertInstanceOf(VersionDbException.class, new SerializationException("x"));
        assertInstanceOf(VersionDbException.class, new TransactionConflictException("x"));
    }

    @Test
    void miniDbExceptionIsUnchecked() {
        assertInstanceOf(RuntimeException.class, new VersionDbException("x"));
    }

    @Test
    void messageAndCausePropagate() {
        Throwable cause = new IllegalStateException("root");
        StorageException e = new StorageException("disk failed", cause);
        assertEquals("disk failed", e.getMessage());
        assertSame(cause, e.getCause());
    }
}
