/**
 * Shared types used across every layer of MiniDB: record identifiers, typed
 * values, column/table metadata, and the exception hierarchy. Defined once here
 * so the storage, wal, txn, and query packages all depend on a single contract.
 */
package com.minidb.shared;
