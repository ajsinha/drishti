/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server.bi.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Drishti SQL parser (docs/architecture/BI_DESIGN.md section 3.4; decision 1 of section 13): hand-written recursive descent over the documented ANSI
 * {@code SELECT} subset, because Apache Calcite 1.40's transitive dependencies are not all in the offline Maven cache and the dialect's table clauses
 * ({@code AS OF DATE}, {@code FOR LIVE}, {@code FOR WINDOW}) and joins without {@code ON} are its own. One {@code SELECT} or {@code WITH ... SELECT},
 * {@code UNION [ALL]}, {@code FROM} with joins, {@code WHERE}, {@code GROUP BY}, {@code HAVING}, {@code ORDER BY}, {@code LIMIT/OFFSET}, {@code CASE},
 * {@code CAST}, aggregates, {@code MEASURE('name')}, window functions with {@code OVER}, optionally under {@code EXPLAIN}. Anything else (DDL, DML,
 * {@code ATTACH}, {@code COPY}, {@code PRAGMA}, {@code SET}, several statements) is {@code DRS-9170}. Bounded (length and depth). Pure.
 */
public final class DsqlParser {

    public static final int MAX_LENGTH = 20_000;
    private static final int MAX_DEPTH = 64;

    private static final Set<String> RESERVED = Set.of("SELECT", "FROM", "WHERE", "GROUP", "BY", "HAVING", "ORDER", "LIMIT", "OFFSET", "UNION", "ALL", "JOIN", "ON",
            "LEFT", "RIGHT", "FULL", "INNER", "OUTER", "CROSS", "AS", "AND", "OR", "NOT", "IN", "IS", "NULL", "BETWEEN", "LIKE", "CASE", "WHEN", "THEN", "ELSE", "END",
            "DISTINCT", "WITH", "ASC", "DESC", "FOR", "OVER", "PARTITION", "WITHIN", "TRUE", "FALSE", "EXPLAIN", "USING", "NATURAL", "INTERSECT", "EXCEPT");
    private static final Set<String> WRITES = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "DROP", "ALTER", "TRUNCATE", "ATTACH", "DETACH", "COPY", "PRAGMA",
            "SET", "RESET", "INSTALL", "LOAD", "CALL", "EXPORT", "IMPORT", "GRANT", "REVOKE", "VACUUM", "CHECKPOINT", "BEGIN", "COMMIT", "ROLLBACK", "USE", "DESCRIBE",
            "SHOW", "SUMMARIZE");

    enum T { NUM, STR, IDENT, QIDENT, OP, EOF }

    record Tok(T type, String text, int start, int end) {
        String upper() {
            return text.toUpperCase(Locale.ROOT);
        }
    }

    private final List<Tok> toks;
    private int p;
    private int depth;

    private DsqlParser(List<Tok> toks) {
        this.toks = toks;
    }

    public static SqlAst.Statement parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new DsqlException(DsqlException.SYNTAX, "the statement is empty", "SELECT ... FROM <alias> ...", 0);
        }
        if (sql.length() > MAX_LENGTH) {
            throw new DsqlException(DsqlException.SYNTAX, "the statement is longer than " + MAX_LENGTH + " characters", null, MAX_LENGTH);
        }
        DsqlParser ps = new DsqlParser(lex(sql));
        Tok first = ps.peek();
        if (first.type() == T.IDENT && WRITES.contains(first.upper())) {
            throw new DsqlException(DsqlException.SYNTAX, "Drishti SQL is read-only: " + first.upper() + " is refused; a statement is one SELECT (or WITH ... SELECT)", null,
                    first.start(), first.end());
        }
        boolean explain = ps.word("EXPLAIN");
        if (!ps.isWord("SELECT") && !ps.isWord("WITH") && !ps.isOp("(")) {
            Tok t = ps.peek();
            throw new DsqlException(DsqlException.SYNTAX, "a Drishti SQL statement starts with SELECT or WITH" + (t.type() == T.EOF ? "" : ", not '" + t.text() + "'"), null, t.start(),
                    t.end());
        }
        SqlAst.Query q = ps.query();
        ps.op(";");
        Tok t = ps.peek();
        if (t.type() != T.EOF) {
            throw new DsqlException(DsqlException.SYNTAX, ps.toks.get(ps.p - 1).text().equals(";") ? "one statement only: Drishti SQL takes a single SELECT"
                    : "unexpected '" + t.text() + "'", null, t.start(), t.end());
        }
        return new SqlAst.Statement(q, explain);
    }

    // ---- lexer -------------------------------------------------------------------------------------------------------

    static List<Tok> lex(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int j = s.indexOf("*/", i + 2);
                if (j < 0) {
                    throw new DsqlException(DsqlException.SYNTAX, "a comment is not closed (*/)", null, i);
                }
                i = j + 2;
            } else if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1))) {
                int j = i;
                while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) {
                    j++;
                }
                if (j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E') && j + 1 < n && (Character.isDigit(s.charAt(j + 1)) || s.charAt(j + 1) == '-' || s.charAt(j + 1) == '+')) {
                    j += 2;
                    while (j < n && Character.isDigit(s.charAt(j))) {
                        j++;
                    }
                }
                out.add(new Tok(T.NUM, s.substring(i, j), i, j));
                i = j;
            } else if (c == '\'') {
                StringBuilder b = new StringBuilder();
                int j = i + 1;
                while (true) {
                    if (j >= n) {
                        throw new DsqlException(DsqlException.SYNTAX, "the text starting here is not closed (')", null, i);
                    }
                    if (s.charAt(j) == '\'') {
                        if (j + 1 < n && s.charAt(j + 1) == '\'') {
                            b.append('\'');
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    b.append(s.charAt(j++));
                }
                out.add(new Tok(T.STR, b.toString(), i, j + 1));
                i = j + 1;
            } else if (c == '"') {
                StringBuilder b = new StringBuilder();
                int j = i + 1;
                while (true) {
                    if (j >= n) {
                        throw new DsqlException(DsqlException.SYNTAX, "the quoted name starting here is not closed (\")", null, i);
                    }
                    if (s.charAt(j) == '"') {
                        if (j + 1 < n && s.charAt(j + 1) == '"') {
                            b.append('"');
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    b.append(s.charAt(j++));
                }
                out.add(new Tok(T.QIDENT, b.toString(), i, j + 1));
                i = j + 1;
            } else if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) {
                    j++;
                }
                out.add(new Tok(T.IDENT, s.substring(i, j), i, j));
                i = j;
            } else {
                String two = i + 1 < n ? s.substring(i, i + 2) : "";
                if (List.of("<=", ">=", "<>", "!=", "||", "::").contains(two)) {
                    out.add(new Tok(T.OP, two, i, i + 2));
                    i += 2;
                } else if ("+-*/%<>=(),.;".indexOf(c) >= 0) {
                    out.add(new Tok(T.OP, String.valueOf(c), i, i + 1));
                    i++;
                } else {
                    throw new DsqlException(DsqlException.SYNTAX, "unexpected character '" + c + "'", null, i, i + 1);
                }
            }
        }
        out.add(new Tok(T.EOF, "", n, n));
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private Tok peek() {
        return toks.get(p);
    }

    private Tok peek(int k) {
        return toks.get(Math.min(p + k, toks.size() - 1));
    }

    private boolean isOp(String o) {
        return peek().type() == T.OP && peek().text().equals(o);
    }

    private boolean op(String o) {
        if (isOp(o)) {
            p++;
            return true;
        }
        return false;
    }

    private boolean isWord(String w) {
        return peek().type() == T.IDENT && peek().upper().equals(w);
    }

    private boolean isWord(int k, String w) {
        Tok t = peek(k);
        return t.type() == T.IDENT && t.upper().equals(w);
    }

    private boolean word(String w) {
        if (isWord(w)) {
            p++;
            return true;
        }
        return false;
    }

    private void expectWord(String w) {
        if (!word(w)) {
            throw unexpected("expected " + w);
        }
    }

    private void expectOp(String o, String what) {
        if (!op(o)) {
            throw unexpected("expected '" + o + "' " + what);
        }
    }

    private DsqlException unexpected(String what) {
        Tok t = peek();
        return new DsqlException(DsqlException.SYNTAX, what + (t.type() == T.EOF ? " but the statement ends" : " but found '" + t.text() + "'"), null, t.start(), t.end());
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw new DsqlException(DsqlException.SYNTAX, "the statement nests deeper than " + MAX_DEPTH + " levels", null, peek().start());
        }
    }

    /** An identifier (plain or quoted) that is not a reserved word. */
    private String name(String what) {
        Tok t = peek();
        if (t.type() == T.QIDENT || t.type() == T.IDENT && !RESERVED.contains(t.upper())) {
            p++;
            return t.text();
        }
        throw unexpected("expected " + what);
    }

    private boolean isName() {
        Tok t = peek();
        return t.type() == T.QIDENT || t.type() == T.IDENT && !RESERVED.contains(t.upper());
    }

    // ---- query -------------------------------------------------------------------------------------------------------

    SqlAst.Query query() {
        enter();
        try {
            int at = peek().start();
            List<SqlAst.Cte> with = new ArrayList<>();
            if (word("WITH")) {
                if (isWord("RECURSIVE")) {
                    throw new DsqlException(DsqlException.SHAPE, "recursive queries are not Drishti SQL", null, peek().start(), peek().end());
                }
                do {
                    int cat = peek().start();
                    String n = name("a name for the WITH query");
                    expectWord("AS");
                    expectOp("(", "around the WITH query");
                    SqlAst.Query q = query();
                    expectOp(")", "to close the WITH query");
                    with.add(new SqlAst.Cte(n, q, cat));
                } while (op(","));
            }
            SqlAst.Body body = union();
            List<SqlAst.Order> order = List.of();
            Long limit = null;
            Long offset = null;
            if (body instanceof SqlAst.Union) {
                if (isWord("ORDER")) {
                    order = orderBy();
                }
                if (word("LIMIT")) {
                    limit = number("LIMIT");
                    if (word("OFFSET")) {
                        offset = number("OFFSET");
                    }
                }
            }
            return new SqlAst.Query(with, body, order, limit, offset, at);
        } finally {
            depth--;
        }
    }

    private SqlAst.Body union() {
        SqlAst.Body left = selectOrParen();
        while (true) {
            int at = peek().start();
            if (isWord("INTERSECT") || isWord("EXCEPT")) {
                throw new DsqlException(DsqlException.SHAPE, peek().upper() + " is not Drishti SQL (yet)", "UNION [ALL], or a filter on the field", peek().start(), peek().end());
            }
            if (!word("UNION")) {
                return left;
            }
            boolean all = word("ALL");
            left = new SqlAst.Union(left, selectOrParen(), all, at);
        }
    }

    private SqlAst.Body selectOrParen() {
        if (op("(")) {
            SqlAst.Query q = query();
            expectOp(")", "to close the bracket");
            if (!q.with().isEmpty() || !q.orderBy().isEmpty() || q.limit() != null) {
                throw new DsqlException(DsqlException.SHAPE, "a bracketed part of a UNION is a plain SELECT", null, q.at());
            }
            return q.body();
        }
        return select();
    }

    private SqlAst.Select select() {
        int at = peek().start();
        expectWord("SELECT");
        boolean distinct = word("DISTINCT");
        word("ALL");
        List<SqlAst.Item> items = new ArrayList<>();
        do {
            int iat = peek().start();
            if (op("*")) {
                items.add(new SqlAst.Item(null, null, true, iat));
                continue;
            }
            SqlAst.Expr e = expr();
            String alias = null;
            if (word("AS")) {
                alias = name("a name after AS");
            } else if (isName() && !isWord("FROM")) {
                alias = name("a name");
            }
            items.add(new SqlAst.Item(e, alias, false, iat));
        } while (op(","));
        List<SqlAst.From> from = new ArrayList<>();
        SqlAst.TimeClause time = null;
        if (word("FROM")) {
            from.add(tableRef(null, null));
            while (true) {
                if (op(",")) {
                    throw new DsqlException(DsqlException.JOIN, "a comma join (FROM a, b) is not Drishti SQL", "JOIN b (the model's relationship is used)", toks.get(p - 1).start());
                }
                String kind = joinKind();
                if (kind == null) {
                    break;
                }
                int jat = peek().start();
                SqlAst.From t = tableRef(kind, null);
                SqlAst.Expr on = null;
                if (word("ON")) {
                    on = expr();
                } else if (isWord("USING")) {
                    throw new DsqlException(DsqlException.JOIN, "JOIN ... USING is not Drishti SQL", "JOIN without ON uses the model's relationship", peek().start(), peek().end());
                }
                from.add(new SqlAst.From(t.table(), t.subquery(), t.alias(), kind, on, jat));
            }
            time = time();
        } else {
            throw unexpected("a SELECT reads FROM a table of the model: expected FROM");
        }
        SqlAst.Expr where = word("WHERE") ? expr() : null;
        List<SqlAst.Expr> group = new ArrayList<>();
        if (word("GROUP")) {
            expectWord("BY");
            do {
                group.add(expr());
            } while (op(","));
        }
        SqlAst.Expr having = word("HAVING") ? expr() : null;
        List<SqlAst.Order> order = isWord("ORDER") ? orderBy() : List.of();
        Long limit = null;
        Long offset = null;
        if (word("LIMIT")) {
            limit = number("LIMIT");
            if (word("OFFSET")) {
                offset = number("OFFSET");
            }
        } else if (word("OFFSET")) {
            offset = number("OFFSET");
        }
        if (time == null) {
            time = time();
        }
        return new SqlAst.Select(distinct, items, from, where, group, having, order, limit, offset, time, at);
    }

    private String joinKind() {
        if (word("JOIN")) {
            return "inner";
        }
        if (isWord("INNER") && isWord(1, "JOIN")) {
            p += 2;
            return "inner";
        }
        if (isWord("LEFT")) {
            p++;
            word("OUTER");
            expectWord("JOIN");
            return "left";
        }
        if (isWord("RIGHT") || isWord("FULL") || isWord("CROSS") || isWord("NATURAL")) {
            throw new DsqlException(DsqlException.JOIN, peek().upper() + " JOIN is not Drishti SQL", "JOIN or LEFT JOIN along a relationship of the model", peek().start(), peek().end());
        }
        return null;
    }

    private SqlAst.From tableRef(String join, SqlAst.Expr on) {
        int at = peek().start();
        if (op("(")) {
            SqlAst.Query q = query();
            expectOp(")", "to close the subquery");
            word("AS");
            String alias = name("a name for the subquery (FROM (SELECT ...) AS x)");
            return new SqlAst.From(null, q, alias, join, on, at);
        }
        String table = name("a table: one of the model's aliases");
        if (op(".")) {
            Tok t = toks.get(p - 1);
            throw new DsqlException(DsqlException.SCOPE, "a table is one of the model's aliases ('" + table + "." + peek().text() + "' names something outside the model)", null, at,
                    t.end());
        }
        if (op("(")) {
            throw new DsqlException(DsqlException.FUNCTION, "table functions (" + table + "(...)) are not Drishti SQL: tables are the model's aliases", null, at, peek().start());
        }
        String alias = null;
        if (word("AS")) {
            if (isWord("OF")) {
                p--;                                                            // AS OF DATE: the table clause, not an alias
            } else {
                alias = name("a name after AS");
            }
        } else if (isName() && !isWord("FOR")) {
            alias = name("a name");
        }
        return new SqlAst.From(table, null, alias, join, on, at);
    }

    private SqlAst.TimeClause time() {
        int at = peek().start();
        if (isWord("AS") && isWord(1, "OF")) {
            p += 2;
            word("DATE");
            Tok t = peek();
            if (t.type() != T.STR) {
                throw unexpected("AS OF DATE takes a date in quotes: AS OF DATE '2026-10-09'");
            }
            p++;
            return new SqlAst.TimeClause("as-of", t.text(), null, null, null, at);
        }
        if (isWord("FOR") && isWord(1, "LIVE")) {
            p += 2;
            return new SqlAst.TimeClause("live", null, null, null, null, at);
        }
        if (isWord("FOR") && isWord(1, "WINDOW")) {
            p += 2;
            if (word("LAST")) {
                Tok n = peek();
                if (n.type() != T.NUM) {
                    throw unexpected("FOR WINDOW LAST takes a number and a unit: FOR WINDOW LAST 1 HOUR");
                }
                p++;
                Tok unit = peek();
                if (unit.type() != T.IDENT) {
                    throw unexpected("a unit (MINUTE, HOUR, DAY)");
                }
                p++;
                return new SqlAst.TimeClause("last", null, null, null, n.text() + " " + unit.upper(), at);
            }
            if (word("FROM")) {
                word("DATE");
                Tok a = peek();
                if (a.type() != T.STR) {
                    throw unexpected("FOR WINDOW FROM DATE '...' TO DATE '...'");
                }
                p++;
                expectWord("TO");
                word("DATE");
                Tok b = peek();
                if (b.type() != T.STR) {
                    throw unexpected("FOR WINDOW FROM DATE '...' TO DATE '...'");
                }
                p++;
                return new SqlAst.TimeClause("window", null, a.text(), b.text(), null, at);
            }
            throw unexpected("FOR WINDOW LAST n unit, or FOR WINDOW FROM DATE '...' TO DATE '...'");
        }
        return null;
    }

    private List<SqlAst.Order> orderBy() {
        expectWord("ORDER");
        expectWord("BY");
        List<SqlAst.Order> out = new ArrayList<>();
        do {
            int at = peek().start();
            SqlAst.Expr e = expr();
            boolean desc = false;
            if (word("DESC")) {
                desc = true;
            } else {
                word("ASC");
            }
            if (word("NULLS")) {
                if (!word("FIRST")) {
                    expectWord("LAST");
                }
            }
            out.add(new SqlAst.Order(e, desc, at));
        } while (op(","));
        return out;
    }

    private long number(String what) {
        Tok t = peek();
        if (t.type() != T.NUM || t.text().contains(".")) {
            throw unexpected(what + " takes a whole number");
        }
        p++;
        return Long.parseLong(t.text());
    }

    // ---- expressions -------------------------------------------------------------------------------------------------

    SqlAst.Expr expr() {
        enter();
        try {
            return or();
        } finally {
            depth--;
        }
    }

    private SqlAst.Expr or() {
        SqlAst.Expr l = and();
        while (isWord("OR")) {
            int at = peek().start();
            p++;
            l = new SqlAst.Binary("OR", l, and(), at);
        }
        return l;
    }

    private SqlAst.Expr and() {
        SqlAst.Expr l = not();
        while (isWord("AND")) {
            int at = peek().start();
            p++;
            l = new SqlAst.Binary("AND", l, not(), at);
        }
        return l;
    }

    private SqlAst.Expr not() {
        if (isWord("NOT")) {
            int at = peek().start();
            p++;
            return new SqlAst.Unary("NOT", not(), at);
        }
        return predicate();
    }

    private SqlAst.Expr predicate() {
        SqlAst.Expr l = add();
        int at = peek().start();
        for (String o : new String[] {"<=", ">=", "<>", "!=", "=", "<", ">"}) {
            if (op(o)) {
                return new SqlAst.Binary("!=".equals(o) ? "<>" : o, l, add(), at);
            }
        }
        boolean negated = false;
        if (isWord("NOT") && (isWord(1, "IN") || isWord(1, "BETWEEN") || isWord(1, "LIKE"))) {
            p++;
            negated = true;
        }
        if (word("IN")) {
            expectOp("(", "after IN");
            if (isWord("SELECT") || isWord("WITH")) {
                SqlAst.Query q = query();
                expectOp(")", "to close the subquery");
                return new SqlAst.In(l, List.of(new SqlAst.SubqueryExpr(q, at)), negated, at);
            }
            List<SqlAst.Expr> items = new ArrayList<>();
            do {
                items.add(expr());
            } while (op(","));
            expectOp(")", "to close the IN list");
            return new SqlAst.In(l, items, negated, at);
        }
        if (word("BETWEEN")) {
            SqlAst.Expr lo = add();
            expectWord("AND");
            return new SqlAst.Between(l, lo, add(), negated, at);
        }
        if (word("LIKE")) {
            return new SqlAst.Like(l, add(), negated, at);
        }
        if (word("IS")) {
            boolean not = word("NOT");
            expectWord("NULL");
            return new SqlAst.IsNull(l, not, at);
        }
        return l;
    }

    private SqlAst.Expr add() {
        SqlAst.Expr l = mul();
        while (true) {
            int at = peek().start();
            String o = op("+") ? "+" : op("-") ? "-" : op("||") ? "||" : null;
            if (o == null) {
                return l;
            }
            l = new SqlAst.Binary(o, l, mul(), at);
        }
    }

    private SqlAst.Expr mul() {
        SqlAst.Expr l = unary();
        while (true) {
            int at = peek().start();
            String o = op("*") ? "*" : op("/") ? "/" : op("%") ? "%" : null;
            if (o == null) {
                return l;
            }
            l = new SqlAst.Binary(o, l, unary(), at);
        }
    }

    private SqlAst.Expr unary() {
        int at = peek().start();
        if (op("-")) {
            return new SqlAst.Unary("-", unary(), at);
        }
        op("+");
        SqlAst.Expr e = primary();
        if (op("::")) {
            throw new DsqlException(DsqlException.SHAPE, "'::' casts are not Drishti SQL", "CAST(x AS type)", at);
        }
        return e;
    }

    private SqlAst.Expr primary() {
        Tok t = peek();
        int at = t.start();
        switch (t.type()) {
            case NUM -> {
                p++;
                Object v = t.text().contains(".") || t.text().contains("e") || t.text().contains("E") ? (Object) Double.parseDouble(t.text()) : (Object) Long.parseLong(t.text());
                return new SqlAst.Literal(v, v instanceof Long ? "integer" : "decimal", at);
            }
            case STR -> {
                p++;
                return new SqlAst.Literal(t.text(), "string", at);
            }
            case OP -> {
                if (op("(")) {
                    if (isWord("SELECT") || isWord("WITH")) {
                        SqlAst.Query q = query();
                        expectOp(")", "to close the subquery");
                        return new SqlAst.SubqueryExpr(q, at);
                    }
                    SqlAst.Expr e = expr();
                    if (isOp(",")) {
                        throw new DsqlException(DsqlException.SHAPE, "row values ((a, b)) are not Drishti SQL", null, peek().start());
                    }
                    expectOp(")", "to close the bracket");
                    return e;
                }
                throw unexpected("expected a value");
            }
            case EOF -> throw unexpected("expected a value");
            default -> { }
        }
        String u = t.upper();
        if (t.type() == T.IDENT) {
            switch (u) {
                case "NULL" -> {
                    p++;
                    return new SqlAst.Literal(null, "null", at);
                }
                case "TRUE", "FALSE" -> {
                    p++;
                    return new SqlAst.Literal("TRUE".equals(u), "boolean", at);
                }
                case "DATE", "TIMESTAMP" -> {
                    if (peek(1).type() == T.STR) {
                        p += 2;
                        return new SqlAst.Literal(toks.get(p - 1).text(), u.toLowerCase(Locale.ROOT), at);
                    }
                }
                case "INTERVAL" -> throw new DsqlException(DsqlException.SHAPE, "INTERVAL is not Drishti SQL (yet)", "compare with DATE '...' literals", at, t.end());
                case "CASE" -> {
                    return caseExpr();
                }
                case "CAST" -> {
                    p++;
                    expectOp("(", "after CAST");
                    SqlAst.Expr v = expr();
                    expectWord("AS");
                    Tok type = peek();
                    if (type.type() != T.IDENT) {
                        throw unexpected("a type (VARCHAR, INTEGER, DOUBLE, DECIMAL, DATE, TIMESTAMP, BOOLEAN)");
                    }
                    p++;
                    StringBuilder ty = new StringBuilder(type.upper());
                    if (op("(")) {
                        while (!op(")")) {
                            if (peek().type() == T.EOF) {
                                throw unexpected("expected ')'");
                            }
                            p++;
                        }
                    }
                    expectOp(")", "to close CAST");
                    return new SqlAst.Cast(v, ty.toString(), at);
                }
                case "EXISTS" -> throw new DsqlException(DsqlException.SHAPE, "EXISTS is not Drishti SQL: a semi-join is a relationship of the model", null, at, t.end());
                default -> { }
            }
        }
        if (t.type() == T.IDENT && RESERVED.contains(u) && !"LEFT".equals(u) && !"RIGHT".equals(u)) {
            throw unexpected("expected a value");
        }
        p++;
        if (op("(")) {
            return call(t);
        }
        List<String> parts = new ArrayList<>(List.of(t.text()));
        while (op(".")) {
            if (op("*")) {
                throw new DsqlException(DsqlException.SHAPE, t.text() + ".* is not Drishti SQL", "name the columns", at);
            }
            Tok n = peek();
            if (n.type() != T.IDENT && n.type() != T.QIDENT) {
                throw unexpected("expected a name after '.'");
            }
            p++;
            parts.add(n.text());
        }
        if (isOp("(")) {
            throw new DsqlException(DsqlException.FUNCTION, "'" + String.join(".", parts) + "(...)' is not a function of Drishti SQL", null, at, peek().end());
        }
        return new SqlAst.Column(parts, at);
    }

    private SqlAst.Expr caseExpr() {
        int at = peek().start();
        p++;
        SqlAst.Expr operand = isWord("WHEN") ? null : expr();
        List<SqlAst.Expr> whens = new ArrayList<>();
        List<SqlAst.Expr> thens = new ArrayList<>();
        while (word("WHEN")) {
            whens.add(expr());
            expectWord("THEN");
            thens.add(expr());
        }
        if (whens.isEmpty()) {
            throw unexpected("CASE needs WHEN ... THEN ...");
        }
        SqlAst.Expr orElse = word("ELSE") ? expr() : null;
        expectWord("END");
        return new SqlAst.Case(operand, whens, thens, orElse, at);
    }

    private SqlAst.Expr call(Tok name) {
        int at = name.start();
        String fn = name.upper();
        if ("MEASURE".equals(fn)) {
            Tok arg = peek();
            if (arg.type() != T.STR && arg.type() != T.QIDENT) {
                throw new DsqlException(DsqlException.SYNTAX, "MEASURE takes the measure's name or id in quotes: MEASURE('MTM')", null, arg.start(), arg.end());
            }
            p++;
            expectOp(")", "to close MEASURE(...)");
            return new SqlAst.Measure(arg.text(), at);
        }
        boolean distinct = false;
        boolean star = false;
        List<SqlAst.Expr> args = new ArrayList<>();
        if (!op(")")) {
            if (word("DISTINCT")) {
                distinct = true;
            }
            if (op("*")) {
                star = true;
            } else {
                do {
                    args.add(expr());
                } while (op(","));
            }
            expectOp(")", "to close the call of " + name.text() + "(...)");
        }
        SqlAst.Expr within = null;
        if (word("WITHIN")) {
            expectWord("GROUP");
            expectOp("(", "after WITHIN GROUP");
            List<SqlAst.Order> o = orderBy();
            expectOp(")", "to close WITHIN GROUP");
            within = o.get(0).expr();
        }
        if (word("FILTER")) {
            throw new DsqlException(DsqlException.SHAPE, "FILTER (WHERE ...) on an aggregate is not Drishti SQL (yet)", "a local measure with where=: SUM(x) as a DEL measure sum(x, where=...)", at);
        }
        SqlAst.Over over = null;
        if (word("OVER")) {
            int oat = peek().start();
            expectOp("(", "after OVER");
            List<SqlAst.Expr> partition = new ArrayList<>();
            if (word("PARTITION")) {
                expectWord("BY");
                do {
                    partition.add(expr());
                } while (op(","));
            }
            List<SqlAst.Order> order = isWord("ORDER") ? orderBy() : List.of();
            if (isWord("ROWS") || isWord("RANGE")) {
                throw new DsqlException(DsqlException.SHAPE, "window frames (ROWS/RANGE) are not Drishti SQL (yet)", "a DEL window measure: moving_avg(agg, n, order)", peek().start());
            }
            expectOp(")", "to close OVER (...)");
            over = new SqlAst.Over(partition, order, oat);
        }
        return new SqlAst.Call(fn, args, distinct, star, within, over, at);
    }
}
