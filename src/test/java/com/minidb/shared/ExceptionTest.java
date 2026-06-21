package com.minidb.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class ExceptionTest {

    @Test
    void allExtendMiniDbException() {
        assertInstanceOf(MiniDbException.class, new StorageException("x"));
        assertInstanceOf(MiniDbException.class, new ParseException("x"));
        assertInstanceOf(MiniDbException.class, new SerializationException("x"));
        assertInstanceOf(MiniDbException.class, new TransactionConflictException("x"));
    }

    @Test
    void miniDbExceptionIsUnchecked() {
        assertInstanceOf(RuntimeException.class, new MiniDbException("x"));
    }

    @Test
    void messageAndCausePropagate() {
        Throwable cause = new IllegalStateException("root");
        StorageException e = new StorageException("disk failed", cause);
        assertEquals("disk failed", e.getMessage());
        assertSame(cause, e.getCause());
    }
}
