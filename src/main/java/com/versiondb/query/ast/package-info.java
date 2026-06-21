/**
 * The Abstract Syntax Tree: immutable node types the {@link com.versiondb.query.Parser}
 * produces from a SQL string. Grouped into statements ({@link com.versiondb.query.ast.Statement}),
 * from-clause nodes ({@link com.versiondb.query.ast.FromItem}), and expressions
 * ({@link com.versiondb.query.ast.Expression}). Everything downstream of parsing
 * operates on these nodes, never on the raw SQL text.
 */
package com.versiondb.query.ast;
