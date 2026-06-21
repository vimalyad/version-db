package com.versiondb.storage;

import com.versiondb.shared.ColumnDef;
import com.versiondb.shared.ColumnMeta;
import com.versiondb.shared.ColumnType;
import com.versiondb.shared.IndexMeta;
import com.versiondb.shared.StorageException;
import com.versiondb.shared.TableMeta;
import com.versiondb.shared.TableStats;
import com.versiondb.shared.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The database's metadata dictionary: which tables exist, their columns, the
 * indexes on them, and optimizer statistics. It answers all "what is in this
 * database" questions for the layers above.
 *
 * <p>The catalog persists itself in three system heap files at fixed, well-known
 * page ids — tables (page 0), columns (page 1), indexes (page 2) — so it is
 * always findable on restart. Each metadata record is encoded with the shared
 * {@link TupleCodec} against a fixed schema, exactly like an ordinary table row.
 * On open, the three heaps are scanned once to rebuild fast in-memory caches;
 * every later lookup is served from memory.
 */
public final class Catalog {

    /** Fixed page ids of the three system heaps. */
    static final int TABLES_PAGE_ID = 0;
    static final int COLUMNS_PAGE_ID = 1;
    static final int INDEXES_PAGE_ID = 2;

    // Schemas for the system-heap records, in field order.
    private static final List<ColumnType> TABLES_SCHEMA = List.of(
            ColumnType.INT,      // tableId
            ColumnType.VARCHAR,  // tableName
            ColumnType.INT,      // firstPageId
            ColumnType.INT,      // numTuples
            ColumnType.INT);     // numPages

    private static final List<ColumnType> COLUMNS_SCHEMA = List.of(
            ColumnType.INT,      // tableId
            ColumnType.VARCHAR,  // columnName
            ColumnType.VARCHAR,  // columnType (enum name)
            ColumnType.INT,      // columnIndex
            ColumnType.BOOL);    // isNullable

    private static final List<ColumnType> INDEXES_SCHEMA = List.of(
            ColumnType.INT,      // indexId
            ColumnType.INT,      // tableId
            ColumnType.VARCHAR,  // columnName
            ColumnType.INT);     // rootPageId

    private final BufferPool bufferPool;

    private final HeapFile tablesHeap;
    private final HeapFile columnsHeap;
    private final HeapFile indexesHeap;

    // In-memory caches, rebuilt from the system heaps on open.
    private final Map<String, TableMeta> tablesByName = new HashMap<>();
    private final Map<Integer, TableMeta> tablesById = new HashMap<>();
    private final Map<Integer, List<ColumnMeta>> columnsByTableId = new HashMap<>();
    private final Map<Integer, List<IndexMeta>> indexesByTableId = new HashMap<>();

    /**
     * Optimizer statistics, held in memory only. The execution engine refreshes
     * these periodically via {@link #updateStats}; they are not persisted (see
     * {@code part1.md} §6.4), so they start empty after a reopen and are derived
     * from {@link TableMeta} until recomputed.
     */
    private final Map<Integer, TableStats> statsByTableId = new HashMap<>();

    private int nextTableId = 0;
    private int nextIndexId = 0;

    /**
     * Open the catalog over the given buffer pool. If the database file is empty
     * this is a fresh database: the three system heaps are created at pages 0, 1
     * and 2. Otherwise the existing system heaps are opened and their records
     * scanned back into the in-memory caches.
     *
     * @param diskManager used only to tell a fresh database from an existing one
     */
    public Catalog(BufferPool bufferPool, DiskManager diskManager) {
        this.bufferPool = bufferPool;
        if (diskManager.getNumPages() == 0) {
            this.tablesHeap = createSystemHeap(TABLES_PAGE_ID);
            this.columnsHeap = createSystemHeap(COLUMNS_PAGE_ID);
            this.indexesHeap = createSystemHeap(INDEXES_PAGE_ID);
        } else {
            this.tablesHeap = new HeapFile(bufferPool, TABLES_PAGE_ID);
            this.columnsHeap = new HeapFile(bufferPool, COLUMNS_PAGE_ID);
            this.indexesHeap = new HeapFile(bufferPool, INDEXES_PAGE_ID);
            load();
        }
    }

    /**
     * Allocate a system heap and verify it landed on its fixed page id. On a
     * fresh database the first three allocations are deterministically pages
     * 0, 1, 2; this guard catches any drift from that assumption.
     */
    private HeapFile createSystemHeap(int expectedPageId) {
        HeapFile heap = HeapFile.create(bufferPool);
        if (heap.getFirstPageId() != expectedPageId) {
            throw new StorageException("system heap expected at page " + expectedPageId
                    + " but was allocated at page " + heap.getFirstPageId());
        }
        return heap;
    }

    /** Rebuild the in-memory caches by scanning the three system heaps. */
    private void load() {
        for (Iterator<HeapFile.Entry> it = tablesHeap.scan(); it.hasNext(); ) {
            TableMeta table = decodeTableMeta(it.next().data());
            cacheTable(table);
            nextTableId = Math.max(nextTableId, table.tableId() + 1);
        }
        for (Iterator<HeapFile.Entry> it = columnsHeap.scan(); it.hasNext(); ) {
            ColumnMeta column = decodeColumnMeta(it.next().data());
            columnsByTableId.computeIfAbsent(column.tableId(), k -> new ArrayList<>()).add(column);
        }
        for (List<ColumnMeta> columns : columnsByTableId.values()) {
            columns.sort(Comparator.comparingInt(ColumnMeta::columnIndex));
        }
        for (Iterator<HeapFile.Entry> it = indexesHeap.scan(); it.hasNext(); ) {
            IndexMeta index = decodeIndexMeta(it.next().data());
            indexesByTableId.computeIfAbsent(index.tableId(), k -> new ArrayList<>()).add(index);
            nextIndexId = Math.max(nextIndexId, index.indexId() + 1);
        }
    }

    private void cacheTable(TableMeta table) {
        tablesByName.put(table.tableName(), table);
        tablesById.put(table.tableId(), table);
    }

    // ---- Tables and columns ---------------------------------------------------

    /**
     * Create a new table: allocate its heap file, assign a table id, and persist
     * one record to the tables heap plus one record per column (in the given
     * order) to the columns heap. The new metadata is cached immediately.
     *
     * @return the metadata for the created table
     * @throws StorageException if a table with this name already exists
     */
    public synchronized TableMeta createTable(String name, List<ColumnDef> columns) {
        if (tablesByName.containsKey(name)) {
            throw new StorageException("table already exists: " + name);
        }
        int tableId = nextTableId++;
        HeapFile heap = HeapFile.create(bufferPool);
        TableMeta table = new TableMeta(tableId, name, heap.getFirstPageId(), 0L, heap.getPageCount());
        tablesHeap.insertTuple(encodeTableMeta(table));
        cacheTable(table);

        List<ColumnMeta> columnMetas = new ArrayList<>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            ColumnDef def = columns.get(i);
            ColumnMeta column = new ColumnMeta(tableId, def.name(), def.type(), i, def.nullable());
            columnsHeap.insertTuple(encodeColumnMeta(column));
            columnMetas.add(column);
        }
        columnsByTableId.put(tableId, columnMetas);
        return table;
    }

    /** @return the table's metadata, or {@code null} if no such table exists. */
    public TableMeta getTable(String name) {
        return tablesByName.get(name);
    }

    /**
     * Every table currently known to the catalog. Used at startup to rebuild the
     * query layer's runtime registries (heap files and indexes), which are not
     * themselves persisted.
     *
     * @return an unmodifiable snapshot of all table metadata
     */
    public synchronized List<TableMeta> allTables() {
        return List.copyOf(tablesByName.values());
    }

    /**
     * @return the table's columns in row order (by column index); an empty list
     *         if the table is unknown or has no columns.
     */
    public List<ColumnMeta> getColumns(int tableId) {
        List<ColumnMeta> columns = columnsByTableId.get(tableId);
        return columns == null ? List.of() : List.copyOf(columns);
    }

    // ---- Indexes --------------------------------------------------------------

    /**
     * Register a new index on a table column, persisting a record to the indexes
     * heap and caching it. The B+Tree root page is owned by the query layer; the
     * catalog only records its page id.
     *
     * @return the metadata for the registered index
     */
    public synchronized IndexMeta registerIndex(int tableId, String columnName, int rootPageId) {
        IndexMeta index = new IndexMeta(nextIndexId++, tableId, columnName, rootPageId);
        indexesHeap.insertTuple(encodeIndexMeta(index));
        indexesByTableId.computeIfAbsent(tableId, k -> new ArrayList<>()).add(index);
        return index;
    }

    /** @return the indexes on the table; an empty list if there are none. */
    public List<IndexMeta> getIndexes(int tableId) {
        List<IndexMeta> indexes = indexesByTableId.get(tableId);
        return indexes == null ? List.of() : List.copyOf(indexes);
    }

    // ---- Statistics -----------------------------------------------------------

    /**
     * Optimizer statistics for a table. Returns the most recently supplied stats
     * from {@link #updateStats}, or — if none have been gathered yet — a default
     * derived from the table's cached row/page counts with no per-column stats.
     *
     * @return the table's statistics, or {@code null} if the table is unknown
     */
    public synchronized TableStats getStats(String tableName) {
        TableMeta table = tablesByName.get(tableName);
        if (table == null) {
            return null;
        }
        TableStats stats = statsByTableId.get(table.tableId());
        if (stats != null) {
            return stats;
        }
        return new TableStats(table.numTuples(), table.numPages(), Map.of());
    }

    /** Replace the cached optimizer statistics for a table. */
    public synchronized void updateStats(int tableId, TableStats stats) {
        statsByTableId.put(tableId, stats);
    }

    // ---- Record encoding / decoding -------------------------------------------

    private static byte[] encodeTableMeta(TableMeta t) {
        return TupleCodec.encode(List.of(
                Value.ofInt(t.tableId()), Value.ofString(t.tableName()),
                Value.ofInt(t.firstPageId()), Value.ofInt(t.numTuples()),
                Value.ofInt(t.numPages())), TABLES_SCHEMA);
    }

    private static byte[] encodeColumnMeta(ColumnMeta c) {
        return TupleCodec.encode(List.of(
                Value.ofInt(c.tableId()), Value.ofString(c.columnName()),
                Value.ofString(c.columnType().name()), Value.ofInt(c.columnIndex()),
                Value.ofBool(c.isNullable())), COLUMNS_SCHEMA);
    }

    private static byte[] encodeIndexMeta(IndexMeta i) {
        return TupleCodec.encode(List.of(
                Value.ofInt(i.indexId()), Value.ofInt(i.tableId()),
                Value.ofString(i.columnName()), Value.ofInt(i.rootPageId())), INDEXES_SCHEMA);
    }

    private static TableMeta decodeTableMeta(byte[] data) {
        List<Value> v = TupleCodec.decode(data, TABLES_SCHEMA);
        return new TableMeta((int) v.get(0).asInt(), v.get(1).asString(),
                (int) v.get(2).asInt(), v.get(3).asInt(), (int) v.get(4).asInt());
    }

    private static ColumnMeta decodeColumnMeta(byte[] data) {
        List<Value> v = TupleCodec.decode(data, COLUMNS_SCHEMA);
        return new ColumnMeta((int) v.get(0).asInt(), v.get(1).asString(),
                ColumnType.valueOf(v.get(2).asString()), (int) v.get(3).asInt(), v.get(4).asBool());
    }

    private static IndexMeta decodeIndexMeta(byte[] data) {
        List<Value> v = TupleCodec.decode(data, INDEXES_SCHEMA);
        return new IndexMeta((int) v.get(0).asInt(), (int) v.get(1).asInt(),
                v.get(2).asString(), (int) v.get(3).asInt());
    }
}
