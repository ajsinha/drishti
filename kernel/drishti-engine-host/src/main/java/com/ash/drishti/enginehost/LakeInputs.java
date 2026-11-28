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
package com.ash.drishti.enginehost;

import com.ash.drishti.api.ColumnSet;
import com.ash.engine.BatchSink;
import com.ash.engine.Control;
import com.ash.engine.Descriptor;
import com.ash.engine.EngineException;
import com.ash.engine.EngineException.Kind;
import com.ash.engine.Filter;
import com.ash.engine.GroupScan;
import com.ash.engine.InputTable;
import com.ash.engine.Inputs;
import com.ash.engine.Scan;
import com.ash.engine.VectorScan;
import com.ash.engine.VectorSink;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * A session's inputs (engine specification 5.2), read through the host's {@link KindData} (EN-40) for one snapshot and, per viewer, as one
 * caller: the site's column map applied (the engine sees the contract's names), the caller's row rule on each kind and the site's
 * {@code where} applied before delivery, masked fields delivered as null. Columns are read once per session as arrays by reference
 * ({@link ColumnBatch}); a grouped scan is answered here, after the rules ({@link GroupedScan}). A kind the caller may not open is not
 * present for them. Scans stop between batches when the request is cancelled.
 */
final class LakeInputs implements Inputs {

    /** A cancellation point. */
    @FunctionalInterface
    interface Check {
        void run() throws EngineException;
    }

    /** The control of the request running on this thread (set around each call into the engine), for scans to stop when it is cancelled. */
    static final ThreadLocal<Control> CURRENT = new ThreadLocal<>();

    private final Descriptor descriptor;
    private final Map<String, InputBinder.Bound> bound;
    private final KindData data;
    private final ViewerAccess viewer;
    private final LocalDate date;
    private final Map<String, RowRule> wheres;
    private final AtomicLong rowsIn;
    private final Map<String, Table> tables = new ConcurrentHashMap<>();

    /**
     * @param viewer the caller (per-viewer isolation), or null for a shared session (no caller's rule applied; the engine filters by the
     *     request's visibility)
     * @param wheres each input's compiled {@code where}, by input name
     * @param rowsIn counts the rows delivered (the host's stats)
     */
    LakeInputs(Descriptor descriptor, Map<String, InputBinder.Bound> bound, KindData data, ViewerAccess viewer, LocalDate date,
            Map<String, RowRule> wheres, AtomicLong rowsIn) {
        this.descriptor = descriptor;
        this.bound = bound;
        this.data = data;
        this.viewer = viewer;
        this.date = date;
        this.wheres = wheres;
        this.rowsIn = rowsIn;
    }

    @Override
    public Set<String> names() {
        return descriptor.inputs().keySet();
    }

    @Override
    public InputTable table(String name) {
        Descriptor.Input in = descriptor.inputs().get(name);
        if (in == null) {
            throw new IllegalArgumentException(name + " is not an input of " + descriptor.id() + "; its inputs are " + descriptor.inputs().keySet());
        }
        return tables.computeIfAbsent(name, n -> new Table(n, in, bound.get(n), in.columns()));
    }

    @Override
    public InputTable table(String input, String table) {
        Descriptor.Input in = descriptor.inputs().get(input);
        if (in == null || !in.open()) {
            throw new IllegalArgumentException(input + " is not an open input of " + descriptor.id());
        }
        InputBinder.Bound b = bound.get(input);
        InputBinder.Bound one = new InputBinder.Bound(input, in.contract(), List.of(table), 0, "named by the request",
                b == null ? Map.of() : b.columns(), b == null ? null : b.where());
        return tables.computeIfAbsent(input + ":" + table, n -> new Table(input + ":" + table, in, one, List.of()));
    }

    /** The stamps of the tables read so far and of every bound input: what the answer's stamp names (input, or input:table, to stamp). */
    SortedMap<String, String> stamps() {
        SortedMap<String, String> out = new TreeMap<>();
        for (String name : descriptor.inputs().keySet()) {
            if (!descriptor.inputs().get(name).open()) {
                out.put(name, table(name).stamp());
            }
        }
        tables.forEach((k, t) -> {
            if (k.contains(":")) {
                out.put(k, t.stamp());
            }
        });
        return out;
    }

    private static void check() throws EngineException {
        Control c = CURRENT.get();
        if (c != null) {
            c.check();
        }
    }

    /** One input's rows for the snapshot, as the caller may read them. */
    private final class Table implements InputTable {

        private final String name;
        private final Descriptor.Input input;
        private final InputBinder.Bound bound;
        private final List<String> declared;
        private final List<String> kinds;
        private volatile Loaded loaded;
        private final Map<List<String>, Loaded> openLoads = new ConcurrentHashMap<>();

        Table(String name, Descriptor.Input input, InputBinder.Bound bound, List<String> declared) {
            this.name = name;
            this.input = input;
            this.bound = bound;
            this.declared = declared;
            List<String> k = bound == null ? List.of() : bound.kinds();
            this.kinds = viewer == null ? k : k.stream().filter(viewer::mayOpen).toList();   // a kind the caller may not open is not there for them
        }

        /** The rows read once: every contract column by its name, the rows the rules let through. */
        private record Loaded(Map<String, ColumnBatch.Column> columns, int size, int[] visible, String stamp, boolean present) {

            int count() {
                return visible == null ? size : visible.length;
            }
        }

        private RowRule rule(String kind) {
            return viewer == null ? RowRule.ALL : viewer.rows(kind);
        }

        private RowRule where() {
            return wheres.getOrDefault(input.open() ? name.substring(0, name.indexOf(':')) : name, RowRule.ALL);
        }

        private Loaded load() throws EngineException {
            if (!input.open()) {
                Loaded l = loaded;
                if (l == null) {
                    synchronized (this) {
                        l = loaded;
                        if (l == null) {
                            l = read(declared);
                            loaded = l;
                        }
                    }
                }
                return l;
            }
            return null;
        }

        private Loaded openLoad(List<String> columns) throws EngineException {
            List<String> key = List.copyOf(new TreeSet<>(columns));
            Loaded l = openLoads.get(key);
            if (l == null) {
                l = read(key);
                openLoads.put(key, l);
            }
            return l;
        }

        /**
         * True when the engine declares the input not per date ({@code per: []}: an official-records history, the desk P&amp;L, a
         * parameter file by version) and the binding reads it plainly: every date the kind holds is read, the engine selects by date.
         */
        private boolean whole() {
            return input.per() != null && input.per().isEmpty() && !bound.onOrBefore() && bound.field() == null;
        }

        /** Reads {@code contractColumns} of every kind of the input (their union), applies each kind's rule and the site's where. */
        private Loaded read(List<String> contractColumns) throws EngineException {
            if (kinds.isEmpty()) {
                return new Loaded(Map.of(), 0, null, "absent", false);
            }
            RowRule where = where();
            List<ColumnSet> sets = new ArrayList<>();
            List<RowRule> rules = new ArrayList<>();
            StringBuilder stamp = new StringBuilder();
            for (String kind : kinds) {
                check();
                RowRule rule = rule(kind);
                Set<String> paths = new LinkedHashSet<>();
                for (String c : contractColumns) {
                    if (!c.equals(input.vector())) {                   // the vector column is read with vectors(), never as a column
                        paths.add(bound.column(c));
                    }
                }
                paths.addAll(rule.fields());
                paths.addAll(where.fields());
                java.util.NavigableSet<LocalDate> held = whole() ? data.dates(kind).orElse(null) : null;
                if (held != null && !held.isEmpty()) {                 // per: [] (not per date): every date the kind holds, oldest first
                    for (LocalDate d : held) {
                        check();
                        Optional<ColumnSet> set = data.columns(kind, paths, d);
                        if (set.isPresent()) {
                            sets.add(set.get());
                            rules.add(rule);
                        }
                    }
                    stamp.append(stamp.length() == 0 ? "" : ";").append(kind).append('=').append(held.size()).append(" dates to ")
                            .append(held.last()).append(':').append(data.stamp(kind, held.last()).orElse("?"));
                    continue;
                }
                Optional<ColumnSet> set = bound.onOrBefore() || bound.field() != null
                        ? data.columns(kind, paths, date, bound.onOrBefore(), bound.field()) : data.columns(kind, paths, date);
                stamp.append(stamp.length() == 0 ? "" : ";").append(kind).append('=').append(data.stamp(kind, date).orElse(set.isPresent() ? "?" : "absent"));
                if (set.isPresent()) {
                    sets.add(set.get());
                    rules.add(rule);
                }
            }
            if (sets.isEmpty()) {
                return new Loaded(Map.of(), 0, null, stamp.toString(), false);
            }
            ColumnSet all = ColumnSet.concat(sets);
            int size = all.size();
            int[] visible = null;
            boolean filtered = !where.all() || rules.stream().anyMatch(r -> !r.all());
            if (filtered) {
                int[] keep = new int[size];
                int n = 0;
                int offset = 0;
                for (int s = 0; s < sets.size(); s++) {
                    RowRule rule = rules.get(s);
                    int end = offset + sets.get(s).size();
                    for (int i = offset; i < end; i++) {
                        int row = i;
                        Function<String, Object> value = p -> all.value(p, row);
                        if ((rule.all() || rule.test(value)) && (where.all() || where.test(value))) {
                            keep[n++] = i;
                        }
                    }
                    offset = end;
                }
                visible = Arrays.copyOf(keep, n);
            }
            Map<String, ColumnBatch.Column> cols = new LinkedHashMap<>();
            String idKey = input.key().size() == 1 ? input.key().get(0) : null;
            for (String c : contractColumns) {
                String path = bound.column(c);
                boolean masked = viewer != null && (viewer.masked(path) || viewer.masked(c));
                if (all.numbers().containsKey(path)) {
                    cols.put(c, new ColumnBatch.Column(all.numbers().get(path), null, null, masked));
                } else if (all.texts().containsKey(path)) {
                    cols.put(c, new ColumnBatch.Column(null, all.texts().get(path), null, masked));
                } else if (all.mixed().containsKey(path)) {
                    cols.put(c, new ColumnBatch.Column(null, null, all.mixed().get(path), masked));
                } else if (c.equals(idKey)) {
                    cols.put(c, new ColumnBatch.Column(null, all.ids(), null, masked));   // the key is the entity's id
                }
            }
            return new Loaded(java.util.Collections.unmodifiableMap(cols), size, visible, stamp.toString(), true);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean present() {
            try {
                return input.open() ? !kinds.isEmpty() && kinds.stream().anyMatch(data::serves) : load().present();
            } catch (EngineException e) {
                return false;
            }
        }

        @Override
        public Set<String> columns() {
            try {
                if (input.open()) {
                    return Set.of();
                }
                Loaded l = load();
                if (!l.present()) {
                    return Set.of();
                }
                LinkedHashSet<String> cols = new LinkedHashSet<>(l.columns().keySet());
                if (input.vector() != null) {
                    cols.add(input.vector());                          // read with vectors(); there when the table is
                }
                return java.util.Collections.unmodifiableSet(cols);
            } catch (EngineException e) {
                return Set.of();
            }
        }

        @Override
        public String stamp() {
            if (input.open()) {
                StringBuilder s = new StringBuilder();
                kinds.forEach(k -> s.append(s.length() == 0 ? "" : ";").append(k).append('=').append(data.stamp(k, date).orElse("?")));
                return s.length() == 0 ? "absent" : s.toString();
            }
            try {
                return load().stamp();
            } catch (EngineException e) {
                return "unread";
            }
        }

        @Override
        public long rows() {
            Loaded l = loaded;
            return l == null ? -1 : l.count();
        }

        private Loaded forScan(List<String> columns) throws EngineException {
            if (!input.open()) {
                for (String c : columns) {
                    if (!declared.contains(c)) {
                        throw new EngineException("ENG-2003", Kind.REFUSED, c + " is not a declared column of " + name + "; it declares " + declared);
                    }
                }
                return load();
            }
            return openLoad(columns);
        }

        @Override
        public void scan(Scan scan, BatchSink sink) throws EngineException {
            Loaded l = forScan(scan.columns());
            if (!l.present()) {
                return;
            }
            int[] rows = narrowed(l, scan.where());
            int total = rows == null ? l.size() : rows.length;
            int size = scan.batchRows() <= 0 ? 4_096 : scan.batchRows();
            ColumnBatch b = new ColumnBatch(l.columns(), scan.columns());
            if (rows == null && total <= size) {
                b.at(null, total);                                   // the whole input in one batch: the reader's arrays, by reference
                sink.batch(b);
                rowsIn.addAndGet(total);
                return;
            }
            int[] all = rows;
            if (all == null) {
                all = new int[total];
                Arrays.setAll(all, i -> i);
            }
            int[] window = new int[Math.min(size, Math.max(total, 1))];
            for (int from = 0; from < total; from += size) {
                check();
                int n = Math.min(size, total - from);
                System.arraycopy(all, from, window, 0, n);
                b.at(window, n);
                sink.batch(b);
                rowsIn.addAndGet(n);
            }
        }

        /** The rows of {@code l} the scan's own filter (over scope columns) lets through; null: every visible row is every row. */
        private int[] narrowed(Loaded l, Filter where) {
            if (where == null || where instanceof Filter.All) {
                return l.visible();
            }
            int n = l.count();
            int[] keep = new int[n];
            int k = 0;
            for (int i = 0; i < n; i++) {
                int row = l.visible() == null ? i : l.visible()[i];
                if (where.test(c -> l.columns().getOrDefault(c, ColumnBatch.Column.ABSENT).text(row))) {
                    keep[k++] = row;
                }
            }
            return Arrays.copyOf(keep, k);
        }

        @Override
        public void vectors(VectorScan scan, VectorSink sink) throws EngineException {
            if (input.vector() == null || !input.vector().equals(scan.column())) {
                throw new EngineException("ENG-2003", Kind.REFUSED, scan.column() + " is not the vector column of " + name);
            }
            RowRule where = where();
            List<String> asked = scan.texts();
            for (String kind : kinds) {
                check();
                RowRule rule = rule(kind);
                List<String> texts = new ArrayList<>();
                Set<String> held = data.columnar(kind);
                // a text the source does not keep as a column is not asked (the vectors then come by column, the text reads as null;
                // a key column the source keeps as the entity's id is the row's id)
                asked.forEach(c -> texts.add(held.isEmpty() || held.contains(bound.column(c)) ? bound.column(c) : "\u0000absent:" + c));
                Set<String> extra = new LinkedHashSet<>(rule.fields());
                extra.addAll(where.fields());
                scan.where().columns().forEach(c -> extra.add(bound.column(c)));
                extra.removeAll(texts);
                texts.addAll(extra);
                Map<String, Integer> at = new LinkedHashMap<>();
                for (int i = 0; i < texts.size(); i++) {
                    at.putIfAbsent(texts.get(i), i);
                }
                String[] out = new String[asked.size()];
                boolean[] masked = new boolean[asked.size()];
                for (int i = 0; i < asked.size(); i++) {
                    masked[i] = viewer != null && (viewer.masked(bound.column(asked.get(i))) || viewer.masked(asked.get(i)));
                }
                List<String> readable = texts.stream().filter(t -> !t.startsWith("\u0000absent:")).toList();
                int[] slot = new int[texts.size()];
                for (int i = 0, r = 0; i < texts.size(); i++) {
                    slot[i] = texts.get(i).startsWith("\u0000absent:") ? -1 : r++;
                }
                try {
                    data.vectors(kind, bound.column(scan.column()), readable, date, (id, values, length, got) -> {
                        String[] row = new String[texts.size()];
                        for (int i = 0; i < row.length; i++) {
                            row[i] = slot[i] < 0 ? null : got[slot[i]];
                        }
                        Function<String, Object> value = p -> {
                            Integer i = at.get(p);
                            return i == null ? null : row[i];
                        };
                        if (!rule.all() && !rule.test(value) || !where.all() && !where.test(value)
                                || !scan.where().test(c -> (String) value.apply(bound.column(c)))) {
                            return;
                        }
                        for (int i = 0; i < out.length; i++) {
                            out[i] = masked[i] ? null : row[i];
                        }
                        rowsIn.incrementAndGet();
                        sink.row(id, values, length, out);
                    });
                } catch (EngineException e) {
                    throw e;
                } catch (Exception e) {
                    throw new EngineException("ENG-2010", Kind.UNAVAILABLE, kind + " could not be read for " + name + ": " + e.getMessage(), Map.of(), e);
                }
            }
        }

        @Override
        public boolean offers(GroupScan scan) {
            return !input.open() && present() && GroupedScan.offers(scan, new java.util.HashSet<>(declared));
        }

        @Override
        public void groups(GroupScan scan, BatchSink sink) throws EngineException {
            if (!offers(scan)) {
                throw new EngineException("ENG-2003", Kind.REFUSED, "a grouped scan that was not offered was asked of " + name);
            }
            Loaded l = load();
            GroupedScan.run(scan, l.columns(), l.visible(), l.size(), sink, LakeInputs::check);
            rowsIn.addAndGet(l.count());
        }
    }
}
