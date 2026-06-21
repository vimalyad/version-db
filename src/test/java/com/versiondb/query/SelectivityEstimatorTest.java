package com.versiondb.query;

import com.versiondb.query.ast.BinaryOp;
import com.versiondb.query.ast.ColumnRef;
import com.versiondb.query.ast.Literal;
import com.versiondb.query.ast.UnaryOp;
import com.versiondb.shared.ColumnStats;
import com.versiondb.shared.TableStats;
import com.versiondb.shared.Value;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SelectivityEstimatorTest {

    private static final double EPS = 1e-9;

    private SelectivityEstimator estimator() {
        Map<String, ColumnStats> cols = Map.of(
                "age", new ColumnStats(100, Value.ofInt(0), Value.ofInt(100), 0),
                "id", new ColumnStats(1000, Value.ofInt(1), Value.ofInt(1000), 0));
        return new SelectivityEstimator(new TableStats(1000, 50, cols));
    }

    private static BinaryOp cmp(String op, long v) {
        return new BinaryOp(ColumnRef.of("age"), op, Literal.ofInt(v));
    }

    @Test
    void nullPredicatePassesEverything() {
        assertEquals(1.0, estimator().estimate(null), EPS);
    }

    @Test
    void equalityUsesDistinctCount() {
        assertEquals(0.01, estimator().estimate(cmp("=", 50)), EPS);
        assertEquals(0.99, estimator().estimate(cmp("!=", 50)), EPS);
    }

    @Test
    void rangeFormulas() {
        assertEquals(0.5, estimator().estimate(cmp(">", 50)), EPS);
        assertEquals(0.51, estimator().estimate(cmp(">=", 50)), EPS);
        assertEquals(0.25, estimator().estimate(cmp("<", 25)), EPS);
        assertEquals(0.26, estimator().estimate(cmp("<=", 25)), EPS);
    }

    @Test
    void literalOnLeftIsFlipped() {
        // 50 < age  ==  age > 50
        BinaryOp p = new BinaryOp(Literal.ofInt(50), "<", ColumnRef.of("age"));
        assertEquals(0.5, estimator().estimate(p), EPS);
    }

    @Test
    void andMultipliesOrCombines() {
        BinaryOp and = new BinaryOp(cmp(">", 50), "AND", cmp("<", 80));
        assertEquals(0.5 * 0.8, estimator().estimate(and), EPS);

        BinaryOp or = new BinaryOp(cmp("=", 10), "OR", cmp("=", 20));
        assertEquals(0.01 + 0.01 - 0.0001, estimator().estimate(or), EPS);
    }

    @Test
    void unknownColumnAndNonComparisonFallBackToDefault() {
        BinaryOp unknown = new BinaryOp(ColumnRef.of("missing"), "=", Literal.ofInt(1));
        assertEquals(SelectivityEstimator.DEFAULT, estimator().estimate(unknown), EPS);

        UnaryOp not = new UnaryOp("NOT", cmp("=", 5));
        assertEquals(SelectivityEstimator.DEFAULT, estimator().estimate(not), EPS);

        // column-to-column comparison is not a single-column filter
        BinaryOp colcol = new BinaryOp(ColumnRef.of("age"), "=", ColumnRef.of("id"));
        assertEquals(SelectivityEstimator.DEFAULT, estimator().estimate(colcol), EPS);
    }

    @Test
    void resultsAreClampedToUnitInterval() {
        assertEquals(1.0, estimator().estimate(cmp(">", -100)), EPS); // would exceed 1.0
        assertEquals(0.0, estimator().estimate(cmp("<", 0)), EPS);    // nothing below the min
    }
}
