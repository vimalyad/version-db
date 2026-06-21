/**
 * The Abstract Syntax Tree: immutable node types the {@link com.minidb.query.Parser}
 * produces from a SQL string. Grouped into statements ({@link com.minidb.query.ast.Statement}),
 * from-clause nodes ({@link com.minidb.query.ast.FromItem}), and expressions
 * ({@link com.minidb.query.ast.Expression}). Everything downstream of parsing
 * operates on these nodes, never on the raw SQL text.
 */
package com.minidb.query.ast;
