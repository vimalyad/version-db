/**
 * Assembly and entry points: {@link com.minidb.tools.MiniDb} wires every layer
 * (storage, WAL/recovery, MVCC/transactions, query) into a single embeddable
 * database, and {@link com.minidb.tools.MiniDbCli} is the interactive REPL on
 * top of it.
 */
package com.minidb.tools;
