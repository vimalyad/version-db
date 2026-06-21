package com.versiondb.query.exec;

import com.versiondb.query.ast.BinaryOp;
import com.versiondb.query.ast.ColumnRef;
import com.versiondb.query.ast.Literal;
import com.versiondb.query.ast.UnaryOp;
import com.versiondb.shared.Value;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionEvaluatorTest {

    private static Tuple row() {
        return new Tuple(
                List.of("t", "t", "t"),
                List.of("age", "name", "active"),
                Arrays.asList(Value.ofInt(30), Value.ofString("bob"), Value.ofBool(true)));
    }

    @Test
    void columnAndLiteral() {
        assertEquals(Value.ofInt(30), ExpressionEvaluator.eval(ColumnRef.of("age"), row()));
        assertEquals(Value.ofInt(5), ExpressionEvaluator.eval(Literal.ofInt(5), row()));
    }

    @Test
    void comparisons() {
        assertTrue(ExpressionEvaluator.test(new BinaryOp(ColumnRef.of("age"), ">", Literal.ofInt(25)), row()));
        assertFalse(ExpressionEvaluator.test(new BinaryOp(ColumnRef.of("age"), "<", Literal.ofInt(25)), row()));
        assertTrue(ExpressionEvaluator.test(new BinaryOp(ColumnRef.of("name"), "=", Literal.ofString("bob")), row()));
    }

    @Test
    void logicalAndOrNot() {
        BinaryOp gt = new BinaryOp(ColumnRef.of("age"), ">", Literal.ofInt(25));
        BinaryOp lt = new BinaryOp(ColumnRef.of("age"), "<", Literal.ofInt(10));
        assertFalse(ExpressionEvaluator.test(new BinaryOp(gt, "AND", lt), row()));
        assertTrue(ExpressionEvaluator.test(new BinaryOp(gt, "OR", lt), row()));
        assertTrue(ExpressionEvaluator.test(new UnaryOp("NOT", lt), row()));
    }

    @Test
    void arithmeticWithTypePromotion() {
        assertEquals(Value.ofInt(31),
                ExpressionEvaluator.eval(new BinaryOp(ColumnRef.of("age"), "+", Literal.ofInt(1)), row()));
        assertEquals(Value.ofFloat(15.0),
                ExpressionEvaluator.eval(new BinaryOp(ColumnRef.of("age"), "/", Literal.ofFloat(2.0)), row()));
    }

    @Test
    void booleanColumnAsPredicate() {
        assertTrue(ExpressionEvaluator.test(ColumnRef.of("active"), row()));
    }

    @Test
    void nullOperandComparisonIsFalse() {
        Tuple t = new Tuple(List.of("t"), List.of("x"), Arrays.asList((Value) null));
        assertFalse(ExpressionEvaluator.test(new BinaryOp(ColumnRef.of("x"), "=", Literal.ofInt(1)), t));
    }
}
