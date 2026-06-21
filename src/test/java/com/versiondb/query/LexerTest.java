package com.versiondb.query;

import com.versiondb.shared.ParseException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LexerTest {

    private List<Token> lex(String sql) {
        return Lexer.tokenize(sql);
    }

    /** Token types in order, excluding the trailing EOF. */
    private List<TokenType> types(String sql) {
        List<Token> all = lex(sql);
        assertEquals(TokenType.EOF, all.get(all.size() - 1).type(), "list ends with EOF");
        return all.subList(0, all.size() - 1).stream().map(Token::type).toList();
    }

    @Test
    void emptyInputIsJustEof() {
        List<Token> t = lex("   \t\n  ");
        assertEquals(1, t.size());
        assertEquals(TokenType.EOF, t.get(0).type());
    }

    @Test
    void keywordsAreCaseInsensitive() {
        assertEquals(List.of(TokenType.SELECT, TokenType.FROM, TokenType.WHERE),
                types("select FROM Where"));
        assertEquals(List.of(TokenType.SELECT), types("SeLeCt"));
    }

    @Test
    void identifiersVsKeywords() {
        List<Token> t = lex("users user_id _tmp");
        assertEquals(TokenType.IDENTIFIER, t.get(0).type());
        assertEquals("users", t.get(0).value());
        assertEquals(TokenType.IDENTIFIER, t.get(1).type());
        assertEquals("user_id", t.get(1).value());
        assertEquals(TokenType.IDENTIFIER, t.get(2).type());
        assertEquals("_tmp", t.get(2).value());
    }

    @Test
    void integerLiteral() {
        List<Token> t = lex("42");
        assertEquals(TokenType.INTEGER_LITERAL, t.get(0).type());
        assertEquals("42", t.get(0).value());
    }

    @Test
    void floatLiteral() {
        List<Token> t = lex("3.14");
        assertEquals(TokenType.FLOAT_LITERAL, t.get(0).type());
        assertEquals("3.14", t.get(0).value());
    }

    @Test
    void dotAfterNumberWithoutFractionIsSeparate() {
        // "1.foo" — the '.' is punctuation, not a decimal point (no following digit).
        assertEquals(List.of(TokenType.INTEGER_LITERAL, TokenType.DOT, TokenType.IDENTIFIER),
                types("1.foo"));
    }

    @Test
    void qualifiedColumnSplitsOnDot() {
        assertEquals(List.of(TokenType.IDENTIFIER, TokenType.DOT, TokenType.IDENTIFIER),
                types("users.age"));
    }

    @Test
    void stringLiteralBasic() {
        List<Token> t = lex("'Alice'");
        assertEquals(TokenType.STRING_LITERAL, t.get(0).type());
        assertEquals("Alice", t.get(0).value());
    }

    @Test
    void stringLiteralEscapedQuote() {
        // 'O''Brien' decodes to O'Brien
        List<Token> t = lex("'O''Brien'");
        assertEquals(TokenType.STRING_LITERAL, t.get(0).type());
        assertEquals("O'Brien", t.get(0).value());
    }

    @Test
    void emptyStringLiteral() {
        List<Token> t = lex("''");
        assertEquals(TokenType.STRING_LITERAL, t.get(0).type());
        assertEquals("", t.get(0).value());
    }

    @Test
    void unterminatedStringThrows() {
        ParseException e = assertThrows(ParseException.class, () -> lex("'abc"));
        assertTrue(e.getMessage().contains("Unterminated"), e.getMessage());
    }

    @Test
    void allComparisonOperators() {
        assertEquals(
                List.of(TokenType.EQ, TokenType.NEQ, TokenType.NEQ, TokenType.LT,
                        TokenType.GT, TokenType.LTE, TokenType.GTE),
                types("= != <> < > <= >="));
    }

    @Test
    void arithmeticOperators() {
        assertEquals(List.of(TokenType.STAR, TokenType.PLUS, TokenType.MINUS, TokenType.SLASH),
                types("* + - /"));
    }

    @Test
    void punctuation() {
        assertEquals(List.of(TokenType.LPAREN, TokenType.RPAREN, TokenType.COMMA,
                        TokenType.DOT, TokenType.SEMICOLON),
                types("( ) , . ;"));
    }

    @Test
    void loneBangThrows() {
        ParseException e = assertThrows(ParseException.class, () -> lex("a ! b"));
        assertTrue(e.getMessage().contains("'!'"), e.getMessage());
    }

    @Test
    void strayCharacterThrows() {
        ParseException e = assertThrows(ParseException.class, () -> lex("a @ b"));
        assertTrue(e.getMessage().contains("position 2"), e.getMessage());
    }

    @Test
    void lineCommentSkippedToEndOfLine() {
        assertEquals(List.of(TokenType.SELECT, TokenType.STAR, TokenType.FROM, TokenType.IDENTIFIER),
                types("SELECT * -- this is the projection\n FROM t"));
    }

    @Test
    void positionsAreRecorded() {
        // "SELECT a" — SELECT at 0, identifier 'a' at 7
        List<Token> t = lex("SELECT a");
        assertEquals(0, t.get(0).position());
        assertEquals(7, t.get(1).position());
    }

    @Test
    void fullStatementTokenStream() {
        assertEquals(
                List.of(TokenType.SELECT, TokenType.IDENTIFIER, TokenType.COMMA, TokenType.IDENTIFIER,
                        TokenType.FROM, TokenType.IDENTIFIER, TokenType.WHERE, TokenType.IDENTIFIER,
                        TokenType.GTE, TokenType.INTEGER_LITERAL),
                types("SELECT name, age FROM users WHERE age >= 18"));
    }

    @Test
    void booleanAndNullKeywords() {
        assertEquals(List.of(TokenType.TRUE, TokenType.FALSE, TokenType.NULL),
                types("TRUE false Null"));
    }
}
