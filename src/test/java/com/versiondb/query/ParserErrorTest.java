package com.versiondb.query;

import com.versiondb.shared.ParseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParserErrorTest {

    private ParseException parseError(String sql) {
        return assertThrows(ParseException.class, () -> Parser.parse(sql));
    }

    /** Every parse error names what was expected, what was found, and the position. */
    private void assertWellFormed(ParseException e) {
        String m = e.getMessage();
        assertTrue(m.contains("Expected "), "message names what was expected: " + m);
        assertTrue(m.contains("found "), "message names what was found: " + m);
        assertTrue(m.contains("position "), "message carries a position: " + m);
    }

    @Test
    void missingProjection() {
        ParseException e = parseError("SELECT FROM users");
        assertWellFormed(e);
        assertTrue(e.getMessage().contains("an expression"), e.getMessage());
    }

    @Test
    void missingFromKeyword() {
        ParseException e = parseError("SELECT * users");
        assertWellFormed(e);
        assertTrue(e.getMessage().contains("Expected FROM"), e.getMessage());
        // 'users' starts at index 9.
        assertTrue(e.getMessage().contains("position 9"), e.getMessage());
    }

    @Test
    void endOfInputReportedAsSuch() {
        ParseException e = parseError("SELECT * FROM");
        assertWellFormed(e);
        assertTrue(e.getMessage().contains("found end of input"), e.getMessage());
    }

    @Test
    void missingWhereExpression() {
        ParseException e = parseError("SELECT * FROM t WHERE");
        assertWellFormed(e);
        assertTrue(e.getMessage().contains("found end of input"), e.getMessage());
    }

    @Test
    void joinMissingOnCondition() {
        ParseException e = parseError("SELECT * FROM a JOIN b");
        assertWellFormed(e);
        assertTrue(e.getMessage().contains("Expected ON"), e.getMessage());
    }

    @Test
    void insertColumnListExpectsIdentifier() {
        ParseException e = parseError("INSERT INTO t (a, VALUES (1)");
        assertWellFormed(e);
        assertTrue(e.getMessage().contains("a column name"), e.getMessage());
    }

    @Test
    void trailingTokensAfterStatement() {
        ParseException e = parseError("SELECT * FROM t 1");
        assertWellFormed(e);
        assertTrue(e.getMessage().contains("Expected end of input"), e.getMessage());
    }

    @Test
    void unknownStatementKeyword() {
        ParseException e = parseError("FOO bar");
        assertWellFormed(e);
        assertTrue(e.getMessage().contains("a statement"), e.getMessage());
        assertTrue(e.getMessage().contains("position 0"), e.getMessage());
    }

    @Test
    void dropRequiresTable() {
        ParseException e = parseError("DROP users");
        assertWellFormed(e);
        assertTrue(e.getMessage().contains("Expected TABLE"), e.getMessage());
    }

    @Test
    void createTableUnknownColumnType() {
        ParseException e = parseError("CREATE TABLE t (id WIDGET)");
        assertWellFormed(e);
        assertTrue(e.getMessage().contains("a column type"), e.getMessage());
    }

    @Test
    void unbalancedParenInExpression() {
        ParseException e = parseError("SELECT * FROM t WHERE (a = 1");
        assertWellFormed(e);
        assertTrue(e.getMessage().contains("')'"), e.getMessage());
    }

    @Test
    void updateAssignmentRequiresEquals() {
        ParseException e = parseError("UPDATE t SET a 1");
        assertWellFormed(e);
        assertTrue(e.getMessage().contains("'='"), e.getMessage());
    }

    @Test
    void messageIncludesSourceText() {
        // The full offending SQL is echoed for context.
        ParseException e = parseError("SELECT * users");
        assertTrue(e.getMessage().contains("\"SELECT * users\""), e.getMessage());
    }
}
