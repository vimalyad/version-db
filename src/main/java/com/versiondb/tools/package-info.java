/**
 * Assembly and entry points: {@link com.versiondb.tools.VersionDb} wires every layer
 * (storage, WAL/recovery, MVCC/transactions, query) into a single embeddable
 * database, and {@link com.versiondb.tools.VersionDbCli} is the interactive REPL on
 * top of it.
 */
package com.versiondb.tools;
