package com.versiondb.query.exec;

import com.versiondb.query.ast.Expression;
import com.versiondb.shared.Value;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Hash join for equality conditions ({@code part2.md} §6.9). Build phase: read
 * the entire inner input and hash it by {@code innerKey}. Probe phase: for each
 * outer row, look up matches by {@code outerKey} and emit merged rows. Rows with
 * a NULL join key never match (standard SQL equi-join semantics).
 */
public final class HashJoinOperator implements Operator {

    private final Operator outer;
    private final Operator inner;
    private final Expression outerKey;
    private final Expression innerKey;
    private final Expression condition;

    private final Map<Value, List<Tuple>> hashTable = new HashMap<>();
    private final Queue<Tuple> output = new ArrayDeque<>();

    public HashJoinOperator(Operator outer, Operator inner,
                            Expression outerKey, Expression innerKey, Expression condition) {
        this.outer = outer;
        this.inner = inner;
        this.outerKey = outerKey;
        this.innerKey = innerKey;
        this.condition = condition;
    }

    @Override
    public void open() {
        // Build phase.
        inner.open();
        try {
            Tuple t;
            while ((t = inner.next()) != null) {
                Value key = ExpressionEvaluator.eval(innerKey, t);
                if (key == null) {
                    continue; // NULL keys never match
                }
                hashTable.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
            }
        } finally {
            inner.close();
        }
        outer.open();
    }

    @Override
    public Tuple next() {
        while (true) {
            if (!output.isEmpty()) {
                return output.poll();
            }
            Tuple outerTuple = outer.next();
            if (outerTuple == null) {
                return null;
            }
            Value key = ExpressionEvaluator.eval(outerKey, outerTuple);
            if (key == null) {
                continue;
            }
            List<Tuple> matches = hashTable.get(key);
            if (matches == null) {
                continue;
            }
            for (Tuple m : matches) {
                Tuple merged = Tuple.merge(outerTuple, m);
                if (condition == null || ExpressionEvaluator.test(condition, merged)) {
                    output.add(merged);
                }
            }
        }
    }

    @Override
    public void close() {
        outer.close();
        hashTable.clear();
        output.clear();
    }
}
