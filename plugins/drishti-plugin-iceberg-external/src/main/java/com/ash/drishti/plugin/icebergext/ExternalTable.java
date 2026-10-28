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
package com.ash.drishti.plugin.icebergext;

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.expr.Expr;
import com.ash.drishti.iceberg.meta.PartitionSpec;
import com.ash.drishti.iceberg.meta.Snapshot;
import com.ash.drishti.iceberg.meta.Transform;
import com.ash.drishti.iceberg.read.JsonValues;
import com.ash.drishti.iceberg.read.TaskReader;
import com.ash.drishti.iceberg.scan.FileTask;
import com.ash.drishti.iceberg.scan.Pruning;
import com.ash.drishti.iceberg.types.NestedField;
import com.ash.drishti.iceberg.types.Type;
import com.ash.drishti.iceberg.types.Values;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * One kind read from an external Iceberg table (written by Spark, Flink, Trino, Snowflake, Glue, dbt, pyiceberg, in any
 * layout) through drishti-iceberg. A snapshot is planned once and grouped by business date ({@link Layout}); a file's
 * date comes from its partition when the partition is the date (identity of a date column, or {@code day()} of a
 * timestamp in UTC), else from its date column's bounds, else from reading that column; such files are <em>inexact</em>
 * (they may hold rows of several days) and their rows are checked. One entity is found by planning-style pruning of
 * the day's files with the id (partition values, column bounds, then Parquet row-group statistics), which reads one row
 * group of a table sorted or bucketed by the id; for an unsorted table, by the day's id index ({@link IdIndex}).
 * Deletes (merge-on-read position and equality deletes) are applied by every read. Thread-safe.
 */
final class ExternalTable {

    static final ObjectMapper JSON = new ObjectMapper();

    /** One business date's files: each with whether all its rows are of that date. */
    record Day(LocalDate date, List<FileTask> files, boolean[] exact, long rows, long fingerprint) {}

    /** A snapshot's shape and dates (MIN alone for a current-only kind). */
    record Layout(long snapshotId, long timestampMs, long sequenceNumber, Shape shape, NavigableMap<LocalDate, Day> days) {}

    /** How the values of the document come out (ICEBERG_EXTERNAL_CONNECTOR.md section 3). */
    private final JsonValues.Options options;
    private final KindConfig cfg;
    private final Table table;
    private final ExecutorService pool;

    ExternalTable(KindConfig cfg, Table table, ExecutorService pool, JsonValues.Options options) {
        this.cfg = cfg;
        this.table = table;
        this.pool = pool;
        this.options = options;
    }

    KindConfig config() {
        return cfg;
    }

    Table table() {
        return table;
    }

    Optional<Long> refresh() {
        table.refresh();
        return current();
    }

    Optional<Long> current() {
        return table.currentSnapshot().map(Snapshot::snapshotId);
    }

    Optional<Long> snapshotAt(long ms) {
        return table.metadata().snapshotAsOf(ms).map(Snapshot::snapshotId);
    }

    private Snapshot snapshot(long id) {
        Optional<Snapshot> s = table.metadata().snapshot(id);
        if (s.isEmpty()) {
            table.refresh();
            s = table.metadata().snapshot(id);
        }
        return s.orElseThrow(() -> new IllegalStateException(cfg.table() + " has no snapshot " + id + " (expired?)"));
    }

    Layout layout(long snapshotId) {
        Snapshot s = snapshot(snapshotId);
        Shape shape = Shape.of(cfg, table.metadata(), table.metadata().schemaFor(s));
        List<FileTask> tasks = table.plan(s, Expr.TRUE, pool);
        Map<LocalDate, List<FileTask>> files = new TreeMap<>();
        Map<LocalDate, List<Boolean>> exact = new TreeMap<>();
        for (FileTask t : tasks) {
            for (Map.Entry<LocalDate, Boolean> d : datesOf(t, shape).entrySet()) {
                files.computeIfAbsent(d.getKey(), k -> new ArrayList<>()).add(t);
                exact.computeIfAbsent(d.getKey(), k -> new ArrayList<>()).add(d.getValue());
            }
        }
        NavigableMap<LocalDate, Day> days = new TreeMap<>();
        files.forEach((date, fs) -> {
            long rows = 0;
            long fp = 1125899906842597L ^ date.toEpochDay();
            boolean[] ex = new boolean[fs.size()];
            for (int i = 0; i < fs.size(); i++) {
                FileTask f = fs.get(i);
                ex[i] = exact.get(date).get(i);
                rows += f.file().recordCount();
                fp = 31 * fp + f.file().path().hashCode();
                for (var d : f.deletes()) {
                    fp = 31 * fp + d.path().hashCode();
                }
            }
            days.put(date, new Day(date, List.copyOf(fs), ex, rows, fp ^ ((long) fs.size() << 48)));
        });
        return new Layout(snapshotId, s.timestampMs(), s.sequenceNumber(), shape, days);
    }

    /** The business dates a file may hold, each with whether every row of the file is of it. */
    private Map<LocalDate, Boolean> datesOf(FileTask t, Shape shape) {
        Map<LocalDate, Boolean> out = new TreeMap<>();
        NestedField date = shape.dateField();
        if (date == null) {
            out.put(LocalDate.MIN, true);
            return out;
        }
        PartitionSpec spec = t.spec();
        for (int i = 0; i < spec.fields().size(); i++) {
            PartitionSpec.Field f = spec.fields().get(i);
            Object v = i < t.file().partition().length ? t.file().partition()[i] : null;
            if (f.sourceId() != date.id() || v == null) {
                continue;
            }
            boolean day = f.transform() instanceof Transform.Identity && !shape.dateTimestamp()
                    || f.transform() instanceof Transform.Time tt && tt.unit() == Transform.TimeUnit.DAY;
            boolean utcExact = !shape.dateTimestamp() || date.type().id() == Type.Id.TIMESTAMP || "UTC".equals(cfg.dateZone().getId())
                    || "Z".equals(cfg.dateZone().getId());
            if (day && utcExact) {
                out.put(LocalDate.ofEpochDay(((Number) v).longValue()), true);
                return out;
            }
        }
        byte[] lo = t.file().lowerBounds().get(date.id());
        byte[] hi = t.file().upperBounds().get(date.id());
        if (lo != null && hi != null) {
            Type.Primitive p = (Type.Primitive) date.type();
            LocalDate a = shape.dateOf(Values.fromBytes(p, lo), cfg);
            LocalDate b = shape.dateOf(Values.fromBytes(p, hi), cfg);
            if (a.equals(b)) {
                out.put(a, true);
                return out;
            }
            if (!b.isBefore(a) && a.plusDays(400).isAfter(b)) {
                for (LocalDate d = a; !d.isAfter(b); d = d.plusDays(1)) {
                    out.put(d, false);
                }
                return out;
            }
        }
        // no usable bounds: the file's own dates, from its date column alone
        try (TaskReader r = table.rows(t, shape.schema(), List.of(date.id()), Expr.TRUE, false)) {
            while (r.hasNext()) {
                LocalDate d = shape.dateOf(r.next()[0], cfg);
                if (d != null) {
                    out.put(d, false);
                }
            }
        }
        if (out.size() == 1) {
            out.replaceAll((k, v) -> true);
        }
        return out;
    }

    /**
     * Reads one file of the day: the top-level columns {@code ids} of each live row of the day (an inexact file's rows
     * of other dates skipped), row groups pruned by {@code filter}.
     */
    void scan(Layout l, Day day, int file, List<Integer> ids, Expr filter, Consumer<Object[]> sink) {
        Shape s = l.shape();
        boolean check = !day.exact()[file] && s.dateField() != null;
        List<Integer> read = new ArrayList<>(ids);
        int dateSlot = -1;
        if (check) {
            dateSlot = read.indexOf(s.dateField().id());
            if (dateSlot < 0) {
                dateSlot = read.size();
                read.add(s.dateField().id());
            }
        }
        try (TaskReader r = table.rows(day.files().get(file), s.schema(), read, filter, filter != Expr.TRUE)) {
            while (r.hasNext()) {
                Object[] row = r.next();
                if (check && !day.date().equals(s.dateOf(row[dateSlot], cfg))) {
                    continue;
                }
                sink.accept(row);
            }
        }
    }

    /** Reads each of the day's files on its own virtual thread, in file order. */
    <T> List<T> perFile(Day day, java.util.function.IntFunction<T> read) {
        List<Future<T>> running = new ArrayList<>();
        for (int i = 0; i < day.files().size(); i++) {
            int f = i;
            running.add(pool.submit(() -> read.apply(f)));
        }
        List<T> out = new ArrayList<>();
        try {
            for (Future<T> f : running) {
                out.add(f.get());
            }
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ ids

    /** An id part as text: strings as they are, numbers plainly, dates ISO, others as their JSON text. */
    static String text(Type t, Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof BigDecimal d) {
            return d.toPlainString();
        }
        JsonNode n = JsonValues.value(t, v, new JsonValues.Options(true, false));
        return n == null ? "" : n.isTextual() ? n.asText() : n.toString();
    }

    /** A row's id (its id columns at {@code slots}, joined by the separator). */
    String idOf(Shape s, Object[] row, int[] slots) {
        if (slots.length == 1) {
            return text(s.idFields().get(0).type(), row[slots[0]]);
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < slots.length; i++) {
            if (i > 0) {
                b.append(cfg.idSeparator());
            }
            b.append(text(s.idFields().get(i).type(), row[slots[i]]));
        }
        return b.toString();
    }

    /** The id's parts as the id columns' values (for pruning), or empty when the id cannot be one of this kind's. */
    Optional<List<Object>> parse(Shape s, String id) {
        List<NestedField> fields = s.idFields();
        String[] parts = fields.size() == 1 ? new String[] {id} : id.split(java.util.regex.Pattern.quote(cfg.idSeparator()), -1);
        if (parts.length != fields.size()) {
            return Optional.empty();
        }
        List<Object> out = new ArrayList<>();
        try {
            for (int i = 0; i < parts.length; i++) {
                Type t = fields.get(i).type();
                out.add(switch (t.id()) {
                    case STRING -> parts[i];
                    case INT -> Integer.parseInt(parts[i]);
                    case LONG -> Long.parseLong(parts[i]);
                    case DATE -> (int) LocalDate.parse(parts[i]).toEpochDay();
                    case DECIMAL -> new BigDecimal(parts[i]).setScale(((Type.Primitive) t).scale());
                    case UUID -> UUID.fromString(parts[i]);
                    case BOOLEAN -> Boolean.parseBoolean(parts[i]);
                    default -> parts[i];                     // compared as text after reading
                });
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return Optional.of(out);
    }

    /** The comparisons that pick an id's rows (for pruning). */
    Expr idFilter(Shape s, List<Object> values) {
        List<Expr> preds = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Type.Id t = s.idFields().get(i).type().id();
            if (t == Type.Id.STRING || t == Type.Id.INT || t == Type.Id.LONG || t == Type.Id.DATE) {
                preds.add(Expr.eq(s.idFields().get(i).id(), values.get(i)));
            }
        }
        return Expr.and(preds.toArray(new Expr[0]));
    }

    /** The day's files that may hold the id, by their partitions and column bounds (indexes into the day's files). */
    List<Integer> candidates(Layout l, Day day, Expr filter) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < day.files().size(); i++) {
            FileTask t = day.files().get(i);
            var projected = Pruning.project(filter, t.spec(), l.shape().schema());
            if (Pruning.partition(t.file().partition(), projected, t.spec().partitionType(l.shape().schema()))
                    && Pruning.metrics(t.file(), filter, l.shape().schema())) {
                out.add(i);
            }
        }
        return out;
    }

    /** Every id of the day with the files it is in: what the id index and type-ahead are built from. */
    Map<String, int[]> ids(Layout l, Day day, long maxRows) {
        Shape s = l.shape();
        List<Integer> cols = s.idFields().stream().map(NestedField::id).toList();
        int[] slots = new int[cols.size()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = i;
        }
        List<List<String>> perFile = perFile(day, f -> {
            List<String> ids = new ArrayList<>();
            scan(l, day, f, cols, Expr.TRUE, r -> {
                if (ids.size() < maxRows) {
                    ids.add(idOf(s, r, slots));
                }
            });
            return ids;
        });
        Map<String, int[]> out = new HashMap<>();
        for (int f = 0; f < perFile.size(); f++) {
            for (String id : perFile.get(f)) {
                int file = f;
                out.merge(id, new int[] {f}, (a, b) -> {
                    for (int x : a) {
                        if (x == file) {
                            return a;
                        }
                    }
                    int[] c = java.util.Arrays.copyOf(a, a.length + 1);
                    c[a.length] = file;
                    return c;
                });
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ documents

    /** The top-level columns a document is read from. */
    List<Integer> documentColumns(Shape s) {
        List<Integer> out = new ArrayList<>();
        if (s.documentField() != null) {
            out.add(s.documentField().id());
        } else {
            s.documentColumns().forEach(f -> out.add(f.id()));
        }
        for (NestedField f : s.idFields()) {
            if (!out.contains(f.id())) {
                out.add(f.id());
            }
        }
        return out;
    }

    /** A row (read with {@link #documentColumns}) as the kind's JSON document. */
    String document(Shape s, Object[] row) {
        if (s.documentField() != null) {
            return row[0] == null ? null : row[0].toString();
        }
        ObjectNode doc = JSON.createObjectNode();
        List<NestedField> cols = s.documentColumns();
        for (int i = 0; i < cols.size(); i++) {
            JsonNode v = JsonValues.value(cols.get(i).type(), row[i], options);
            if (v != null) {
                doc.set(cfg.nameOf(cols.get(i).name()), v);
            }
        }
        return doc.toString();
    }

    /**
     * One entity's document from the day: from the files {@code files} (pruned by the id, or named by the id index),
     * each read with the id's filter so only row groups that can hold it are decoded.
     */
    Optional<String> fetch(Layout l, Day day, String id, List<Object> values, List<Integer> files) {
        Shape s = l.shape();
        List<Integer> cols = documentColumns(s);
        int[] slots = new int[s.idFields().size()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = cols.indexOf(s.idFields().get(i).id());
        }
        Expr filter = idFilter(s, values);
        String[] found = new String[1];
        for (int f : files) {
            scan(l, day, f, cols, filter, r -> {
                if (found[0] == null && id.equals(idOf(s, r, slots))) {
                    found[0] = document(s, r);
                }
            });
            if (found[0] != null) {
                break;
            }
        }
        return Optional.ofNullable(found[0]);
    }

    /** Every document of a day (small tables: reverse lookups without links), id to JSON. */
    Map<String, String> read(Layout l, Day day, long maxRows) {
        Shape s = l.shape();
        List<Integer> cols = documentColumns(s);
        int[] slots = new int[s.idFields().size()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = cols.indexOf(s.idFields().get(i).id());
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int f = 0; f < day.files().size() && out.size() < maxRows; f++) {
            scan(l, day, f, cols, Expr.TRUE, r -> out.putIfAbsent(idOf(s, r, slots), document(s, r)));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ columns

    /** Whether a searchable field is read as numbers. */
    static boolean numeric(Type.Primitive t) {
        return switch (t.id()) {
            case INT, LONG, FLOAT, DOUBLE, DECIMAL -> true;
            default -> false;
        };
    }

    /**
     * The ids and the asked searchable fields of every row of the day, read from their top-level columns only.
     *
     * @return per row: its id and each field's value (a Double, or text)
     */
    List<Object[]> columns(Layout l, Day day, List<String> paths, long maxRows) {
        Shape s = l.shape();
        List<Integer> cols = new ArrayList<>(s.idFields().stream().map(NestedField::id).toList());
        int[] idSlots = new int[cols.size()];
        for (int i = 0; i < idSlots.length; i++) {
            idSlots[i] = i;
        }
        int[] slot = new int[paths.size()];
        List<Shape.Path> where = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            Shape.Path p = s.searchable().get(paths.get(i));
            where.add(p);
            int at = cols.indexOf(p.column().id());
            if (at < 0) {
                at = cols.size();
                cols.add(p.column().id());
            }
            slot[i] = at;
        }
        List<List<Object[]>> perFile = perFile(day, f -> {
            List<Object[]> rows = new ArrayList<>();
            scan(l, day, f, cols, Expr.TRUE, r -> {
                if (rows.size() >= maxRows) {
                    return;
                }
                Object[] out = new Object[paths.size() + 1];
                out[0] = idOf(s, r, idSlots);
                for (int i = 0; i < paths.size(); i++) {
                    Shape.Path p = where.get(i);
                    Object v = Shape.at(r, slot[i], p);
                    out[i + 1] = v == null ? null : numeric(p.type()) ? (Object) ((Number) v).doubleValue() : text(p.type(), v);
                }
                rows.add(out);
            });
            return rows;
        });
        List<Object[]> all = new ArrayList<>();
        perFile.forEach(all::addAll);
        return all;
    }

    /** Parses a document column's JSON (for messages and tests). */
    static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The searchable paths that name kinds of other entities (links), after renaming. */
    Set<String> linked() {
        return cfg.links().keySet();
    }
}
