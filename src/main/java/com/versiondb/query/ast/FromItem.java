package com.versiondb.query.ast;

/**
 * Marker interface for a node in a SELECT's FROM clause: either a single
 * {@link TableRef} or a {@link JoinExpr} combining two from-items. All
 * from-clause nodes are immutable.
 */
public interface FromItem {
}
