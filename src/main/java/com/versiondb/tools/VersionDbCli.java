package com.versiondb.tools;

import com.versiondb.query.exec.ResultSet;
import com.versiondb.shared.Value;
import com.versiondb.txn.Transaction;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.StringJoiner;

/**
 * A line-oriented REPL over {@link VersionDb}. Each input line is one command:
 * a SQL statement (optionally terminated by {@code ;}) or one of the
 * transaction-control words {@code BEGIN} / {@code COMMIT} / {@code ABORT}
 * (alias {@code ROLLBACK}).
 *
 * <p><b>Autocommit.</b> With no explicit transaction open, each statement runs
 * in its own transaction (committed on success, aborted on error). After
 * {@code BEGIN}, statements run in the open transaction until {@code COMMIT} or
 * {@code ABORT}; a statement that errors inside an explicit transaction aborts
 * it (the next command starts fresh in autocommit).
 *
 * <p>{@link #handle(String)} returns the textual result for one line, which
 * makes the REPL easy to drive from tests; {@link #main(String[])} wires it to
 * standard input/output.
 */
public final class VersionDbCli {

    private final VersionDb db;
    private Transaction current; // null → autocommit

    public VersionDbCli(VersionDb db) {
        this.db = db;
    }

    /** Whether an explicit transaction is currently open. */
    public boolean inTransaction() {
        return current != null;
    }

    /**
     * Execute one command line and return its textual result (without a trailing
     * newline). A blank line yields the empty string.
     */
    public String handle(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return "";
        }
        String command = stripTerminator(trimmed);
        String upper = command.toUpperCase();

        if (upper.equals("BEGIN") || upper.equals("BEGIN TRANSACTION") || upper.equals("START TRANSACTION")) {
            if (current != null) {
                return "ERROR: a transaction is already open";
            }
            current = db.begin();
            return "BEGIN";
        }
        if (upper.equals("COMMIT")) {
            if (current == null) {
                return "ERROR: no transaction is open";
            }
            db.commit(current);
            current = null;
            return "COMMIT";
        }
        if (upper.equals("ABORT") || upper.equals("ROLLBACK")) {
            if (current == null) {
                return "ERROR: no transaction is open";
            }
            db.abort(current);
            current = null;
            return "ROLLBACK";
        }

        try {
            ResultSet rs = current != null ? db.execute(command, current) : db.execute(command);
            return format(rs);
        } catch (RuntimeException e) {
            if (current != null) {
                // The statement failed mid-transaction; abort so the session recovers.
                db.abort(current);
                current = null;
                return "ERROR (transaction aborted): " + e.getMessage();
            }
            return "ERROR: " + e.getMessage();
        }
    }

    private static String stripTerminator(String s) {
        return s.endsWith(";") ? s.substring(0, s.length() - 1).strip() : s;
    }

    /** Render a result: a table for queries, an affected-row count for writes/DDL. */
    private static String format(ResultSet rs) {
        if (rs.columns().isEmpty()) {
            return "OK, " + rs.affectedRows() + " row(s) affected";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(row(rs.columns()));
        for (List<Value> r : rs.rows()) {
            sb.append('\n').append(row(r.stream().map(VersionDbCli::cell).toList()));
        }
        sb.append('\n').append('(').append(rs.size()).append(" row(s))");
        return sb.toString();
    }

    private static String row(List<String> cells) {
        StringJoiner j = new StringJoiner(" | ");
        cells.forEach(j::add);
        return j.toString();
    }

    private static String cell(Value v) {
        return v == null || v.isNull() ? "NULL" : v.toString();
    }

    /**
     * Interactive entry point. The first argument is the database directory
     * (defaults to the current directory). Reads commands from standard input
     * until {@code exit}/{@code quit} or end of input.
     */
    public static void main(String[] args) throws IOException {
        Path dir = args.length > 0 ? Path.of(args[0]) : Path.of(".");
        try (VersionDb db = VersionDb.open(dir);
             BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            VersionDbCli cli = new VersionDbCli(db);
            System.out.println("VersionDB ready. End statements with ';'. Type 'exit' to quit.");
            System.out.print("versiondb> ");
            String line;
            while ((line = in.readLine()) != null) {
                String t = line.strip();
                if (t.equalsIgnoreCase("exit") || t.equalsIgnoreCase("quit")) {
                    break;
                }
                String out = cli.handle(line);
                if (!out.isEmpty()) {
                    System.out.println(out);
                }
                System.out.print("versiondb> ");
            }
            System.out.println();
        }
    }
}
