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
package com.ash.drishti.plugin.jdbc;

import com.ash.drishti.api.write.WriteCommand;
import com.ash.drishti.api.write.WriteResult;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A connector's {@code writes:} commands for a database (WRITE_BACK.md section 5.4):
 * <pre>
 * jdbc:
 *   sql: UPDATE MX_TRADE_LEG SET NOTIONAL = :value, VERSION = VERSION + 1, DRISHTI_CHANGE_ID = :changeId
 *        WHERE TRADE_ID = :entity AND LEG_NO = :leg AND VERSION = :basedOn
 *   expect-rows: 1              # the concurrency guard: another count means the record moved (conflicted)
 *   already-applied: SELECT 1 FROM MX_TRADE_LEG WHERE TRADE_ID = :entity AND DRISHTI_CHANGE_ID = :changeId   # the idempotency clause
 * </pre>
 * Named parameters ({@code :value}) are bound as prepared-statement parameters, never pasted into the text ({@code ::} casts and
 * quoted text are left alone). One command is one transaction: the idempotency clause first (a resend that finds its change id writes
 * nothing), then the statement, committed only when it touched exactly {@code expect-rows} rows.
 */
final class JdbcWrites {

    static final String KEY = "jdbc";

    private JdbcWrites() {}

    /** The SQL with {@code ?} for each named parameter, and the names in order. */
    record Bound(String sql, List<String> names) {}

    static Bound bind(String sql) {
        StringBuilder out = new StringBuilder();
        List<String> names = new ArrayList<>();
        boolean quoted = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
                out.append(c);
            } else if (!quoted && c == ':' && i + 1 < sql.length() && sql.charAt(i + 1) == ':') {
                out.append("::");                                    // a PostgreSQL cast
                i++;
            } else if (!quoted && c == ':' && i + 1 < sql.length() && (Character.isLetter(sql.charAt(i + 1)) || sql.charAt(i + 1) == '_')) {
                int j = i + 1;
                while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                    j++;
                }
                names.add(sql.substring(i + 1, j));
                out.append('?');
                i = j - 1;
            } else {
                out.append(c);
            }
        }
        return new Bound(out.toString(), names);
    }

    /** What is wrong with a command's {@code jdbc:} block (empty when usable). */
    static List<String> check(String command, Map<String, Object> block, Set<String> known) {
        List<String> out = new ArrayList<>();
        Object sql = block.get("sql");
        if (!(sql instanceof String s) || s.isBlank()) {
            out.add(command + ": jdbc.sql is the UPDATE or INSERT to run");
            return out;
        }
        Object expect = block.get("expect-rows");
        if (!(expect instanceof Number)) {
            out.add(command + ": jdbc.expect-rows is required: the concurrency guard (0 rows means the record moved)");
        }
        Object already = block.get("already-applied");
        if (!(already instanceof String a) || a.isBlank()) {
            out.add(command + ": jdbc.already-applied is required: the idempotency clause, a SELECT that finds this change id");
        }
        for (Object text : List.of(sql, already == null ? "" : already)) {
            for (String n : bind(text.toString()).names()) {
                if (!known.contains(n)) {
                    out.add(command + ": :" + n + " is not a parameter the server supplies or the command declares (" + String.join(", ", new java.util.TreeSet<>(known)) + ")");
                }
            }
        }
        for (String k : block.keySet()) {
            if (!Set.of("sql", "expect-rows", "already-applied").contains(k)) {
                out.add(command + ": jdbc." + k + " is not a key of a database command (sql, expect-rows, already-applied)");
            }
        }
        return out;
    }

    /** Runs one command in one transaction on {@code c}. */
    static WriteResult run(Connection c, WriteCommand cmd) throws SQLException {
        Map<String, Object> t = cmd.template();
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            Bound already = bind(String.valueOf(t.get("already-applied")));
            try (PreparedStatement s = c.prepareStatement(already.sql())) {
                set(s, already.names(), cmd.params());
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) {
                        c.rollback();
                        return WriteResult.already();
                    }
                }
            }
            Bound write = bind(String.valueOf(t.get("sql")));
            int expect = ((Number) t.get("expect-rows")).intValue();
            int rows;
            try (PreparedStatement s = c.prepareStatement(write.sql())) {
                set(s, write.names(), cmd.params());
                rows = s.executeUpdate();
            }
            if (rows != expect) {
                c.rollback();
                return rows == 0 ? WriteResult.conflicted("the record moved at the source: 0 rows matched the guard")
                        : WriteResult.refused(rows + " rows would change, " + expect + " expected: nothing was written");
            }
            c.commit();
            return WriteResult.applied();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private static void set(PreparedStatement s, List<String> names, Map<String, Object> params) throws SQLException {
        for (int i = 0; i < names.size(); i++) {
            Object v = params.get(names.get(i));
            if (v instanceof BigDecimal || v instanceof Number || v instanceof Boolean) {
                s.setObject(i + 1, v);
            } else if (v == null) {
                s.setObject(i + 1, null);
            } else {
                s.setString(i + 1, v.toString());                            // text stays text: an id such as 00123 is never a number
            }
        }
    }
}
