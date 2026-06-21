package com.versiondb.query;

import com.versiondb.shared.ParseException;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a raw SQL string into a flat list of {@link Token}s. Whitespace and
 * {@code --} line comments are skipped; every other run of characters becomes a
 * token carrying its source position. The returned list always ends with a
 * single {@link TokenType#EOF} token.
 *
 * <p>The lexer is single-use per {@link #tokenize()} call and holds no state
 * between calls. Lexical errors (an unterminated string, a stray character,
 * a lone {@code !}) throw {@link ParseException} with the offending position.
 */
public final class Lexer {

    private final String sql;
    private int pos;

    public Lexer(String sql) {
        if (sql == null) {
            throw new ParseException("SQL source must not be null");
        }
        this.sql = sql;
    }

    /** Convenience: tokenize {@code sql} in one call. */
    public static List<Token> tokenize(String sql) {
        return new Lexer(sql).scan();
    }

    /** Produce the full token list, terminated by an EOF token. */
    public List<Token> scan() {
        List<Token> tokens = new ArrayList<>();
        Token t;
        do {
            t = next();
            tokens.add(t);
        } while (t.type() != TokenType.EOF);
        return tokens;
    }

    private Token next() {
        skipTrivia();
        if (pos >= sql.length()) {
            return new Token(TokenType.EOF, "", pos);
        }
        int start = pos;
        char c = sql.charAt(pos);

        if (isIdentifierStart(c)) {
            return identifierOrKeyword(start);
        }
        if (Character.isDigit(c)) {
            return number(start);
        }
        if (c == '\'') {
            return stringLiteral(start);
        }
        return operatorOrPunctuation(start);
    }

    // ---- character classes ---------------------------------------------------

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ---- token scanners ------------------------------------------------------

    private Token identifierOrKeyword(int start) {
        while (pos < sql.length() && isIdentifierPart(sql.charAt(pos))) {
            pos++;
        }
        String text = sql.substring(start, pos);
        TokenType keyword = TokenType.KEYWORDS.get(text.toUpperCase());
        return new Token(keyword != null ? keyword : TokenType.IDENTIFIER, text, start);
    }

    private Token number(int start) {
        while (pos < sql.length() && Character.isDigit(sql.charAt(pos))) {
            pos++;
        }
        boolean isFloat = false;
        // A '.' is part of the number only when followed by a digit; otherwise it
        // is a separate DOT (e.g. "users.age" must not eat the dot here).
        if (pos + 1 < sql.length() && sql.charAt(pos) == '.'
                && Character.isDigit(sql.charAt(pos + 1))) {
            isFloat = true;
            pos++; // consume '.'
            while (pos < sql.length() && Character.isDigit(sql.charAt(pos))) {
                pos++;
            }
        }
        String text = sql.substring(start, pos);
        return new Token(isFloat ? TokenType.FLOAT_LITERAL : TokenType.INTEGER_LITERAL, text, start);
    }

    private Token stringLiteral(int start) {
        pos++; // consume opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < sql.length()) {
            char c = sql.charAt(pos);
            if (c == '\'') {
                // A doubled '' is an escaped single quote; a lone ' closes the string.
                if (pos + 1 < sql.length() && sql.charAt(pos + 1) == '\'') {
                    sb.append('\'');
                    pos += 2;
                    continue;
                }
                pos++; // consume closing quote
                return new Token(TokenType.STRING_LITERAL, sb.toString(), start);
            }
            sb.append(c);
            pos++;
        }
        throw new ParseException("Unterminated string literal starting at position " + start
                + " in \"" + sql + "\"");
    }

    private Token operatorOrPunctuation(int start) {
        char c = sql.charAt(pos);
        switch (c) {
            case '=': pos++; return new Token(TokenType.EQ, "=", start);
            case '*': pos++; return new Token(TokenType.STAR, "*", start);
            case '+': pos++; return new Token(TokenType.PLUS, "+", start);
            case '-': pos++; return new Token(TokenType.MINUS, "-", start);
            case '/': pos++; return new Token(TokenType.SLASH, "/", start);
            case '(': pos++; return new Token(TokenType.LPAREN, "(", start);
            case ')': pos++; return new Token(TokenType.RPAREN, ")", start);
            case ',': pos++; return new Token(TokenType.COMMA, ",", start);
            case '.': pos++; return new Token(TokenType.DOT, ".", start);
            case ';': pos++; return new Token(TokenType.SEMICOLON, ";", start);
            case '<':
                pos++;
                if (peekChar() == '=') { pos++; return new Token(TokenType.LTE, "<=", start); }
                if (peekChar() == '>') { pos++; return new Token(TokenType.NEQ, "<>", start); }
                return new Token(TokenType.LT, "<", start);
            case '>':
                pos++;
                if (peekChar() == '=') { pos++; return new Token(TokenType.GTE, ">=", start); }
                return new Token(TokenType.GT, ">", start);
            case '!':
                pos++;
                if (peekChar() == '=') { pos++; return new Token(TokenType.NEQ, "!=", start); }
                throw new ParseException("Unexpected character '!' at position " + start
                        + " (did you mean '!='?) in \"" + sql + "\"");
            default:
                throw new ParseException("Unexpected character '" + c + "' at position " + start
                        + " in \"" + sql + "\"");
        }
    }

    // ---- helpers -------------------------------------------------------------

    /** The character at the current position, or '\0' if at end of input. */
    private char peekChar() {
        return pos < sql.length() ? sql.charAt(pos) : '\0';
    }

    /** Skip whitespace and {@code --} line comments. */
    private void skipTrivia() {
        while (pos < sql.length()) {
            char c = sql.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '-' && pos + 1 < sql.length() && sql.charAt(pos + 1) == '-') {
                pos += 2;
                while (pos < sql.length() && sql.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                return;
            }
        }
    }
}
