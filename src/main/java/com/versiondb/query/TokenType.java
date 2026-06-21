package com.versiondb.query;

import java.util.Map;

/**
 * The lexical categories produced by the {@link Lexer}. Keywords each have their
 * own constant so the {@link Parser} can match them directly; identifiers,
 * literals, operators, and punctuation are grouped by kind.
 */
public enum TokenType {

    // Keywords (matched case-insensitively).
    SELECT, FROM, WHERE, INSERT, INTO, VALUES, DELETE, UPDATE, SET,
    CREATE, TABLE, DROP, INDEX, ON, AS,
    JOIN, INNER, LEFT, RIGHT, CROSS,
    AND, OR, NOT,
    ORDER, BY, ASC, DESC, LIMIT, OFFSET,
    NULL, TRUE, FALSE,

    // Identifiers and literals.
    IDENTIFIER,
    INTEGER_LITERAL,
    FLOAT_LITERAL,
    STRING_LITERAL,

    // Operators.
    EQ,        // =
    NEQ,       // != or <>
    LT,        // <
    GT,        // >
    LTE,       // <=
    GTE,       // >=
    STAR,      // *
    PLUS,      // +
    MINUS,     // -
    SLASH,     // /

    // Punctuation.
    LPAREN,    // (
    RPAREN,    // )
    COMMA,     // ,
    DOT,       // .
    SEMICOLON, // ;

    /** End of input sentinel; the last token of every token list. */
    EOF;

    /** Reserved words, keyed by their upper-case spelling. */
    static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("SELECT", SELECT), Map.entry("FROM", FROM), Map.entry("WHERE", WHERE),
            Map.entry("INSERT", INSERT), Map.entry("INTO", INTO), Map.entry("VALUES", VALUES),
            Map.entry("DELETE", DELETE), Map.entry("UPDATE", UPDATE), Map.entry("SET", SET),
            Map.entry("CREATE", CREATE), Map.entry("TABLE", TABLE), Map.entry("DROP", DROP),
            Map.entry("INDEX", INDEX), Map.entry("ON", ON), Map.entry("AS", AS),
            Map.entry("JOIN", JOIN), Map.entry("INNER", INNER), Map.entry("LEFT", LEFT),
            Map.entry("RIGHT", RIGHT), Map.entry("CROSS", CROSS),
            Map.entry("AND", AND), Map.entry("OR", OR), Map.entry("NOT", NOT),
            Map.entry("ORDER", ORDER), Map.entry("BY", BY), Map.entry("ASC", ASC),
            Map.entry("DESC", DESC), Map.entry("LIMIT", LIMIT), Map.entry("OFFSET", OFFSET),
            Map.entry("NULL", NULL), Map.entry("TRUE", TRUE), Map.entry("FALSE", FALSE));
}
