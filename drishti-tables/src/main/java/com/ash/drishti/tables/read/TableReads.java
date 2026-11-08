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
package com.ash.drishti.tables.read;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.answer.ModelAnswers;
import com.ash.drishti.tables.answer.TableAccess;
import com.ash.drishti.tables.catalog.Catalogue;
import com.ash.drishti.tables.def.ColumnDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.lang.LateBound;
import com.ash.drishti.tables.lang.Trees;
import com.ash.drishti.tables.preview.Preview;
import com.ash.drishti.tables.preview.RowEvaluator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads virtual tables for one request (docs/architecture/ARCHITECTURE.md section 5 and section 10): a pivot view, a model query, a Flight read, a Sutra
 * page. Each table is resolved to its root source (a table over a table reads its base's source, with the base's grain and filter and both
 * tables' columns, Catalogue#effective), the source's documents are read for the business date through {@link SourceRows} (as the reader
 * may see the source), and the table's rows are computed by the one computation the preview uses ({@link Preview#rows(TableDef,
 * Preview.Doc, RowEvaluator, Map)}).
 *
 * <p><b>Shared reads (V7).</b> Many tables may be rooted in one source ({@code trading.trade_summary} and {@code trading.trade_legs}, both
 * {@code from: { kind: trade }}): within one {@code TableReads} the source is read once per business date and known-at time, and every
 * table over it is computed from that one read; a table's rows are computed once per date too.
 *
 * <p><b>Dates (V11).</b> A read names a business date; none is the source's latest; a date the source does not hold is refused; an undated
 * source gives its current rows, dated today. {@code knownAt} is passed to a source that keeps history and refused by one that does not.
 *
 * <p>Not thread-safe: one per request. Bounded: a read of more than {@code maxRows} rows is refused in words, never cut short.
 */
public final class TableReads implements ModelAnswers.Rows {

    /** The most rows one table's read stages, unless the holder says otherwise ({@code drishti.tables.read-max-rows}). */
    public static final long DEFAULT_MAX_ROWS = 2_000_000L;

    /**
     * One table read for one reader.
     *
     * @param table the table as read (its effective definition)
     * @param date the business date read (today for an undated source)
     * @param columns the columns, built-ins first; hidden columns left out
     * @param types each column's type ({@code string}, {@code integer}, {@code decimal}, {@code boolean}, {@code date}, {@code timestamp})
     * @param rows the rows, masked values as the mask
     * @param masked the columns the reader sees masked
     * @param problems what could not be computed (the first of each place)
     */
    public record Read(TableDef table, LocalDate date, List<String> columns, List<String> types, List<List<Object>> rows, Set<String> masked,
            List<Problem> problems) {}

    private final Catalogue catalogue;
    private final SourceRows source;
    private final LateBound.Fx fx;
    private final long maxRows;
    private final Clock clock;
    private final Map<String, List<Preview.Doc>> documents = new HashMap<>();
    private final Map<String, List<List<Object>>> computed = new HashMap<>();
    private final Map<String, Problem> problems = new LinkedHashMap<>();
    private final AtomicInteger sourceReads = new AtomicInteger();
    private boolean rules = true;

    public TableReads(Catalogue catalogue, SourceRows source, LateBound.Fx fx, long maxRows, Clock clock) {
        this.catalogue = catalogue;
        this.source = source;
        this.fx = fx == null ? LateBound.Fx.NONE : fx;
        this.maxRows = maxRows <= 0 ? DEFAULT_MAX_ROWS : maxRows;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public TableReads(Catalogue catalogue, SourceRows source) {
        this(catalogue, source, LateBound.Fx.NONE, DEFAULT_MAX_ROWS, Clock.systemUTC());
    }

    /**
     * These reads without the tables' row rules (security off, as BI's dataset rules do not apply then); masks and hidden columns still do.
     */
    public TableReads rulesOff() {
        this.rules = false;
        return this;
    }

    /** How many times a source was read (the shared reads' measure: one per source, date and known-at time). */
    public int sourceReads() {
        return sourceReads.get();
    }

    /** The catalogue the reads resolve names in. */
    public Catalogue catalogue() {
        return catalogue;
    }

    /**
     * Reads {@code name} for {@code who} on {@code date} (null or blank: the latest) as known at {@code knownAt} (null: now), keeping the
     * rows that meet {@code where} (a Rachana condition over the table's columns, bare or as {@code @.X} or {@code $.X}; null for every row).
     */
    public Read read(String name, String date, Instant knownAt, ModelAnswers.Reader who, String where) {
        TableDef t = catalogue.effective(com.ash.drishti.tables.def.TableName.Ref.parse(name))
                .orElseThrow(() -> new ModelAnswers.Refused(TableCodes.REFERENCE, "no virtual table '" + name + "'"));
        Resolved r = resolve(t);
        LocalDate day = day(r, date);
        TableAccess access = TableAccess.of(t, who, !rules);
        if (access.none()) {
            throw new ModelAnswers.Refused(TableCodes.READ, "you may not read " + t.name() + ": none of its rules is for your roles");
        }
        Expr condition = where == null || where.isBlank() ? null : condition(where, t, access);
        List<String> all = Preview.columns(r.def());
        List<String> columns = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (!access.hidden().contains(all.get(i))) {
                columns.add(all.get(i));
                at.add(i);
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        Set<String> masked = new LinkedHashSet<>(access.masks());
        for (List<Object> row : computed(r, day, knownAt)) {
            DataNode node = node(all, row);
            if (!access.allows(node)) {
                continue;
            }
            if (condition != null && !Values.truthy(condition.eval(EvalContext.of(node, null).withRow(node, 0)))) {
                continue;
            }
            List<Object> out = new ArrayList<>(at.size());
            for (int i : at) {
                Object v = row.get(i);
                boolean mask = masked.contains(all.get(i)) || Values.masked(v) || DataNode.MASK.equals(v);
                if (mask) {
                    masked.add(all.get(i));                    // masked at the source for this reader: the whole column is
                }
                out.add(mask ? DataNode.MASK : v);
            }
            rows.add(out);
        }
        if (masked.size() > access.masks().size()) {
            for (List<Object> out : rows) {
                for (int k = 0; k < at.size(); k++) {
                    if (masked.contains(all.get(at.get(k)))) {
                        out.set(k, DataNode.MASK);
                    }
                }
            }
        }
        List<String> types = columns.stream().map(c -> type(t, c)).toList();
        return new Read(r.def(), day, columns, types, rows, java.util.Collections.unmodifiableSet(masked), List.copyOf(problems.values()));
    }

    /** {@link ModelAnswers.Rows}: the table's rows on {@code date} as computed, before any reader's rules (the model applies those). */
    @Override
    public List<DataNode> rows(TableDef table, LocalDate date) {
        Resolved r = resolve(table);
        LocalDate day = date == null ? day(r, null) : date;
        List<String> cols = Preview.columns(r.def());
        List<DataNode> out = new ArrayList<>();
        for (List<Object> row : computed(r, day, null)) {
            out.add(node(cols, row));
        }
        return out;
    }

    /** The business date a read of {@code name} gives for {@code date} (V11). */
    public LocalDate day(String name, String date) {
        TableDef t = catalogue.effective(com.ash.drishti.tables.def.TableName.Ref.parse(name))
                .orElseThrow(() -> new ModelAnswers.Refused(TableCodes.REFERENCE, "no virtual table '" + name + "'"));
        return day(resolve(t), date);
    }

    /** The business dates a table can be read on (its root source's), oldest first; empty for an undated source. */
    public List<LocalDate> dates(String name) {
        TableDef t = catalogue.effective(com.ash.drishti.tables.def.TableName.Ref.parse(name))
                .orElseThrow(() -> new ModelAnswers.Refused(TableCodes.REFERENCE, "no virtual table '" + name + "'"));
        return source.dates(resolve(t).root());
    }

    /** A column's type as the table declares it; the built-ins' own; {@code string} when not declared. */
    public static String type(TableDef t, String column) {
        switch (column) {
            case "business_date":
                return "date";
            case "index", "update_seq":
                return "integer";
            case "updated_at":
                return "timestamp";
            case "id":
                return "string";
            default:
                ColumnDef c = t.columns().get(column);
                return c == null || c.type() == null ? "string" : c.type();
        }
    }

    // ---- resolution -----------------------------------------------------------------------------------------------------------

    /** A table resolved to its root source: the definition whose grain, filter and columns are computed over that source's documents. */
    record Resolved(TableDef def, TableDef.Source root) {}

    Resolved resolve(TableDef t) {
        TableDef.Source root = t.from();
        String grain = t.grain();
        List<String> filters = new ArrayList<>();
        if (t.filter() != null) {
            filters.add(t.filter());
        }
        Set<String> seen = new LinkedHashSet<>();
        TableDef at = t;
        while (root != null && root.form() == TableDef.Source.Form.TABLE) {
            if (!seen.add(at.name())) {
                throw new ModelAnswers.Refused(TableCodes.CYCLE, "tables over tables come back to " + at.name());
            }
            String baseName = root.table();
            TableDef base = catalogue.table(baseName)
                    .orElseThrow(() -> new ModelAnswers.Refused(TableCodes.REFERENCE, "no table '" + baseName + "' under " + t.name()));
            if (grain == null) {
                grain = base.grain();
            }
            if (base.filter() != null) {
                filters.add(0, base.filter());
            }
            at = base;
            root = base.from();
        }
        if (root == null || root.form() != TableDef.Source.Form.KIND && root.form() != TableDef.Source.Form.LAKE) {
            String form = root == null ? "none" : root.form().name().toLowerCase(java.util.Locale.ROOT);
            throw new ModelAnswers.Refused(TableCodes.READ, t.name() + " reads a " + form + " source, which its runner computes: only tables over a"
                    + " kind or a lake table are read here");
        }
        String filter = filters.isEmpty() ? null : filters.size() == 1 ? filters.get(0)
                : String.join(" && ", filters.stream().map(f -> "(" + f + ")").toList());
        TableDef def = new TableDef(t.name(), t.was(), t.version(), t.about(), root, grain, filter, t.columns(), t.keys(), t.keepLatest(), t.says(),
                t.materialize(), t.asOf(), t.retention(), t.refresh(), t.rules(), t.hiddenFor(), t.tests());
        return new Resolved(def, root);
    }

    private LocalDate day(Resolved r, String date) {
        List<LocalDate> held = source.dates(r.root());
        if (held.isEmpty()) {
            return LocalDate.now(clock);
        }
        if (date == null || date.isBlank() || "latest".equalsIgnoreCase(date.strip())) {
            return held.get(held.size() - 1);
        }
        LocalDate day;
        try {
            day = LocalDate.parse(date.strip());
        } catch (DateTimeParseException e) {
            throw new ModelAnswers.Refused(TableCodes.READ, "not a date: '" + date + "' (write 2026-09-30)");
        }
        if (!held.contains(day)) {
            throw new ModelAnswers.Refused(TableCodes.READ, r.def().name() + " holds no data on " + day + ": its source's dates run from "
                    + held.get(0) + " to " + held.get(held.size() - 1));
        }
        return day;
    }

    // ---- the one read per source and date ------------------------------------------------------------------------------------

    private List<List<Object>> computed(Resolved r, LocalDate day, Instant knownAt) {
        String key = r.def().name() + "|" + day + "|" + knownAt;
        List<List<Object>> have = computed.get(key);
        if (have != null) {
            return have;
        }
        RowEvaluator eval = RowEvaluator.rachana(fx, this::lookup, day);
        List<List<Object>> out = new ArrayList<>();
        for (Preview.Doc d : documents(r, day, knownAt)) {
            for (List<Object> row : Preview.rows(r.def(), d, eval, problems)) {
                if (out.size() >= maxRows) {
                    throw new ModelAnswers.Refused(TableCodes.READ, r.def().name() + " has more than " + maxRows + " rows on " + day
                            + " (drishti.tables.read-max-rows): filter it, or read it materialised; a table is never cut short");
                }
                out.add(row);
            }
        }
        computed.put(key, out);
        return out;
    }

    private List<Preview.Doc> documents(Resolved r, LocalDate day, Instant knownAt) {
        TableDef.Source s = r.root();
        if (knownAt != null && !source.travels(s)) {
            throw new ModelAnswers.Refused(TableCodes.READ, r.def().name() + "'s source keeps no history: it cannot be read as known at " + knownAt);
        }
        boolean dated = !source.dates(s).isEmpty();
        String key = sourceKey(s) + "|" + (dated ? day : "current") + "|" + knownAt;
        List<Preview.Doc> have = documents.get(key);
        if (have != null) {
            return have;
        }
        List<Preview.Doc> docs = new ArrayList<>();
        sourceReads.incrementAndGet();
        source.read(s, dated ? day : null, knownAt, d -> {
            docs.add(dated || d.businessDate() != null ? d : new Preview.Doc(d.id(), day.toString(), d.doc()));
            return true;
        });
        documents.put(key, docs);
        return docs;
    }

    /** What makes two sources the same read: the form and its names, never the table over it. */
    static String sourceKey(TableDef.Source s) {
        return s.form() + "|" + s.kind() + "|" + s.pack() + "|" + s.connector() + "|" + s.lake() + "|" + s.table();
    }

    /** {@code related(table.COLUMN, on = key)}: the other table's row on the same date by its first key (else its id). */
    private DataNode lookup(String table, Object key) {
        TableDef other = catalogue.effective(com.ash.drishti.tables.def.TableName.Ref.parse(table)).orElse(null);
        if (other == null || key == null) {
            return null;
        }
        String k = other.keys().isEmpty() ? "id" : other.keys().get(0);
        for (DataNode row : rows(other, null)) {
            DataNode v = row.get(k);
            if (v != null && !v.isNull() && v.asText().equals(String.valueOf(key))) {
                return row;
            }
        }
        return null;
    }

    // ---- conditions -------------------------------------------------------------------------------------------------------------

    /** A caller's condition over the table's columns, checked to name only columns the reader may use. */
    private static Expr condition(String where, TableDef t, TableAccess access) {
        Expr e;
        try {
            e = Trees.parse(where);
        } catch (RuntimeException ex) {
            throw new ModelAnswers.Refused(TableCodes.READ, "the condition '" + where + "' is not Rachana: " + ex.getMessage());
        }
        List<String> names = new ArrayList<>();
        Trees.walk(e, n -> {
            String name = Trees.name(n);
            if (name != null && !name.isEmpty()) {
                names.add(name.replaceFirst("^[$@]\\.", ""));
            }
        });
        for (String n : names) {
            String column = n.contains(".") ? n.substring(0, n.indexOf('.')) : n;
            if (access.masks().contains(column) || access.hidden().contains(column)) {
                throw new ModelAnswers.Refused(TableCodes.READ, "the condition reads " + column + ", which you see masked: a masked column cannot filter");
            }
        }
        return e;
    }

    private static DataNode node(List<String> columns, List<Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            m.put(columns.get(i), row.get(i));
        }
        return DataNode.of(m);
    }
}
