package com.versiondb.query;

/**
 * One lexical token: its {@link TokenType}, the exact source text it matched
 * ({@code value}), and the zero-based index in the source string where it
 * starts ({@code position}, used for error messages).
 *
 * <p>For a {@link TokenType#STRING_LITERAL} the {@code value} is the decoded
 * contents (surrounding quotes removed and {@code ''} unescaped to a single
 * quote); for every other token it is the raw matched text. The {@code value}
 * of the {@link TokenType#EOF} token is the empty string.
 */
public record Token(TokenType type, String value, int position) {

    @Override
    public String toString() {
        return type + "(" + value + ")@" + position;
    }
}
