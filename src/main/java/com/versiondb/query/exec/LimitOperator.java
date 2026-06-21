package com.versiondb.query.exec;

/**
 * Limit / offset operator ({@code part2.md} §6.11). {@code open()} skips
 * {@code offset} rows; {@code next()} returns up to {@code limit} rows. Either
 * bound may be {@code null} (no offset / no limit). Pipelined: it pulls only as
 * many rows as it returns, so {@code LIMIT 1} stops the whole pipeline after one
 * passing row.
 */
public final class LimitOperator implements Operator {

    private final Operator child;
    private final Integer limit;
    private final Integer offset;
    private int returned;

    public LimitOperator(Operator child, Integer limit, Integer offset) {
        this.child = child;
        this.limit = limit;
        this.offset = offset;
    }

    @Override
    public void open() {
        child.open();
        returned = 0;
        int toSkip = offset == null ? 0 : offset;
        for (int i = 0; i < toSkip; i++) {
            if (child.next() == null) {
                break;
            }
        }
    }

    @Override
    public Tuple next() {
        if (limit != null && returned >= limit) {
            return null;
        }
        Tuple t = child.next();
        if (t == null) {
            return null;
        }
        returned++;
        return t;
    }

    @Override
    public void close() {
        child.close();
    }
}
