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
package com.ash.drishti.bicompute.lang;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.rachana.el.CheckedMode;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.el.check.Checker;
import com.ash.drishti.rachana.el.check.ElType;
import com.ash.drishti.rachana.el.check.Fields;
import com.ash.drishti.rachana.el.check.Scope;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * The differential harness of the one compiler (docs/architecture/BI_DESIGN.md 2.6 and 7.1): one generated table held twice, as DuckDB rows and
 * as Rachana documents, with the edges that make two evaluators disagree (nulls, empty and mixed-case text, zero divisors, negative
 * numbers, dates across a year end, a masked column). An expression is checked by Rachana, compiled once, then evaluated by the Java
 * form ({@link RowsEvaluator}) and by the SQL form ({@link ScanEvaluator}); the two must agree value for value (numbers to 1e-9
 * relative, Q26; everything else exactly; a masked value against SQL's null with the row marked masked).
 */
final class Differential implements AutoCloseable {

    /** The table's typed columns as checked mode sees them; SECRET is masked for the reader. */
    static final Map<String, ElType> TYPES = new LinkedHashMap<>();

    static {
        TYPES.put("ID", ElType.INTEGER);
        TYPES.put("MTM", ElType.DECIMAL);
        TYPES.put("NOTIONAL", ElType.DECIMAL);
        TYPES.put("N", ElType.INTEGER);
        TYPES.put("STATUS", ElType.STRING);
        TYPES.put("DESK", ElType.STRING);
        TYPES.put("CCY", ElType.STRING);
        TYPES.put("TRADE_DATE", ElType.DATE);
        TYPES.put("MATURITY", ElType.DATE);
        TYPES.put("LIVE", ElType.BOOLEAN);
        TYPES.put("SECRET", ElType.DECIMAL);
    }

    static final LocalDate AS_OF = LocalDate.of(2026, 10, 16);

    final Connection db;
    final List<DataNode> rows = new ArrayList<>();
    final Compiler compiler;
    final RowsEvaluator memory;
    final ScanEvaluator scan;

    Differential() throws SQLException {
        db = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement s = db.createStatement()) {
            s.execute("CREATE TABLE t (ID BIGINT, MTM DOUBLE, NOTIONAL DOUBLE, N BIGINT, STATUS VARCHAR, DESK VARCHAR, CCY VARCHAR, "
                    + "TRADE_DATE DATE, MATURITY DATE, LIVE BOOLEAN, SECRET DOUBLE)");
            Random r = new Random(2911);
            String[] statuses = {"LIVE", "live", "MATURED", "", null, "Pending"};
            String[] desks = {"DESK-RATES", "DESK-COMM", "DESK-FX", null};
            String[] ccys = {"USD", "EUR", "JPY", "GBP", null};
            for (int i = 0; i < 48; i++) {
                Double mtm = i % 7 == 3 ? null : i % 11 == 5 ? 0.0 : Math.round((r.nextDouble() * 2_000_000 - 1_000_000) * 100) / 100.0;
                Double notional = i % 13 == 4 ? null : Math.round(r.nextDouble() * 50_000_000) / 1.0;
                Long n = i % 9 == 2 ? null : i % 10 == 0 ? 0L : (long) (r.nextInt(41) - 20);
                LocalDate trade = i % 8 == 6 ? null : LocalDate.of(2025, 12, 20).plusDays(r.nextInt(40));
                LocalDate maturity = i % 12 == 7 ? null : LocalDate.of(2026, 1, 1).plusDays(r.nextInt(3000));
                Boolean live = i % 6 == 1 ? null : r.nextBoolean();
                add(s, i, mtm, notional, n, statuses[i % statuses.length], desks[i % desks.length], ccys[i % ccys.length], trade, maturity, live,
                        i * 1.5);
            }
        }
        SqlLowering.Columns cols = new SqlLowering.Columns() {
            @Override
            public String sql(List<String> parts) {
                return "t.\"" + column(parts) + "\"";
            }

            @Override
            public boolean masked(List<String> parts) {
                return "SECRET".equals(column(parts));
            }

            @Override
            public boolean date(List<String> parts) {
                return TYPES.get(column(parts)) == ElType.DATE;
            }

            @Override
            public boolean table(String name) {
                return "trades".equals(name);
            }
        };
        compiler = new Compiler(cols, new SqlLowering.Options(AS_OF, null));
        memory = new RowsEvaluator(EvalContext.of(DataNode.missing(), null).withToday(AS_OF));
        scan = new ScanEvaluator("t");
    }

    /** {@code [MTM]}, {@code [trades, MTM]} and {@code [$, MTM]} all name the column MTM. */
    static String column(List<String> parts) {
        String c = parts.get(parts.size() - 1);
        if (parts.size() > 2 || parts.size() == 2 && !List.of("trades", "$").contains(parts.get(0)) || !TYPES.containsKey(c)) {
            throw new IllegalArgumentException("no field " + String.join(".", parts));
        }
        return c;
    }

    /** What checked mode may name: the table's columns, bare, as trades.X and as $.X; the table trades; SECRET masked. */
    static final Fields FIELDS = new Fields() {
        @Override
        public Optional<Field> field(List<String> parts) {
            try {
                String c = column(parts);
                return Optional.of(new Field(String.join(".", parts), TYPES.get(c), "SECRET".equals(c)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }

        @Override
        public boolean alias(String name) {
            return "trades".equals(name);
        }

        @Override
        public Optional<String> measure(String nameOrId) {
            return Optional.empty();
        }
    };

    private void add(Statement s, int id, Double mtm, Double notional, Long n, String status, String desk, String ccy, LocalDate trade,
            LocalDate maturity, Boolean live, double secret) throws SQLException {
        s.execute("INSERT INTO t VALUES (" + id + ", " + sql(mtm) + ", " + sql(notional) + ", " + sql(n) + ", " + sql(status) + ", " + sql(desk)
                + ", " + sql(ccy) + ", " + (trade == null ? "NULL" : "DATE '" + trade + "'") + ", " + (maturity == null ? "NULL" : "DATE '" + maturity + "'")
                + ", " + sql(live) + ", " + secret + ")");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ID", id);
        m.put("MTM", mtm);
        m.put("NOTIONAL", notional);
        m.put("N", n);
        m.put("STATUS", status);
        m.put("DESK", desk);
        m.put("CCY", ccy);
        m.put("TRADE_DATE", trade == null ? null : trade.toString());
        m.put("MATURITY", maturity == null ? null : maturity.toString());
        m.put("LIVE", live);
        String json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(m).toString();
        DataNode plain = new JsonCodec().read(json);
        Map<String, DataNode> fields = new LinkedHashMap<>(((DataNode.Obj) plain).fields());
        fields.put("SECRET", DataNode.masked());
        DataNode own = new DataNode.Obj(fields);
        Map<String, DataNode> doc = new LinkedHashMap<>(fields);
        doc.put("trades", own);
        rows.add(new DataNode.Obj(doc));
    }

    private static String sql(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof String s) {
            return "'" + s.replace("'", "''") + "'";
        }
        return v.toString();
    }

    /** Checks the row expression, compiles it and returns the rows on which the two forms disagree (empty: they agree). */
    List<String> rowDifferences(String text) throws SQLException {
        Checker.Checked c = CheckedMode.check(text, Scope.ROW, FIELDS);
        assertThat(c.problems().stream().filter(p -> p.error())).as(text).isEmpty();
        Lowered.Row row = compiler.row(CheckedMode.parse(text).expr());
        List<Object> sql = scan.rows(db, row, "ID");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Object j = memory.row(row, rows.get(i));
            Object q = sql.get(i);
            if (Values.masked(j)) {
                if (q != null || !row.masked()) {
                    out.add("row " + i + ": Java masked, SQL " + q);
                }
            } else if (!same(j, q)) {
                out.add("row " + i + ": Java " + j + " (" + (j == null ? "" : j.getClass().getSimpleName()) + "), SQL " + q + " ("
                        + (q == null ? "" : q.getClass().getSimpleName()) + ")");
            }
        }
        return out;
    }

    /** Checks the measure, compiles it, and returns its Java and SQL values. */
    Object[] measure(String text) throws SQLException {
        Checker.Checked c = CheckedMode.check(text, Scope.AGGREGATE, FIELDS);
        assertThat(c.problems().stream().filter(p -> p.error())).as(text).isEmpty();
        Lowered l = compiler.measure(CheckedMode.parse(text).expr());
        return new Object[] {memory.measure(l, rows, Map.of()), scan.measure(db, l, Map.of())};
    }

    /** Two values the same: nulls, numbers to 1e-9 relative, texts and booleans exactly, timestamps by their instant. */
    static boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Number x && b instanceof Number y) {
            double p = x.doubleValue();
            double q = y.doubleValue();
            if (Double.isNaN(p) || Double.isNaN(q)) {
                return Double.isNaN(p) && Double.isNaN(q);
            }
            return p == q || Math.abs(p - q) <= 1e-9 * Math.max(Math.abs(p), Math.abs(q));
        }
        if (a instanceof BigDecimal || b instanceof BigDecimal) {
            return false;
        }
        String s = a.toString();
        String t = b.toString();
        if (s.matches("\\d{4}-\\d{2}-\\d{2}T.*") && t.matches("\\d{4}-\\d{2}-\\d{2}T.*")) {
            return java.time.LocalDateTime.parse(s).equals(java.time.LocalDateTime.parse(t));
        }
        return s.equals(t);
    }

    @Override
    public void close() throws SQLException {
        db.close();
    }
}
