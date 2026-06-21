package com.versiondb.query.ast;

/**
 * The {@code *} wildcard: {@code SELECT *} or the argument of {@code COUNT(*)}.
 * All {@code Star} instances are equal.
 */
public record Star() implements Expression {
}
