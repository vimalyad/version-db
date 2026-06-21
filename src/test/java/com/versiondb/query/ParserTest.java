package com.versiondb.query;

import com.versiondb.query.ast.*;
import com.versiondb.shared.ColumnType;
import com.versiondb.shared.Value;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    private Statement parse(String sql) {
        return Parser.parse(sql);
    }

    /** Parse a SELECT and return its WHERE expression. */
    private Expression where(String selectSql) {
        return ((SelectStatement) parse(selectSql)).where();
    }

    // ---- SELECT --------------------------------------------------------------

    @Test
    void selectStar() {
        SelectStatement s = (SelectStatement) parse("SELECT * FROM users");
        assertEquals(1, s.projections().size());
        assertInstanceOf(Star.class, s.projections().get(0).expr());
        assertEquals(new TableRef("users", null), s.from());
        assertNull(s.where());
        assertTrue(s.orderBy().isEmpty());
        assertNull(s.limit());
        assertNull(s.offset());
    }

    @Test
    void selectColumnsWithAliases() {
        SelectStatement s = (SelectStatement) parse("SELECT name, age AS years, id x FROM users");
        assertEquals(3, s.projections().size());
        assertEquals(ColumnRef.of("name"), s.projections().get(0).expr());
        assertNull(s.projections().get(0).alias());
        assertEquals("years", s.projections().get(1).alias());
        assertEquals("x", s.projections().get(2).alias()); // implicit alias
    }

    @Test
    void selectQualifiedColumn() {
        SelectStatement s = (SelectStatement) parse("SELECT users.age FROM users");
        assertEquals(new ColumnRef("users", "age"), s.projections().get(0).expr());
    }

    @Test
    void selectWithWhere() {
        Expression w = where("SELECT * FROM users WHERE age >= 18");
        assertEquals(new BinaryOp(ColumnRef.of("age"), ">=", Literal.ofInt(18)), w);
    }

    @Test
    void selectTableAlias() {
        SelectStatement s = (SelectStatement) parse("SELECT * FROM users AS u");
        assertEquals(new TableRef("users", "u"), s.from());
        SelectStatement s2 = (SelectStatement) parse("SELECT * FROM users u");
        assertEquals(new TableRef("users", "u"), s2.from());
    }

    @Test
    void selectOrderByLimitOffset() {
        SelectStatement s = (SelectStatement) parse(
                "SELECT * FROM users ORDER BY age DESC, name LIMIT 10 OFFSET 5");
        assertEquals(2, s.orderBy().size());
        assertFalse(s.orderBy().get(0).ascending());
        assertEquals(ColumnRef.of("age"), s.orderBy().get(0).expr());
        assertTrue(s.orderBy().get(1).ascending()); // default ASC
        assertEquals(10, s.limit());
        assertEquals(5, s.offset());
    }

    @Test
    void selectInnerJoinOn() {
        SelectStatement s = (SelectStatement) parse(
                "SELECT * FROM a INNER JOIN b ON a.id = b.aid");
        JoinExpr j = (JoinExpr) s.from();
        assertEquals(JoinType.INNER, j.type());
        assertEquals(new TableRef("a", null), j.left());
        assertEquals(new TableRef("b", null), j.right());
        assertEquals(new BinaryOp(new ColumnRef("a", "id"), "=", new ColumnRef("b", "aid")),
                j.on());
    }

    @Test
    void plainJoinIsInner() {
        SelectStatement s = (SelectStatement) parse("SELECT * FROM a JOIN b ON a.id = b.id");
        assertEquals(JoinType.INNER, ((JoinExpr) s.from()).type());
    }

    @Test
    void leftJoinAndCrossJoin() {
        SelectStatement l = (SelectStatement) parse(
                "SELECT * FROM a LEFT JOIN b ON a.id = b.id");
        assertEquals(JoinType.LEFT, ((JoinExpr) l.from()).type());

        SelectStatement c = (SelectStatement) parse("SELECT * FROM a CROSS JOIN b");
        JoinExpr cj = (JoinExpr) c.from();
        assertEquals(JoinType.CROSS, cj.type());
        assertNull(cj.on(), "CROSS JOIN has no ON condition");
    }

    @Test
    void joinsAreLeftAssociative() {
        SelectStatement s = (SelectStatement) parse(
                "SELECT * FROM a JOIN b ON a.id = b.id JOIN c ON b.id = c.id");
        JoinExpr outer = (JoinExpr) s.from();
        assertEquals(new TableRef("c", null), outer.right());
        assertInstanceOf(JoinExpr.class, outer.left()); // (a JOIN b) JOIN c
    }

    @Test
    void countStarAggregate() {
        SelectStatement s = (SelectStatement) parse("SELECT COUNT(*) FROM users");
        FunctionCall fn = (FunctionCall) s.projections().get(0).expr();
        assertEquals("COUNT", fn.name());
        assertEquals(1, fn.args().size());
        assertInstanceOf(Star.class, fn.args().get(0));
    }

    @Test
    void aggregateWithColumnArgument() {
        SelectStatement s = (SelectStatement) parse("SELECT SUM(price) FROM orders");
        FunctionCall fn = (FunctionCall) s.projections().get(0).expr();
        assertEquals("SUM", fn.name());
        assertEquals(ColumnRef.of("price"), fn.args().get(0));
    }

    // ---- INSERT --------------------------------------------------------------

    @Test
    void insertWithColumnList() {
        InsertStatement s = (InsertStatement) parse(
                "INSERT INTO users (id, name) VALUES (1, 'Alice')");
        assertEquals("users", s.table());
        assertEquals(java.util.List.of("id", "name"), s.columns());
        assertEquals(1, s.rows().size());
        assertEquals(Literal.ofInt(1), s.rows().get(0).get(0));
        assertEquals(Literal.ofString("Alice"), s.rows().get(0).get(1));
    }

    @Test
    void insertWithoutColumnListMultipleRows() {
        InsertStatement s = (InsertStatement) parse(
                "INSERT INTO t VALUES (1, 2), (3, 4), (5, 6)");
        assertFalse(s.hasColumnList());
        assertEquals(3, s.rows().size());
        assertEquals(Literal.ofInt(6), s.rows().get(2).get(1));
    }

    // ---- DELETE --------------------------------------------------------------

    @Test
    void deleteWithAndWithoutWhere() {
        DeleteStatement withWhere = (DeleteStatement) parse("DELETE FROM users WHERE id = 1");
        assertEquals("users", withWhere.table());
        assertEquals(new BinaryOp(ColumnRef.of("id"), "=", Literal.ofInt(1)), withWhere.where());

        DeleteStatement all = (DeleteStatement) parse("DELETE FROM users");
        assertNull(all.where());
    }

    // ---- UPDATE --------------------------------------------------------------

    @Test
    void updateAssignmentsAndWhere() {
        UpdateStatement s = (UpdateStatement) parse(
                "UPDATE users SET name = 'Bob', age = 30 WHERE id = 7");
        assertEquals("users", s.table());
        assertEquals(2, s.assignments().size());
        assertEquals(new Assignment("name", Literal.ofString("Bob")), s.assignments().get(0));
        assertEquals(new Assignment("age", Literal.ofInt(30)), s.assignments().get(1));
        assertEquals(new BinaryOp(ColumnRef.of("id"), "=", Literal.ofInt(7)), s.where());
    }

    // ---- CREATE TABLE / INDEX, DROP ------------------------------------------

    @Test
    void createTableColumnTypesAndNullability() {
        CreateTableStatement s = (CreateTableStatement) parse(
                "CREATE TABLE users (id INT NOT NULL, name VARCHAR(255), active BOOL, score FLOAT)");
        assertEquals("users", s.table());
        assertEquals(4, s.columns().size());
        assertEquals(ColumnType.INT, s.columns().get(0).type());
        assertFalse(s.columns().get(0).nullable());
        assertEquals(ColumnType.VARCHAR, s.columns().get(1).type());
        assertTrue(s.columns().get(1).nullable()); // length ignored, default nullable
        assertEquals(ColumnType.BOOL, s.columns().get(2).type());
        assertEquals(ColumnType.FLOAT, s.columns().get(3).type());
    }

    @Test
    void createIndex() {
        CreateIndexStatement s = (CreateIndexStatement) parse(
                "CREATE INDEX idx_age ON users (age)");
        assertEquals("idx_age", s.indexName());
        assertEquals("users", s.table());
        assertEquals("age", s.column());
    }

    @Test
    void dropTable() {
        DropTableStatement s = (DropTableStatement) parse("DROP TABLE users");
        assertEquals("users", s.table());
    }

    @Test
    void trailingSemicolonAllowed() {
        assertInstanceOf(SelectStatement.class, parse("SELECT * FROM t;"));
        assertInstanceOf(DropTableStatement.class, parse("DROP TABLE t ;"));
    }

    // ---- expression precedence (12.4) ----------------------------------------

    @Test
    void orHasLowerPrecedenceThanAnd() {
        // a OR b AND c  ==  a OR (b AND c)
        Expression w = where("SELECT * FROM t WHERE a OR b AND c");
        BinaryOp or = (BinaryOp) w;
        assertEquals("OR", or.op());
        assertEquals(ColumnRef.of("a"), or.left());
        BinaryOp and = (BinaryOp) or.right();
        assertEquals("AND", and.op());
        assertEquals(ColumnRef.of("b"), and.left());
        assertEquals(ColumnRef.of("c"), and.right());
    }

    @Test
    void notBindsTighterThanAndLooserThanComparison() {
        // NOT a = b  ==  NOT (a = b)
        Expression w = where("SELECT * FROM t WHERE NOT a = b");
        UnaryOp not = (UnaryOp) w;
        assertEquals("NOT", not.op());
        assertEquals(new BinaryOp(ColumnRef.of("a"), "=", ColumnRef.of("b")), not.operand());
    }

    @Test
    void multiplicationBeforeAddition() {
        // a + b * c  ==  a + (b * c)
        Expression w = where("SELECT * FROM t WHERE x = a + b * c");
        BinaryOp eq = (BinaryOp) w;
        BinaryOp add = (BinaryOp) eq.right();
        assertEquals("+", add.op());
        assertEquals(ColumnRef.of("a"), add.left());
        BinaryOp mul = (BinaryOp) add.right();
        assertEquals("*", mul.op());
    }

    @Test
    void parenthesesOverridePrecedence() {
        // (a + b) * c
        Expression w = where("SELECT * FROM t WHERE x = (a + b) * c");
        BinaryOp eq = (BinaryOp) w;
        BinaryOp mul = (BinaryOp) eq.right();
        assertEquals("*", mul.op());
        assertEquals("+", ((BinaryOp) mul.left()).op());
    }

    @Test
    void unaryMinus() {
        Expression w = where("SELECT * FROM t WHERE x = -5");
        BinaryOp eq = (BinaryOp) w;
        assertEquals(new UnaryOp("-", Literal.ofInt(5)), eq.right());
    }

    @Test
    void neqOperatorsNormalizeToBangEquals() {
        assertEquals("!=", ((BinaryOp) where("SELECT * FROM t WHERE a <> b")).op());
        assertEquals("!=", ((BinaryOp) where("SELECT * FROM t WHERE a != b")).op());
    }

    @Test
    void literalTypes() {
        SelectStatement s = (SelectStatement) parse(
                "SELECT * FROM t WHERE a = 1 AND b = 2.5 AND c = 'hi' AND d = TRUE AND e = NULL");
        // Walk to the rightmost NULL comparison.
        BinaryOp top = (BinaryOp) s.where();           // ... AND e = NULL
        BinaryOp eNull = (BinaryOp) top.right();
        assertEquals(Literal.NULL, eNull.right());
        assertTrue(((Literal) eNull.right()).isNull());

        // Confirm the float literal carried its value somewhere in the tree.
        assertEquals(Value.ofFloat(2.5), Literal.ofFloat(2.5).value());
    }
}
