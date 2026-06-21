package com.versiondb.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VersionDbCliTest {

    @TempDir
    Path dir;

    private VersionDb db;
    private VersionDbCli cli;

    @BeforeEach
    void setUp() {
        db = VersionDb.openWithoutVacuum(dir);
        cli = new VersionDbCli(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void blankLineProducesNoOutput() {
        assertEquals("", cli.handle("   "));
    }

    @Test
    void ddlAndInsertReportAffectedRows() {
        assertEquals("OK, 0 row(s) affected", cli.handle("CREATE TABLE t (id INT, name VARCHAR);"));
        assertEquals("OK, 2 row(s) affected",
                cli.handle("INSERT INTO t VALUES (1, 'a'), (2, 'b')"));
    }

    @Test
    void selectRendersTable() {
        cli.handle("CREATE TABLE t (id INT, name VARCHAR)");
        cli.handle("INSERT INTO t VALUES (1, 'Alice')");
        String out = cli.handle("SELECT id, name FROM t");
        assertTrue(out.contains("id | name"), out);
        assertTrue(out.contains("1 | Alice"), out);
        assertTrue(out.contains("(1 row(s))"), out);
    }

    @Test
    void autocommitPersistsBetweenCommands() {
        cli.handle("CREATE TABLE t (id INT)");
        cli.handle("INSERT INTO t VALUES (42)");
        assertTrue(cli.handle("SELECT id FROM t").contains("42"));
    }

    @Test
    void explicitBeginCommit() {
        cli.handle("CREATE TABLE t (id INT)");
        assertFalse(cli.inTransaction());
        assertEquals("BEGIN", cli.handle("BEGIN"));
        assertTrue(cli.inTransaction());
        cli.handle("INSERT INTO t VALUES (1)");
        cli.handle("INSERT INTO t VALUES (2)");
        assertEquals("COMMIT", cli.handle("COMMIT"));
        assertFalse(cli.inTransaction());

        assertTrue(cli.handle("SELECT id FROM t").contains("(2 row(s))"));
    }

    @Test
    void explicitRollbackDiscardsChanges() {
        cli.handle("CREATE TABLE t (id INT)");
        cli.handle("INSERT INTO t VALUES (1)");
        cli.handle("BEGIN");
        cli.handle("INSERT INTO t VALUES (2)");
        assertEquals("ROLLBACK", cli.handle("ROLLBACK"));
        assertTrue(cli.handle("SELECT id FROM t").contains("(1 row(s))"));
    }

    @Test
    void commitWithoutTransactionIsAnError() {
        assertTrue(cli.handle("COMMIT").startsWith("ERROR"));
        assertTrue(cli.handle("ROLLBACK").startsWith("ERROR"));
    }

    @Test
    void doubleBeginIsAnError() {
        cli.handle("BEGIN");
        assertTrue(cli.handle("BEGIN").startsWith("ERROR"));
        cli.handle("ROLLBACK");
    }

    @Test
    void syntaxErrorIsReportedInAutocommit() {
        String out = cli.handle("SELECT FROM");
        assertTrue(out.startsWith("ERROR"), out);
    }

    @Test
    void errorInsideTransactionAbortsIt() {
        cli.handle("CREATE TABLE t (id INT)");
        cli.handle("BEGIN");
        String out = cli.handle("SELECT * FROM no_such_table");
        assertTrue(out.startsWith("ERROR"), out);
        assertFalse(cli.inTransaction(), "a failed statement aborts the open transaction");
    }
}
