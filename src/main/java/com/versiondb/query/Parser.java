package com.versiondb.query;

import com.versiondb.query.ast.*;
import com.versiondb.shared.ColumnDef;
import com.versiondb.shared.ColumnType;
import com.versiondb.shared.ParseException;

import java.util.ArrayList;
import java.util.List;

/**
 * A recursive-descent parser: turns a SQL string into an AST {@link Statement}.
 * It lexes the input with {@link Lexer}, then consumes the token stream with one
 * method per grammar rule. Expression parsing follows the SQL precedence chain
 * (lowest to highest): OR, AND, NOT, comparison, additive, multiplicative,
 * unary, primary.
 *
 * <p>Supported statements: SELECT, INSERT, DELETE, UPDATE, CREATE TABLE,
 * CREATE INDEX, DROP TABLE. Every parse error is a {@link ParseException}
 * naming what was expected, what was found, and the source position.
 */
public final class Parser {

    private final String sql;
    private final List<Token> tokens;
    private int pos;

    public Parser(String sql) {
        this.sql = sql;
        this.tokens = Lexer.tokenize(sql);
    }

    /** Parse {@code sql} into a single statement AST. */
    public static Statement parse(String sql) {
        return new Parser(sql).parseStatement();
    }

    /**
     * Parse exactly one statement, allow an optional trailing {@code ;}, and
     * require the input to end there.
     */
    public Statement parseStatement() {
        Statement stmt = statement();
        match(TokenType.SEMICOLON); // optional terminator
        expect(TokenType.EOF, "end of input");
        return stmt;
    }

    // -------------------------------------------------------------------------
    // Statement dispatch
    // -------------------------------------------------------------------------

    private Statement statement() {
        return switch (peek().type()) {
            case SELECT -> selectStatement();
            case INSERT -> insertStatement();
            case DELETE -> deleteStatement();
            case UPDATE -> updateStatement();
            case CREATE -> createStatement();
            case DROP -> dropStatement();
            default -> throw error(
                    "a statement (SELECT, INSERT, UPDATE, DELETE, CREATE, or DROP)");
        };
    }

    // ---- SELECT --------------------------------------------------------------

    private SelectStatement selectStatement() {
        expect(TokenType.SELECT, "SELECT");
        List<SelectItem> projections = projectionList();
        expect(TokenType.FROM, "FROM");
        FromItem from = fromClause();
        Expression where = match(TokenType.WHERE) ? expression() : null;
        List<OrderByItem> orderBy = orderByClause();
        Integer limit = match(TokenType.LIMIT) ? intLiteral("a LIMIT count") : null;
        Integer offset = match(TokenType.OFFSET) ? intLiteral("an OFFSET count") : null;
        return new SelectStatement(projections, from, where, orderBy, limit, offset);
    }

    private List<SelectItem> projectionList() {
        List<SelectItem> items = new ArrayList<>();
        items.add(selectItem());
        while (match(TokenType.COMMA)) {
            items.add(selectItem());
        }
        return items;
    }

    private SelectItem selectItem() {
        if (check(TokenType.STAR)) {
            advance();
            return SelectItem.of(new Star());
        }
        Expression expr = expression();
        String alias = optionalAlias();
        return new SelectItem(expr, alias);
    }

    private FromItem fromClause() {
        FromItem left = tableRef();
        while (checkAny(TokenType.JOIN, TokenType.INNER, TokenType.LEFT,
                TokenType.RIGHT, TokenType.CROSS)) {
            JoinType type = joinType();
            FromItem right = tableRef();
            Expression on = null;
            if (type != JoinType.CROSS) {
                expect(TokenType.ON, "ON after join");
                on = expression();
            }
            left = new JoinExpr(left, right, type, on);
        }
        return left;
    }

    private JoinType joinType() {
        JoinType type;
        if (match(TokenType.INNER)) {
            type = JoinType.INNER;
        } else if (match(TokenType.LEFT)) {
            type = JoinType.LEFT;
        } else if (match(TokenType.RIGHT)) {
            type = JoinType.RIGHT;
        } else if (match(TokenType.CROSS)) {
            type = JoinType.CROSS;
        } else {
            type = JoinType.INNER; // bare JOIN
        }
        expect(TokenType.JOIN, "JOIN");
        return type;
    }

    private TableRef tableRef() {
        String table = expect(TokenType.IDENTIFIER, "a table name").value();
        return new TableRef(table, optionalAlias());
    }

    /** Optional alias: {@code AS name} or a bare {@code name}. Returns null if none. */
    private String optionalAlias() {
        if (match(TokenType.AS)) {
            return expect(TokenType.IDENTIFIER, "an alias after AS").value();
        }
        if (check(TokenType.IDENTIFIER)) {
            return advance().value();
        }
        return null;
    }

    private List<OrderByItem> orderByClause() {
        List<OrderByItem> items = new ArrayList<>();
        if (match(TokenType.ORDER)) {
            expect(TokenType.BY, "BY after ORDER");
            do {
                Expression expr = expression();
                boolean ascending = true;
                if (match(TokenType.DESC)) {
                    ascending = false;
                } else {
                    match(TokenType.ASC); // optional, default ascending
                }
                items.add(new OrderByItem(expr, ascending));
            } while (match(TokenType.COMMA));
        }
        return items;
    }

    // ---- INSERT --------------------------------------------------------------

    private InsertStatement insertStatement() {
        expect(TokenType.INSERT, "INSERT");
        expect(TokenType.INTO, "INTO after INSERT");
        String table = expect(TokenType.IDENTIFIER, "a table name").value();

        List<String> columns = new ArrayList<>();
        if (match(TokenType.LPAREN)) {
            columns.add(expect(TokenType.IDENTIFIER, "a column name").value());
            while (match(TokenType.COMMA)) {
                columns.add(expect(TokenType.IDENTIFIER, "a column name").value());
            }
            expect(TokenType.RPAREN, "')' after the column list");
        }

        expect(TokenType.VALUES, "VALUES");
        List<List<Expression>> rows = new ArrayList<>();
        rows.add(valueRow());
        while (match(TokenType.COMMA)) {
            rows.add(valueRow());
        }
        return new InsertStatement(table, columns, rows);
    }

    private List<Expression> valueRow() {
        expect(TokenType.LPAREN, "'(' to start a VALUES row");
        List<Expression> values = new ArrayList<>();
        values.add(expression());
        while (match(TokenType.COMMA)) {
            values.add(expression());
        }
        expect(TokenType.RPAREN, "')' to close the VALUES row");
        return values;
    }

    // ---- DELETE --------------------------------------------------------------

    private DeleteStatement deleteStatement() {
        expect(TokenType.DELETE, "DELETE");
        expect(TokenType.FROM, "FROM after DELETE");
        String table = expect(TokenType.IDENTIFIER, "a table name").value();
        Expression where = match(TokenType.WHERE) ? expression() : null;
        return new DeleteStatement(table, where);
    }

    // ---- UPDATE --------------------------------------------------------------

    private UpdateStatement updateStatement() {
        expect(TokenType.UPDATE, "UPDATE");
        String table = expect(TokenType.IDENTIFIER, "a table name").value();
        expect(TokenType.SET, "SET after the table name");
        List<Assignment> assignments = new ArrayList<>();
        assignments.add(assignment());
        while (match(TokenType.COMMA)) {
            assignments.add(assignment());
        }
        Expression where = match(TokenType.WHERE) ? expression() : null;
        return new UpdateStatement(table, assignments, where);
    }

    private Assignment assignment() {
        String column = expect(TokenType.IDENTIFIER, "a column name").value();
        expect(TokenType.EQ, "'=' in the assignment");
        return new Assignment(column, expression());
    }

    // ---- CREATE / DROP -------------------------------------------------------

    private Statement createStatement() {
        expect(TokenType.CREATE, "CREATE");
        if (match(TokenType.TABLE)) {
            return createTableBody();
        }
        if (match(TokenType.INDEX)) {
            return createIndexBody();
        }
        throw error("TABLE or INDEX after CREATE");
    }

    private CreateTableStatement createTableBody() {
        String table = expect(TokenType.IDENTIFIER, "a table name").value();
        expect(TokenType.LPAREN, "'(' before the column definitions");
        List<ColumnDef> columns = new ArrayList<>();
        columns.add(columnDef());
        while (match(TokenType.COMMA)) {
            columns.add(columnDef());
        }
        expect(TokenType.RPAREN, "')' after the column definitions");
        return new CreateTableStatement(table, columns);
    }

    private ColumnDef columnDef() {
        String name = expect(TokenType.IDENTIFIER, "a column name").value();
        ColumnType type = columnType();
        boolean nullable = true;
        if (match(TokenType.NOT)) {
            expect(TokenType.NULL, "NULL after NOT");
            nullable = false;
        } else {
            match(TokenType.NULL); // optional explicit NULL
        }
        return new ColumnDef(name, type, nullable);
    }

    private ColumnType columnType() {
        Token tok = expect(TokenType.IDENTIFIER, "a column type (INT, FLOAT, VARCHAR, or BOOL)");
        ColumnType type = switch (tok.value().toUpperCase()) {
            case "INT", "INTEGER", "BIGINT" -> ColumnType.INT;
            case "FLOAT", "DOUBLE", "REAL" -> ColumnType.FLOAT;
            case "VARCHAR", "TEXT", "STRING", "CHAR" -> ColumnType.VARCHAR;
            case "BOOL", "BOOLEAN" -> ColumnType.BOOL;
            default -> throw unexpected(tok, "a column type (INT, FLOAT, VARCHAR, or BOOL)");
        };
        // Optional length/precision spec, e.g. VARCHAR(255) — accepted and ignored.
        if (match(TokenType.LPAREN)) {
            expect(TokenType.INTEGER_LITERAL, "a length inside '( )'");
            expect(TokenType.RPAREN, "')' after the length");
        }
        return type;
    }

    private CreateIndexStatement createIndexBody() {
        String indexName = expect(TokenType.IDENTIFIER, "an index name").value();
        expect(TokenType.ON, "ON after the index name");
        String table = expect(TokenType.IDENTIFIER, "a table name").value();
        String column;
        if (match(TokenType.LPAREN)) {
            column = expect(TokenType.IDENTIFIER, "a column name").value();
            expect(TokenType.RPAREN, "')' after the column name");
        } else {
            column = expect(TokenType.IDENTIFIER, "a column name").value();
        }
        return new CreateIndexStatement(indexName, table, column);
    }

    private DropTableStatement dropStatement() {
        expect(TokenType.DROP, "DROP");
        expect(TokenType.TABLE, "TABLE after DROP");
        String table = expect(TokenType.IDENTIFIER, "a table name").value();
        return new DropTableStatement(table);
    }

    // -------------------------------------------------------------------------
    // Expressions — precedence chain (lowest to highest)
    // -------------------------------------------------------------------------

    private Expression expression() {
        return orExpr();
    }

    private Expression orExpr() {
        Expression left = andExpr();
        while (match(TokenType.OR)) {
            left = new BinaryOp(left, "OR", andExpr());
        }
        return left;
    }

    private Expression andExpr() {
        Expression left = notExpr();
        while (match(TokenType.AND)) {
            left = new BinaryOp(left, "AND", notExpr());
        }
        return left;
    }

    private Expression notExpr() {
        if (match(TokenType.NOT)) {
            return new UnaryOp("NOT", notExpr());
        }
        return comparison();
    }

    private Expression comparison() {
        Expression left = additive();
        while (checkAny(TokenType.EQ, TokenType.NEQ, TokenType.LT, TokenType.GT,
                TokenType.LTE, TokenType.GTE)) {
            String op = spell(advance().type());
            left = new BinaryOp(left, op, additive());
        }
        return left;
    }

    private Expression additive() {
        Expression left = multiplicative();
        while (checkAny(TokenType.PLUS, TokenType.MINUS)) {
            String op = spell(advance().type());
            left = new BinaryOp(left, op, multiplicative());
        }
        return left;
    }

    private Expression multiplicative() {
        Expression left = unary();
        while (checkAny(TokenType.STAR, TokenType.SLASH)) {
            String op = spell(advance().type());
            left = new BinaryOp(left, op, unary());
        }
        return left;
    }

    private Expression unary() {
        if (match(TokenType.MINUS)) {
            return new UnaryOp("-", unary());
        }
        return primary();
    }

    private Expression primary() {
        Token tok = peek();
        switch (tok.type()) {
            case LPAREN -> {
                advance();
                Expression inner = expression();
                expect(TokenType.RPAREN, "')' to close the subexpression");
                return inner;
            }
            case INTEGER_LITERAL -> {
                advance();
                return Literal.ofInt(parseLong(tok));
            }
            case FLOAT_LITERAL -> {
                advance();
                return Literal.ofFloat(Double.parseDouble(tok.value()));
            }
            case STRING_LITERAL -> {
                advance();
                return Literal.ofString(tok.value());
            }
            case TRUE -> {
                advance();
                return Literal.ofBool(true);
            }
            case FALSE -> {
                advance();
                return Literal.ofBool(false);
            }
            case NULL -> {
                advance();
                return Literal.NULL;
            }
            case IDENTIFIER -> {
                return identifierExpr();
            }
            default -> throw error("an expression");
        }
    }

    /** Parse an identifier-led primary: a function call, a qualified column, or a plain column. */
    private Expression identifierExpr() {
        String name = expect(TokenType.IDENTIFIER, "a column or function name").value();
        if (match(TokenType.LPAREN)) {
            List<Expression> args = new ArrayList<>();
            if (check(TokenType.STAR)) {
                advance();
                args.add(new Star()); // COUNT(*)
            } else if (!check(TokenType.RPAREN)) {
                args.add(expression());
                while (match(TokenType.COMMA)) {
                    args.add(expression());
                }
            }
            expect(TokenType.RPAREN, "')' after the function arguments");
            return new FunctionCall(name, args);
        }
        if (match(TokenType.DOT)) {
            String column = expect(TokenType.IDENTIFIER, "a column name after '.'").value();
            return new ColumnRef(name, column);
        }
        return ColumnRef.of(name);
    }

    // -------------------------------------------------------------------------
    // Token-stream helpers
    // -------------------------------------------------------------------------

    private Token peek() {
        return tokens.get(pos);
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private boolean checkAny(TokenType... types) {
        TokenType current = peek().type();
        for (TokenType t : types) {
            if (current == t) {
                return true;
            }
        }
        return false;
    }

    private Token advance() {
        Token t = peek();
        if (t.type() != TokenType.EOF) {
            pos++;
        }
        return t;
    }

    private boolean match(TokenType type) {
        if (check(type)) {
            advance();
            return true;
        }
        return false;
    }

    private Token expect(TokenType type, String expected) {
        if (check(type)) {
            return advance();
        }
        throw error(expected);
    }

    private int intLiteral(String expected) {
        Token tok = expect(TokenType.INTEGER_LITERAL, expected);
        try {
            return Integer.parseInt(tok.value());
        } catch (NumberFormatException e) {
            throw unexpected(tok, expected + " (value out of range)");
        }
    }

    private long parseLong(Token tok) {
        try {
            return Long.parseLong(tok.value());
        } catch (NumberFormatException e) {
            throw unexpected(tok, "an integer in range");
        }
    }

    // -------------------------------------------------------------------------
    // Errors
    // -------------------------------------------------------------------------

    private ParseException error(String expected) {
        return unexpected(peek(), expected);
    }

    private ParseException unexpected(Token found, String expected) {
        String foundDesc = found.type() == TokenType.EOF
                ? "end of input"
                : found.type() + " '" + found.value() + "'";
        return new ParseException("Expected " + expected + ", found " + foundDesc
                + " at position " + found.position() + " in \"" + sql + "\"");
    }

    /** Canonical SQL spelling of an operator token type. */
    private static String spell(TokenType type) {
        return switch (type) {
            case EQ -> "=";
            case NEQ -> "!=";
            case LT -> "<";
            case GT -> ">";
            case LTE -> "<=";
            case GTE -> ">=";
            case PLUS -> "+";
            case MINUS -> "-";
            case STAR -> "*";
            case SLASH -> "/";
            default -> throw new IllegalStateException("not an operator token: " + type);
        };
    }
}
