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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.DuckDbEngine;
import com.ash.drishti.server.bi.mq.ModelFields;
import com.ash.drishti.server.bi.mq.ModelQuery;
import com.ash.drishti.server.bi.mq.MqException;
import com.ash.drishti.server.bi.mq.MqParser;
import com.ash.drishti.server.bi.mq.MqService;
import com.ash.drishti.server.bi.plan.Explain;
import com.ash.drishti.server.bi.plan.LogicalPlan;
import com.ash.drishti.server.bi.plan.PlanNode;
import com.ash.drishti.server.bi.plan.Planner;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Drishti SQL over a model (docs/architecture/BI_DESIGN.md section 3.4): parse ({@link DsqlParser}), bind as the reader ({@link SqlBinder}), and answer.
 * An MQ-shaped statement is exactly one model query: same plan, same plan hash, same cache entry as the MQ (and the report visual) asking the same; its
 * answer's columns are renamed and ordered as the {@code SELECT} says. A composite statement's leaves are model queries answered as the reader, loaded into an
 * in-memory DuckDB that can read nothing else (no files, no network, no extensions), where the outer query runs; a join, filter, sort or partition on a column
 * masked for the reader is refused ({@code DRS-9161}). Thread-safe; one in-memory engine per composite statement.
 */
public final class SqlService {

    /** A statement bound: MQ-shaped ({@code leaf}) or composite. */
    public record Compiled(String sql, SqlAst.Statement statement, SqlBinder.Leaf leaf, SqlComposer.Composite composite) {
        public String shape() {
            return leaf != null ? "mq" : "composite";
        }

        public boolean explain() {
            return statement.explain();
        }

        /** The model queries of the statement, in order. */
        public List<ModelQuery> queries() {
            return leaf != null ? List.of(leaf.query()) : composite.leaves().stream().map(l -> l.leaf().query()).toList();
        }
    }

    /** An answer: the JSON body, and the columns for Arrow or CSV. */
    public record Answer(Map<String, Object> body, List<ArrowIpcWriter.Column> columns, int rows) {}

    private final MqService mq;
    private final SqlProperties props;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("drishti-bi-sql-watchdog").unstarted(r));

    public SqlService(MqService mq, SqlProperties props) {
        this.mq = mq;
        this.props = props;
    }

    public SqlProperties properties() {
        return props;
    }

    /** Parses and binds a statement for the bound reader; a refusal is a {@link DsqlException} (or the model query's own {@link MqException}). */
    public Compiled compile(MqService.Bound b, String ref, String sql) {
        SqlAst.Statement st = DsqlParser.parse(sql);
        SqlBinder binder = new SqlBinder(b.view().flat(), ModelFields.of(b, null, b.context().roles()), ref);
        if (!SqlComposer.composite(st.query())) {
            return new Compiled(sql, st, binder.leaf((SqlAst.Select) st.query().body()), null);
        }
        SqlComposer.Composite c = new SqlComposer(binder).compose(st.query());
        if (c.leaves().size() > props.maxLeaves()) {
            throw new DsqlException(DsqlException.SHAPE, "the statement asks " + c.leaves().size() + " model queries; at most " + props.maxLeaves() + " (drishti.bi.sql.max-leaves)", null, 0);
        }
        return new Compiled(sql, st, null, oneTime(c));
    }

    /** One time for the whole statement (section 3.4): a time clause given once applies to every leaf; two different ones are refused. */
    private static SqlComposer.Composite oneTime(SqlComposer.Composite c) {
        SqlAst.TimeClause time = null;
        for (SqlComposer.LeafUse l : c.leaves()) {
            SqlAst.TimeClause t = l.leaf().time();
            if (t == null) {
                continue;
            }
            if (time != null && !same(time, t)) {
                throw new DsqlException(DsqlException.TIME, "two different times in one statement (a comparison of two dates is DEL as_of, wave B)", "one AS OF / FOR clause for the statement",
                        t.at());
            }
            time = t;
        }
        if (time == null) {
            return c;
        }
        List<SqlComposer.LeafUse> leaves = new ArrayList<>();
        for (SqlComposer.LeafUse l : c.leaves()) {
            ModelQuery q = l.leaf().query();
            ModelQuery timed = new ModelQuery(q.mq(), q.model(), q.define(), q.fields(), q.measures(), q.where(), q.having(), SqlBinder.time(time), q.sort(), q.limit(), q.totals(),
                    q.drill(), q.shape(), q.params(), q.options());
            leaves.add(new SqlComposer.LeafUse(l.table(), new SqlBinder.Leaf(timed, l.leaf().columns(), l.leaf().offset(), time)));
        }
        return new SqlComposer.Composite(leaves, c.sql(), c.params(), c.sensitive(), c.shape());
    }

    private static boolean same(SqlAst.TimeClause a, SqlAst.TimeClause b) {
        return a.kind().equals(b.kind()) && java.util.Objects.equals(a.asOf(), b.asOf()) && java.util.Objects.equals(a.from(), b.from()) && java.util.Objects.equals(a.to(), b.to())
                && java.util.Objects.equals(a.last(), b.last());
    }

    /** The plan of a compiled statement (the model query's for an MQ-shaped one; relational operators over the leaves' plans for a composite). */
    public LogicalPlan plan(MqService.Bound b, Compiled c) {
        if (c.leaf() != null) {
            return mq.plan(b, c.leaf().query());
        }
        List<PlanNode> leaves = new ArrayList<>();
        String time = "current";
        for (SqlComposer.LeafUse l : c.composite().leaves()) {
            LogicalPlan p = mq.plan(b, l.leaf().query());
            leaves.add(p.root());
            time = p.time();
        }
        PlanNode root = new PlanNode.Rel(c.composite().shape(), leaves, Map.of("sql", c.composite().sql()));
        String id = b.view().def().id() == null || b.view().def().id().isBlank() ? b.view().id() : b.view().def().id();
        return new LogicalPlan(root, id, b.view().hash(), time, List.of(), Planner.ids(b.view().ids()));
    }

    /** Checks a statement without reading data: valid, its shape, the model queries, the output columns, the plan, or the problems. */
    public Map<String, Object> validate(MqService.Bound b, String ref, String sql) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            Compiled c = compile(b, ref, sql);
            LogicalPlan plan = plan(b, c);
            m.put("valid", true);
            m.put("shape", c.shape());
            m.put("queries", c.queries().stream().map(MqParser::canonical).toList());
            m.put("columns", c.leaf() != null ? c.leaf().columns().stream().map(o -> Map.of("name", o.name(), "source", o.source(), "kind", o.measure() ? "measure" : "field")).toList()
                    : List.of());
            m.put("plan", plan.hash());
            m.put("signature", b.signature());
            m.put("problems", List.of());
        } catch (DsqlException e) {
            m.put("valid", false);
            m.put("problems", List.of(e.toMap()));
        } catch (MqException e) {
            m.put("valid", false);
            m.put("problems", e.problems().stream().map(p -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("code", p.code());
                x.put("level", "error");
                x.put("message", p.detail());
                x.put("fix", p.fix());
                x.put("path", p.path());
                return x;
            }).toList());
        }
        return m;
    }

    /** {@code EXPLAIN}: the plan (tree and text), the model queries, and a composite's outer SQL; nothing is read. */
    public Map<String, Object> explain(MqService.Bound b, Compiled c) {
        LogicalPlan plan = plan(b, c);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sql", 1);
        m.put("explain", true);
        m.put("shape", c.shape());
        m.put("plan", plan.hash());
        m.put("signature", b.signature());
        m.put("queries", c.queries().stream().map(MqParser::canonical).toList());
        if (c.composite() != null) {
            m.put("outer", c.composite().sql());
        }
        m.put("tree", plan.tree());
        m.put("text", Explain.text(plan, b.signature()));
        return m;
    }

    // ---- answers -----------------------------------------------------------------------------------------------------

    /**
     * A statement's answer as an engine result (a SQL view dataset's rows, a model's SQL test): at {@code date} when the statement names no time. Masked
     * columns are listed in the result's meta.
     */
    @SuppressWarnings("unchecked")
    public com.ash.drishti.server.bi.engine.QueryService.Result answer(MqService.Bound b, String model, String sql, java.time.LocalDate date) {
        Compiled c;
        try {
            c = compile(b, model, sql);
        } catch (DsqlException e) {
            throw e.toMq();
        }
        if (date != null) {
            c = at(c, date);
        }
        Answer a = run(b, c);
        List<String> masked = new ArrayList<>();
        for (Map<String, Object> col : (List<Map<String, Object>>) a.body().get("columns")) {
            if (Boolean.TRUE.equals(col.get("masked"))) {
                masked.add(String.valueOf(col.get("name")));
            }
        }
        Map<String, Object> meta = (Map<String, Object>) a.body().get("meta");
        List<String> warnings = new ArrayList<>((List<String>) meta.getOrDefault("warnings", List.of()));
        return new com.ash.drishti.server.bi.engine.QueryService.Result(a.columns(), a.rows(), new com.ash.drishti.server.bi.engine.QueryService.Meta("sql", null, a.rows(), 0,
                Boolean.TRUE.equals(meta.get("cached")), List.of(), List.of(), masked, warnings, null, 0));
    }

    /** The statement at a date: every model query that names no time asks as of it. */
    private static Compiled at(Compiled c, java.time.LocalDate date) {
        ModelQuery.Time t = new ModelQuery.Time(date, null, null, null, false);
        java.util.function.UnaryOperator<ModelQuery> dated = q -> q.time().equals(ModelQuery.Time.DEFAULT) ? new ModelQuery(q.mq(), q.model(), q.define(), q.fields(), q.measures(),
                q.where(), q.having(), t, q.sort(), q.limit(), q.totals(), q.drill(), q.shape(), q.params(), q.options()) : q;
        if (c.leaf() != null) {
            SqlBinder.Leaf l = c.leaf();
            return new Compiled(c.sql(), c.statement(), new SqlBinder.Leaf(dated.apply(l.query()), l.columns(), l.offset(), l.time()), null);
        }
        List<SqlComposer.LeafUse> leaves = c.composite().leaves().stream().map(l -> new SqlComposer.LeafUse(l.table(), new SqlBinder.Leaf(dated.apply(l.leaf().query()),
                l.leaf().columns(), l.leaf().offset(), l.leaf().time()))).toList();
        SqlComposer.Composite x = c.composite();
        return new Compiled(c.sql(), c.statement(), null, new SqlComposer.Composite(leaves, x.sql(), x.params(), x.sensitive(), x.shape()));
    }

    public Answer run(MqService.Bound b, Compiled c) {
        return c.leaf() != null ? leaf(b, c.leaf()) : composite(b, c.composite());
    }

    @SuppressWarnings("unchecked")
    private Answer leaf(MqService.Bound b, SqlBinder.Leaf leaf) {
        MqService.Answer a = mq.answer(b, mq.run(b, leaf.query(), false, false));
        Map<String, Object> body = a.body();
        List<Map<String, Object>> cols = (List<Map<String, Object>>) body.get("columns");
        List<List<Object>> rows = (List<List<Object>>) body.get("rows");
        int[] index = new int[leaf.columns().size()];
        List<Map<String, Object>> outCols = new ArrayList<>();
        List<ArrowIpcWriter.Column> arrow = new ArrayList<>();
        int skip = (int) Math.min(rows.size(), leaf.offset());
        for (int i = 0; i < index.length; i++) {
            SqlBinder.Out o = leaf.columns().get(i);
            index[i] = indexOf(cols, o.source());
            Map<String, Object> cm = new LinkedHashMap<>(cols.get(index[i]));
            cm.put("name", o.name());
            cm.put("source", o.source());
            outCols.add(cm);
            ArrowIpcWriter.Column src = a.result().columns().get(index[i]);
            Object[] vals = java.util.Arrays.copyOfRange(src.values(), skip, a.result().rows());
            arrow.add(new ArrowIpcWriter.Column(o.name(), src.kind(), vals));
        }
        List<List<Object>> outRows = new ArrayList<>();
        for (List<Object> r : rows.subList(skip, rows.size())) {
            List<Object> row = new ArrayList<>(index.length);
            for (int k : index) {
                row.add(r.get(k));
            }
            outRows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sql", 1);
        out.put("model", body.get("model"));
        out.put("columns", outCols);
        out.put("rows", outRows);
        out.put("rowCount", outRows.size());
        Map<String, Object> meta = new LinkedHashMap<>((Map<String, Object>) body.get("meta"));
        meta.put("shape", "mq");
        out.put("meta", meta);
        return new Answer(out, arrow, outRows.size());
    }

    private static int indexOf(List<Map<String, Object>> cols, String name) {
        for (int i = 0; i < cols.size(); i++) {
            if (name.equals(cols.get(i).get("name"))) {
                return i;
            }
        }
        throw new DrishtiException(ErrorCode.QUERY_ENGINE, "the model query's answer has no column '" + name + "'");
    }

    @SuppressWarnings("unchecked")
    private Answer composite(MqService.Bound b, SqlComposer.Composite c) {
        long t0 = System.nanoTime();
        List<Map<String, Object>> answers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Object> plans = new ArrayList<>();
        boolean cached = true;
        for (SqlComposer.LeafUse l : c.leaves()) {
            Answer a = leaf(b, l.leaf());
            answers.add(a.body());
            Map<String, Object> meta = (Map<String, Object>) a.body().get("meta");
            ((List<String>) meta.getOrDefault("warnings", List.of())).forEach(w -> warnings.add(l.table() + ": " + w));
            plans.add(meta.get("plan"));
            cached &= Boolean.TRUE.equals(meta.get("cached"));
            for (Map<String, Object> col : (List<Map<String, Object>>) a.body().get("columns")) {
                if (Boolean.TRUE.equals(col.get("masked")) && c.sensitive().getOrDefault(l.table(), Set.of()).contains(String.valueOf(col.get("name")))) {
                    throw new MqException(MqException.Problem.of("DRS-9161", "sql", "'" + col.get("name") + "' is masked for you: a join, filter, sort or partition on it is refused"));
                }
            }
        }
        List<ArrowIpcWriter.Column> cols;
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement s = conn.createStatement()) {
                s.execute("SET memory_limit = '" + props.compositeMemory().replace("'", "") + "'");
                s.execute("SET threads = 1");
                s.execute("SET autoinstall_known_extensions = false");
                s.execute("SET autoload_known_extensions = false");
                s.execute("SET enable_external_access = false");                 // the outer query reads the leaves' answers, nothing else
                s.execute("SET lock_configuration = true");
            }
            for (int i = 0; i < c.leaves().size(); i++) {
                load(conn, c.leaves().get(i).table(), answers.get(i));
            }
            try (PreparedStatement ps = conn.prepareStatement(c.sql())) {
                for (int i = 0; i < c.params().size(); i++) {
                    ps.setObject(i + 1, c.params().get(i));
                }
                ScheduledFuture<?> cancel = watchdog.schedule(() -> {
                    try {
                        ps.cancel();
                    } catch (SQLException ignored) {
                        // finished already
                    }
                }, props.compositeTimeout().toMillis(), TimeUnit.MILLISECONDS);
                try (ResultSet rs = ps.executeQuery()) {
                    cols = DuckDbEngine.read(rs, props.maxRows() + 1);
                } finally {
                    cancel.cancel(false);
                }
            }
        } catch (SQLException e) {
            throw new DrishtiException(ErrorCode.QUERY_ENGINE, "the composite statement could not run over its answers: " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
        }
        int n = cols.isEmpty() ? 0 : cols.get(0).values().length;
        if (n > props.maxRows()) {
            throw new DrishtiException(ErrorCode.QUERY_ROWS, "the statement answers more than " + props.maxRows() + " rows (drishti.bi.sql.max-rows): add a LIMIT");
        }
        List<Map<String, Object>> outCols = new ArrayList<>();
        for (ArrowIpcWriter.Column col : cols) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", col.name());
            cm.put("type", switch (col.kind()) {
                case TEXT -> "string";
                case INT64 -> "integer";
                case FLOAT64 -> "decimal";
            });
            cm.put("masked", maskedOut(col.name(), answers));
            outCols.add(cm);
        }
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<Object> row = new ArrayList<>(cols.size());
            for (ArrowIpcWriter.Column col : cols) {
                row.add(col.values()[i]);
            }
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sql", 1);
        out.put("model", answers.isEmpty() ? null : answers.get(0).get("model"));
        out.put("columns", outCols);
        out.put("rows", rows);
        out.put("rowCount", n);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("shape", "composite");
        meta.put("leaves", plans);
        meta.put("signature", b.signature());
        meta.put("cached", cached);
        meta.put("warnings", warnings);
        meta.put("millis", Math.round((System.nanoTime() - t0) / 1e5) / 10.0);
        out.put("meta", meta);
        return new Answer(out, cols, n);
    }

    /** True when an output column takes its name from a masked column of a leaf (the composite says so; its values are already masked). */
    @SuppressWarnings("unchecked")
    private static boolean maskedOut(String name, List<Map<String, Object>> answers) {
        for (Map<String, Object> a : answers) {
            for (Map<String, Object> col : (List<Map<String, Object>>) a.get("columns")) {
                if (name.equals(col.get("name")) && Boolean.TRUE.equals(col.get("masked"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A leaf's answer as a table of the in-memory engine. */
    @SuppressWarnings("unchecked")
    private static void load(Connection conn, String table, Map<String, Object> answer) throws SQLException {
        List<Map<String, Object>> cols = (List<Map<String, Object>>) answer.get("columns");
        List<List<Object>> rows = (List<List<Object>>) answer.get("rows");
        List<String> defs = new ArrayList<>();
        for (Map<String, Object> c : cols) {
            String type = switch (String.valueOf(c.get("type"))) {
                case "integer" -> "BIGINT";
                case "decimal" -> "DOUBLE";
                case "boolean" -> "BOOLEAN";
                default -> "VARCHAR";
            };
            if (Boolean.TRUE.equals(c.get("masked")) && "field".equals(c.get("kind"))) {
                type = "VARCHAR";                                               // a masked field reads as text
            }
            defs.add(SqlComposer.q(String.valueOf(c.get("name"))) + " " + type);
        }
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE " + SqlComposer.q(table) + " (" + String.join(", ", defs) + ")");
        }
        if (rows.isEmpty()) {
            return;
        }
        String marks = String.join(", ", java.util.Collections.nCopies(cols.size(), "?"));
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO " + SqlComposer.q(table) + " VALUES (" + marks + ")")) {
            for (List<Object> r : rows) {
                for (int i = 0; i < r.size(); i++) {
                    Object v = r.get(i);
                    ps.setObject(i + 1, v instanceof java.time.temporal.Temporal ? v.toString() : v);
                }
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
