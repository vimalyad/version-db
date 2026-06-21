package com.versiondb.query.ast;

import com.versiondb.shared.ColumnDef;
import com.versiondb.shared.ColumnType;
import com.versiondb.shared.Value;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AstNodeTest {

    @Test
    void literalNullVsTypedValue() {
        assertTrue(Literal.NULL.isNull());
        assertNull(Literal.NULL.value());

        Literal i = Literal.ofInt(42);
        assertFalse(i.isNull());
        assertEquals(Value.ofInt(42), i.value());
    }

    @Test
    void recordsHaveValueEquality() {
        assertEquals(ColumnRef.of("age"), new ColumnRef(null, "age"));
        assertEquals(Literal.ofString("x"), Literal.ofString("x"));
        assertEquals(new BinaryOp(ColumnRef.of("a"), "=", Literal.ofInt(1)),
                new BinaryOp(ColumnRef.of("a"), "=", Literal.ofInt(1)));
    }

    @Test
    void allStarsAreEqual() {
        assertEquals(new Star(), new Star());
    }

    @Test
    void columnRefQualification() {
        ColumnRef qualified = new ColumnRef("users", "age");
        assertTrue(qualified.isQualified());
        assertFalse(ColumnRef.of("age").isQualified());
    }

    @Test
    void functionCallArgsAreImmutable() {
        List<Expression> args = new ArrayList<>();
        args.add(new Star());
        FunctionCall count = new FunctionCall("COUNT", args);
        // Defensive copy: mutating the source list does not change the node.
        args.add(Literal.ofInt(1));
        assertEquals(1, count.args().size());
        assertThrows(UnsupportedOperationException.class, () -> count.args().add(new Star()));
    }

    @Test
    void selectStatementDefensiveCopiesAndNullOrderBy() {
        List<SelectItem> proj = new ArrayList<>();
        proj.add(SelectItem.of(new Star()));
        SelectStatement s = new SelectStatement(proj, TableRef.of("t"), null, null, null, null);
        proj.clear();
        assertEquals(1, s.projections().size());
        assertNotNull(s.orderBy(), "null order-by normalizes to empty list");
        assertTrue(s.orderBy().isEmpty());
        assertNull(s.where());
        assertThrows(UnsupportedOperationException.class,
                () -> s.projections().add(SelectItem.of(new Star())));
    }

    @Test
    void insertStatementColumnListOptional() {
        InsertStatement withCols = new InsertStatement(
                "t", List.of("a", "b"), List.of(List.of(Literal.ofInt(1), Literal.ofInt(2))));
        assertTrue(withCols.hasColumnList());

        InsertStatement noCols = new InsertStatement(
                "t", null, List.of(List.of(Literal.ofInt(1))));
        assertFalse(noCols.hasColumnList());
        assertTrue(noCols.columns().isEmpty());
    }

    @Test
    void createTableUsesSharedColumnDef() {
        CreateTableStatement c = new CreateTableStatement("users", List.of(
                new ColumnDef("id", ColumnType.INT, false),
                new ColumnDef("name", ColumnType.VARCHAR, true)));
        assertEquals(2, c.columns().size());
        assertEquals(ColumnType.INT, c.columns().get(0).type());
        assertFalse(c.columns().get(0).nullable());
    }

    @Test
    void joinExprAndTableRef() {
        TableRef a = new TableRef("a", null);
        TableRef b = new TableRef("b", "bb");
        assertFalse(a.hasAlias());
        assertTrue(b.hasAlias());
        JoinExpr j = new JoinExpr(a, b, JoinType.INNER,
                new BinaryOp(new ColumnRef("a", "id"), "=", new ColumnRef("b", "id")));
        assertEquals(JoinType.INNER, j.type());
        assertSame(a, j.left());
    }

    @Test
    void markerInterfaceMembership() {
        assertInstanceOf(Statement.class, new DropTableStatement("t"));
        assertInstanceOf(Expression.class, new Star());
        assertInstanceOf(Expression.class, Literal.NULL);
        assertInstanceOf(FromItem.class, TableRef.of("t"));
    }
}
