package com.versiondb.query.exec;

import com.versiondb.query.ast.BinaryOp;
import com.versiondb.query.ast.ColumnRef;
import com.versiondb.query.ast.Expression;
import com.versiondb.query.ast.Literal;
import com.versiondb.query.ast.UnaryOp;
import com.versiondb.shared.ColumnType;
import com.versiondb.shared.StorageException;
import com.versiondb.shared.Value;

/**
 * Evaluates an AST {@link Expression} against a runtime {@link Tuple}
 * ({@code part2.md} §6.13). Used by filters (WHERE), projections, and join
 * conditions.
 *
 * <p>SQL NULL is represented by a {@code null} return. NULL handling is
 * simplified for the capstone: a comparison or logical operation involving NULL
 * yields {@code false} rather than three-valued UNKNOWN, and arithmetic on NULL
 * yields NULL.
 */
public final class ExpressionEvaluator {

    private ExpressionEvaluator() {
    }

    /** Evaluate {@code e} to a value, or {@code null} for SQL NULL. */
    public static Value eval(Expression e, Tuple tuple) {
        if (e instanceof Literal lit) {
            return lit.value(); // null for the NULL literal
        }
        if (e instanceof ColumnRef c) {
            Value v = tuple.get(c.table(), c.column());
            return (v != null && v.isNull()) ? null : v;
        }
        if (e instanceof UnaryOp u) {
            return unary(u, tuple);
        }
        if (e instanceof BinaryOp b) {
            return binary(b, tuple);
        }
        throw new StorageException("cannot evaluate expression: " + e.getClass().getSimpleName());
    }

    /** Evaluate {@code predicate} as a boolean; NULL/non-boolean is treated as false. */
    public static boolean test(Expression predicate, Tuple tuple) {
        Value v = eval(predicate, tuple);
        return v != null && !v.isNull() && v.type() == ColumnType.BOOL && v.asBool();
    }

    private static Value unary(UnaryOp u, Tuple tuple) {
        return switch (u.op()) {
            case "NOT" -> Value.ofBool(!test(u.operand(), tuple));
            case "-" -> {
                Value v = eval(u.operand(), tuple);
                if (v == null) {
                    yield null;
                }
                yield v.type() == ColumnType.FLOAT ? Value.ofFloat(-v.asFloat()) : Value.ofInt(-v.asInt());
            }
            default -> throw new StorageException("unknown unary operator " + u.op());
        };
    }

    private static Value binary(BinaryOp b, Tuple tuple) {
        switch (b.op()) {
            case "AND":
                return Value.ofBool(test(b.left(), tuple) && test(b.right(), tuple));
            case "OR":
                return Value.ofBool(test(b.left(), tuple) || test(b.right(), tuple));
            default:
                break;
        }

        Value l = eval(b.left(), tuple);
        Value r = eval(b.right(), tuple);

        return switch (b.op()) {
            case "=" -> Value.ofBool(l != null && r != null && compare(l, r) == 0);
            case "!=" -> Value.ofBool(l != null && r != null && compare(l, r) != 0);
            case "<" -> Value.ofBool(l != null && r != null && compare(l, r) < 0);
            case ">" -> Value.ofBool(l != null && r != null && compare(l, r) > 0);
            case "<=" -> Value.ofBool(l != null && r != null && compare(l, r) <= 0);
            case ">=" -> Value.ofBool(l != null && r != null && compare(l, r) >= 0);
            case "+", "-", "*", "/" -> arithmetic(b.op(), l, r);
            default -> throw new StorageException("unknown binary operator " + b.op());
        };
    }

    private static Value arithmetic(String op, Value l, Value r) {
        if (l == null || r == null) {
            return null;
        }
        boolean useFloat = l.type() == ColumnType.FLOAT || r.type() == ColumnType.FLOAT;
        if (useFloat) {
            double a = asDouble(l);
            double bb = asDouble(r);
            return Value.ofFloat(switch (op) {
                case "+" -> a + bb;
                case "-" -> a - bb;
                case "*" -> a * bb;
                case "/" -> a / bb;
                default -> throw new StorageException("bad arithmetic op " + op);
            });
        }
        long a = l.asInt();
        long bb = r.asInt();
        if ((op.equals("/")) && bb == 0) {
            throw new StorageException("division by zero");
        }
        return Value.ofInt(switch (op) {
            case "+" -> a + bb;
            case "-" -> a - bb;
            case "*" -> a * bb;
            case "/" -> a / bb;
            default -> throw new StorageException("bad arithmetic op " + op);
        });
    }

    /** Total order for comparisons; numeric types compare across INT/FLOAT. */
    static int compare(Value a, Value b) {
        if (isNumeric(a) && isNumeric(b)) {
            if (a.type() == ColumnType.INT && b.type() == ColumnType.INT) {
                return Long.compare(a.asInt(), b.asInt());
            }
            return Double.compare(asDouble(a), asDouble(b));
        }
        if (a.type() != b.type()) {
            throw new StorageException("cannot compare " + a.type() + " with " + b.type());
        }
        return switch (a.type()) {
            case VARCHAR -> a.asString().compareTo(b.asString());
            case BOOL -> Boolean.compare(a.asBool(), b.asBool());
            default -> throw new StorageException("uncomparable type " + a.type());
        };
    }

    private static boolean isNumeric(Value v) {
        return v.type() == ColumnType.INT || v.type() == ColumnType.FLOAT;
    }

    private static double asDouble(Value v) {
        return v.type() == ColumnType.INT ? (double) v.asInt() : v.asFloat();
    }
}
