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
package com.ash.drishti.server.bi.dataset;

import com.ash.drishti.server.bi.dataset.DatasetParser.Problem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Composed and derived datasets (RUPAKA.md section 20.10, docs/guides/DATASETS.md section 11): a dataset that is not read from a connector but built from the
 * answers of other datasets. A <em>composed</em> dataset asks each source a question ({@code from:}), joins the answers ({@code join:}) and has measures of its own
 * over the joined columns. A <em>derived</em> dataset hands the answers to a Python Transform and takes the frame it returns as its one table. Either way the
 * checked result is an ordinary {@link Dataset} with one table of columns, so reports, alerts, subscriptions and governance treat it like any other; the engine
 * builds the table's rows at query time, as the reader.
 *
 * <p>This class resolves the sources by name through a {@link Directory} (installed by the BI service: the stored and shipped datasets), types the columns of the
 * frame from the sources' models, and checks: sources exist, columns and measures exist, join keys are of one type, a join that repeats rows is warned about
 * ({@code DRS-9015}), and a dataset that reads itself, directly or through others, is refused ({@code DRS-9016}).
 */
public final class Composition {

    static final String COMPOSE = "DRS-9014";
    static final String FANOUT = "DRS-9015";
    static final String CYCLE = "DRS-9016";
    static final String DERIVED = "DRS-9017";
    /** The description of a column of a derived dataset that was learned from a result, not declared: a later result lacking it is not an error. */
    public static final String LEARNED = "column of the Transform's last result";

    /** A dataset found by name: the catalogue id it is stored under and the text readers see. */
    public record Found(String id, String body) {}

    /** Where sources are looked up. Implemented by the BI service over the stored and shipped dataset items. */
    public interface Directory {

        /** The dataset a source names: by catalogue id, file name, the dataset's own name or title. */
        Optional<Found> find(String ref);

        /** The columns (name to type) the last successful run of a derived dataset's Transform returned, or empty when it never ran. */
        default Map<String, String> learned(String datasetName) {
            return Map.of();
        }
    }

    private static final List<Directory> DIRECTORIES = new CopyOnWriteArrayList<>();
    private static final ThreadLocal<Deque<String>> EXPANDING = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private Composition() {}

    /** Makes a directory available to every parse (a server has one; a test may install its own). The newest answers first. */
    public static void install(Directory d) {
        DIRECTORIES.add(0, d);
    }

    public static void uninstall(Directory d) {
        DIRECTORIES.remove(d);
    }

    static Optional<Found> find(String ref) {
        for (Directory d : DIRECTORIES) {
            Optional<Found> f = d.find(ref);
            if (f.isPresent()) {
                return f;
            }
        }
        return Optional.empty();
    }

    static Map<String, String> learned(String name) {
        for (Directory d : DIRECTORIES) {
            Map<String, String> m = d.learned(name);
            if (!m.isEmpty()) {
                return m;
            }
        }
        return Map.of();
    }

    /** The {@code dataset:} name of a dataset text without checking it (cheap, never recursive); empty when there is none. */
    public static String declaredName(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return "";
        }
        try {
            JsonNode n = YAML.readTree(yaml);
            return n != null && n.path("dataset").isTextual() ? n.path("dataset").asText() : "";
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "";
        }
    }

    /** True when the text declares {@code from:} or {@code derived:}, whose model depends on other datasets (so a cached parse of it can go stale). */
    public static boolean dependsOnOthers(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return false;
        }
        try {
            JsonNode n = YAML.readTree(yaml);
            return n != null && (n.has("from") || n.has("derived"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return false;
        }
    }

    // ---- expansion ---------------------------------------------------------------------------------------------------

    /**
     * Builds the table of a composed or derived dataset from the models of its sources and checks it.
     *
     * @param ds the dataset as parsed: {@code compose} or {@code derived} set, no tables
     * @param declared for a derived dataset, the columns the YAML declares (name to type), possibly empty
     * @return the dataset with its one table; the problems found are added to {@code out}
     */
    static Dataset expand(Dataset ds, Map<String, String> declared, List<Problem> out) {
        Deque<String> stack = EXPANDING.get();
        if (stack.contains(ds.name())) {
            List<String> path = new ArrayList<>(stack);
            java.util.Collections.reverse(path);
            out.add(error(CYCLE, "from", "dataset '" + ds.name() + "' reads itself through " + String.join(" -> ", path) + " -> " + ds.name() + ": a dataset cannot be built from itself"));
            return ds;
        }
        stack.push(ds.name());
        try {
            return ds.composed() ? composed(ds, out) : derived(ds, declared, out);
        } finally {
            stack.pop();
        }
    }

    /** A source resolved: its model and the frame columns its question gives. */
    private record Resolved(Dataset.Input input, Dataset model, List<Dataset.FrameColumn> columns, boolean grouped) {}

    private static Resolved resolve(String path, Dataset.Input in, List<Problem> out) {
        Optional<Found> found = find(in.dataset());
        if (found.isEmpty()) {
            out.add(error(COMPOSE, path + ".dataset", "no dataset '" + in.dataset() + "' (a catalogue id, a file name such as trades-basic, or the dataset's name)"));
            return null;
        }
        DatasetParser.Result r = DatasetParser.parse(found.get().body());
        if (r.dataset() == null || !r.ok()) {
            boolean cycle = r.problems().stream().anyMatch(p -> CYCLE.equals(p.code()));
            String why = r.problems().stream().filter(Problem::error).findFirst().map(p -> p.code() + " " + p.path() + ": " + p.message()).orElse("it cannot be read");
            out.add(error(cycle ? CYCLE : COMPOSE, path + ".dataset", "source dataset '" + in.dataset() + "' has errors, so nothing can be built from it: " + why));
            return null;
        }
        Dataset model = r.dataset();
        Resolver resolver = new Resolver(model);
        List<Dataset.FrameColumn> cols = new ArrayList<>();
        boolean ok = true;
        List<Dataset.Named> fieldsOrBy = in.rows() ? in.fields() : in.by();
        String which = in.rows() ? "fields" : "by";
        for (int i = 0; i < fieldsOrBy.size(); i++) {
            Dataset.Named n = fieldsOrBy.get(i);
            try {
                Resolver.ColRef ref = resolver.resolve(List.of(n.source().split("\\.", -1)), null);
                if (ref.column() == null) {
                    out.add(error(COMPOSE, path + "." + which + "[" + i + "]", "'" + n.source() + "' is a table; name a column (table.column)"));
                    ok = false;
                    continue;
                }
                String type = ref.part() != null ? "integer" : resolver.column(ref.table(), ref.column()).type();
                cols.add(new Dataset.FrameColumn(frameName(n, ref.text()), in.alias(), n.source(), type, false, null));
            } catch (IllegalArgumentException e) {
                out.add(error(COMPOSE, path + "." + which + "[" + i + "]", "dataset '" + in.dataset() + "': " + e.getMessage()));
                ok = false;
            }
        }
        for (int i = 0; i < in.measures().size(); i++) {
            Dataset.Named n = in.measures().get(i);
            Dataset.Measure m = model.measures().get(n.source());
            if (m == null) {
                out.add(error(COMPOSE, path + ".measures[" + i + "]", "dataset '" + in.dataset() + "' has no measure '" + n.source() + "' (measures: " + String.join(", ", model.measures().keySet()) + ")"));
                ok = false;
            } else if (m.expr() == null) {
                out.add(error(COMPOSE, path + ".measures[" + i + "]", "'" + n.source() + "' is a Python measure, which a query cannot compute: use a measure with an expression"));
                ok = false;
            } else {
                cols.add(new Dataset.FrameColumn(n.as() == null ? n.source() : n.as(), in.alias(), n.source(), "decimal", true, null));
            }
        }
        if (in.rows() && !in.measures().isEmpty()) {
            out.add(error(COMPOSE, path + ".measures", "an input of `fields` is a list of rows and has no measures; use `by` with `measures` for a grouped answer"));
            ok = false;
        }
        if (cols.isEmpty() && ok) {
            out.add(error(COMPOSE, path, "an input needs `by` and `measures` (a grouped answer) or `fields` (rows)"));
            ok = false;
        }
        for (int i = 0; i < in.filters().size(); i++) {
            Dataset.InputFilter f = in.filters().get(i);
            if (!model.measures().containsKey(f.field())) {
                try {
                    resolver.resolve(List.of(f.field().split("\\.", -1)), null);
                } catch (IllegalArgumentException e) {
                    out.add(error(COMPOSE, path + ".filters[" + i + "].field", "dataset '" + in.dataset() + "': " + e.getMessage()));
                    ok = false;
                }
            }
        }
        for (String k : in.key()) {
            if (cols.stream().noneMatch(c -> c.name().equals(k) && !c.measure())) {
                out.add(error(COMPOSE, path + ".key", "'" + k + "' is not one of the grouping columns of this source (" + String.join(", ", cols.stream().filter(c -> !c.measure()).map(Dataset.FrameColumn::name).toList()) + ")"));
                ok = false;
            }
        }
        Set<String> names = new LinkedHashSet<>();
        for (Dataset.FrameColumn c : cols) {
            if (!names.add(c.name())) {
                out.add(error(COMPOSE, path, "the name '" + c.name() + "' is used twice in this source: rename one with `as`"));
                ok = false;
            }
        }
        return ok ? new Resolved(in, model, cols, !in.rows()) : null;
    }

    private static String frameName(Dataset.Named n, String text) {
        return n.as() != null ? n.as() : text.substring(text.lastIndexOf('.') + 1);
    }

    // ---- composed ----------------------------------------------------------------------------------------------------

    private static Dataset composed(Dataset ds, List<Problem> out) {
        Dataset.Compose c = ds.compose();
        Map<String, Resolved> sources = new LinkedHashMap<>();
        for (Dataset.Input in : c.from().values()) {
            Resolved r = resolve("from." + in.alias(), in, out);
            if (r != null) {
                sources.put(in.alias(), r);
            }
        }
        if (c.from().isEmpty()) {
            out.add(error(COMPOSE, "from", "`from:` names the datasets to read: alias: { dataset, by, measures }"));
        }
        if (c.from().size() > 1 && c.joins().isEmpty()) {
            out.add(error(COMPOSE, "join", "several sources need `join:` to say how they meet: - { left: a, right: b, on: [field], type: inner }"));
        }
        Set<String> joined = new LinkedHashSet<>();
        if (!c.joins().isEmpty()) {
            joined.add(c.joins().get(0).left());
        } else if (!c.from().isEmpty()) {
            joined.add(c.from().keySet().iterator().next());
        }
        Map<String, String> folded = new LinkedHashMap<>();              // "leftAlias.key" -> right alias whose same-named key is folded into it
        Set<String> dropped = new LinkedHashSet<>();                     // "rightAlias.key": not a column of the frame
        for (int i = 0; i < c.joins().size(); i++) {
            Dataset.Join j = c.joins().get(i);
            String path = "join[" + i + "]";
            if (!c.from().containsKey(j.left()) || !c.from().containsKey(j.right())) {
                out.add(error(COMPOSE, path, "a join names sources of `from:` (" + String.join(", ", c.from().keySet()) + "), not '" + (c.from().containsKey(j.left()) ? j.right() : j.left()) + "'"));
                continue;
            }
            if (j.left().equals(j.right())) {
                out.add(error(COMPOSE, path, "a source cannot be joined to itself"));
                continue;
            }
            if (!joined.contains(j.left())) {
                out.add(error(COMPOSE, path + ".left", "'" + j.left() + "' is not joined yet: write the joins in order, each starting from a source already joined"));
                continue;
            }
            if (!joined.add(j.right())) {
                out.add(error(COMPOSE, path + ".right", "'" + j.right() + "' is already joined: a source is joined once"));
                continue;
            }
            if (j.on().isEmpty()) {
                out.add(error(COMPOSE, path + ".on", "name the fields to join on: on: [counterparty], or on: [\"a = b\"] when the two names differ"));
                continue;
            }
            if (!Dataset.Join.TYPES.contains(j.type())) {
                out.add(error(COMPOSE, path + ".type", "type is inner, left or full, not '" + j.type() + "'"));
            }
            Resolved l = sources.get(j.left());
            Resolved r = sources.get(j.right());
            if (l == null || r == null) {
                continue;                                                            // the source's own problem is already reported
            }
            Set<String> leftKeys = new LinkedHashSet<>();
            Set<String> rightKeys = new LinkedHashSet<>();
            boolean keysOk = true;
            for (String on : j.on()) {
                String[] sides = on.contains("=") ? on.split("=", 2) : new String[] {on, on};
                String lk = sides[0].trim();
                String rk = sides[1].trim();
                Dataset.FrameColumn lc = column(l, lk);
                Dataset.FrameColumn rc = column(r, rk);
                if (lc == null || rc == null) {
                    boolean leftMissing = lc == null;
                    out.add(error(COMPOSE, path + ".on", "'" + (leftMissing ? lk : rk) + "' is not a column of '" + (leftMissing ? j.left() : j.right()) + "' (its columns: "
                            + String.join(", ", (leftMissing ? l : r).columns().stream().map(Dataset.FrameColumn::name).toList()) + ")"));
                    keysOk = false;
                    continue;
                }
                if (lc.measure() || rc.measure()) {
                    out.add(error(COMPOSE, path + ".on", "'" + (lc.measure() ? lk : rk) + "' is a measure: a join is made on the columns a source is grouped by"));
                    keysOk = false;
                    continue;
                }
                if (!lc.type().equals(rc.type())) {
                    out.add(error(COMPOSE, path + ".on", "the key '" + lk + "' of '" + j.left() + "' is " + lc.type() + " but '" + rk + "' of '" + j.right() + "' is " + rc.type()
                            + ": both sides of a join are of one type"));
                    keysOk = false;
                }
                leftKeys.add(lk);
                rightKeys.add(rk);
                if (lk.equals(rk)) {
                    folded.put(j.left() + "." + lk, j.right());
                    dropped.add(j.right() + "." + rk);
                }
            }
            if (keysOk) {
                fanOut(path, j, l, r, leftKeys, rightKeys, out);
            }
        }
        for (String alias : c.from().keySet()) {
            if (!joined.contains(alias)) {
                out.add(error(COMPOSE, "from." + alias, "source '" + alias + "' is not joined to the others"));
            }
        }
        // the frame: every column of every source, a folded key once; names must be unique
        Map<String, Dataset.FrameColumn> byName = new LinkedHashMap<>();
        for (Resolved r : sources.values()) {
            for (Dataset.FrameColumn fc : r.columns()) {
                String alias = r.input().alias();
                if (dropped.contains(alias + "." + fc.name())) {
                    continue;
                }
                Dataset.FrameColumn withFold = new Dataset.FrameColumn(fc.name(), fc.alias(), fc.source(), fc.type(), fc.measure(), fc.measure() ? null : folded.get(alias + "." + fc.name()));
                Dataset.FrameColumn clash = byName.put(fc.name(), withFold);
                if (clash != null) {
                    out.add(error(COMPOSE, "from." + fc.alias(), "the column '" + fc.name() + "' is in '" + clash.alias() + "' and in '" + fc.alias() + "' and is not a join key: rename one with `as` (\""
                            + fc.source() + " as other_name\")"));
                }
            }
        }
        List<Dataset.FrameColumn> frame = new ArrayList<>(byName.values());
        Map<String, Dataset.Column> columns = new LinkedHashMap<>();
        Map<String, String> columnOf = new LinkedHashMap<>();
        for (Dataset.FrameColumn fc : frame) {
            String col = identifier(fc.name(), columnOf.values());
            columnOf.put(fc.name(), col);
            columns.put(col, new Dataset.Column(col, col, fc.type(), null, false, null, (fc.measure() ? "measure " : "column ") + fc.source() + " of " + fc.alias()));
        }
        Map<String, Dataset.Measure> measures = new LinkedHashMap<>();
        for (Dataset.FrameColumn fc : frame) {
            if (fc.measure()) {
                if (ds.measures().containsKey(fc.name())) {
                    out.add(error(COMPOSE, "measures." + fc.name(), "'" + fc.name() + "' is already a measure taken from '" + fc.alias() + "'; pick another name for yours"));
                }
                measures.put(fc.name(), new Dataset.Measure(fc.name(), "sum(" + Dataset.JOINED + "." + columnOf.get(fc.name()) + ")", null,
                        "measure " + fc.source() + " of " + fc.alias() + " (" + c.from().get(fc.alias()).dataset() + "), added up over the joined rows", null));
            }
        }
        measures.putAll(ds.measures());
        Dataset.Table table = new Dataset.Table(Dataset.JOINED, "", Dataset.FRAME, null, Dataset.FRAME, false, "", "the joined answers of " + String.join(", ", c.from().keySet()), columns);
        Dataset.Compose done = new Dataset.Compose(c.from(), c.joins(), frame);
        return new Dataset(ds.name(), ds.version(), ds.title(), ds.description(), ds.sample(), ds.engine(), Map.of(Dataset.JOINED, table), List.of(), measures, ds.hierarchies(),
                ds.security(), List.of(), ds.refresh(), done, null);
    }

    /** The name a frame column has as a table column: an identifier (a name with a space or an ampersand becomes underscores), unique among {@code taken}. */
    public static String identifier(String name, java.util.Collection<String> taken) {
        StringBuilder b = new StringBuilder();
        for (char ch : name.toCharArray()) {
            b.append(ch < 128 && Character.isLetterOrDigit(ch) || ch == '_' ? ch : '_');
        }
        String base = b.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
        if (base.isEmpty() || Character.isDigit(base.charAt(0))) {
            base = "c_" + base;
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        if (Dataset.ID.equals(base) || Dataset.DATE.equals(base) || Dataset.INDEX.equals(base)) {
            base = base + "_";
        }
        String candidate = base;
        for (int n = 2; taken.contains(candidate); n++) {
            candidate = base + "_" + n;
        }
        return candidate;
    }

    private static Dataset.FrameColumn column(Resolved r, String name) {
        return r.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }

    /** The columns that identify one row of a source's answer: the declared {@code key}, else every grouping column. */
    private static List<String> identity(Resolved r) {
        return r.input().key().isEmpty() ? r.columns().stream().filter(c -> !c.measure()).map(Dataset.FrameColumn::name).toList() : r.input().key();
    }

    /** A join repeats rows when its keys do not identify one row on each side; say so, because a sum over the repeated rows overstates. */
    private static void fanOut(String path, Dataset.Join j, Resolved l, Resolved r, Set<String> leftKeys, Set<String> rightKeys, List<Problem> out) {
        boolean leftUnique = l.grouped() && leftKeys.containsAll(identity(l));
        boolean rightUnique = r.grouped() && rightKeys.containsAll(identity(r));
        if (!leftUnique && !rightUnique) {
            out.add(warning(FANOUT, path, "many-to-many: neither '" + j.left() + "' nor '" + j.right() + "' has one row per " + String.join(", ", leftKeys)
                    + ", so every row matches several and the measures of both repeat; add the missing grouping columns to the join, or group a source by the key alone"));
        } else if (leftUnique && !rightUnique) {
            out.add(warning(FANOUT, path, "one-to-many: '" + j.left() + "' has one row per " + String.join(", ", leftKeys) + " but '" + j.right()
                    + "' has several, so the measures of '" + j.left() + "' repeat for each and add up too high; put the source with several rows on the left"));
        }
    }

    // ---- derived -----------------------------------------------------------------------------------------------------

    private static Dataset derived(Dataset ds, Map<String, String> declared, List<Problem> out) {
        Dataset.Derived d = ds.derived();
        if (d.transform().isBlank()) {
            out.add(error(DERIVED, "derived.transform", "`transform:` names the Python item (interface transform) that makes the table"));
        }
        if (d.inputs().isEmpty()) {
            out.add(error(DERIVED, "derived.inputs", "`inputs:` names the datasets the Transform reads: alias: { dataset, by, measures } or { dataset, fields }"));
        }
        for (Dataset.Input in : d.inputs().values()) {
            resolve("derived.inputs." + in.alias(), in, out);
        }
        Map<String, String> types = new LinkedHashMap<>(declared);
        boolean learnedColumns = false;
        if (types.isEmpty()) {
            types.putAll(learned(ds.name()));
            learnedColumns = !types.isEmpty();
        }
        if (types.isEmpty()) {
            out.add(warning(DERIVED, "columns", "the columns of the result are not known yet: declare `columns:` (name: type), or run the Transform once (Run now on the dataset page) and the columns it returned are kept"));
        }
        Map<String, Dataset.Column> columns = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : types.entrySet()) {
            if (!DatasetParser.IDENT.matcher(e.getKey()).matches()) {
                out.add(error(DERIVED, "columns." + e.getKey(), "'" + e.getKey() + "' is not a column name (letters, digits, underscore): have the Transform name its columns so"));
                continue;
            }
            if (!Dataset.TYPES.contains(e.getValue())) {
                out.add(error(DERIVED, "columns." + e.getKey(), "type is one of " + new java.util.TreeSet<>(Dataset.TYPES) + ", not '" + e.getValue() + "'"));
                continue;
            }
            columns.put(e.getKey(), new Dataset.Column(e.getKey(), e.getKey(), e.getValue(), null, false, null, learnedColumns ? LEARNED : ""));
        }
        Dataset.Table table = new Dataset.Table(Dataset.RESULT, "", Dataset.FRAME, null, Dataset.FRAME, false, "", "the frame " + d.transform() + " returns", columns);
        return new Dataset(ds.name(), ds.version(), ds.title(), ds.description(), ds.sample(), ds.engine(), Map.of(Dataset.RESULT, table), List.of(), ds.measures(), ds.hierarchies(),
                ds.security(), List.of(), ds.refresh(), null, d);
    }

    private static Problem error(String code, String path, String message) {
        return new Problem("error", code, path, message);
    }

    private static Problem warning(String code, String path, String message) {
        return new Problem("warning", code, path, message);
    }

    /** The sources of a dataset (composed: its {@code from}; derived: its inputs), in order. */
    public static List<Dataset.Input> sources(Dataset ds) {
        if (ds.compose() != null) {
            return new ArrayList<>(ds.compose().from().values());
        }
        if (ds.derived() != null) {
            return new ArrayList<>(ds.derived().inputs().values());
        }
        return List.of();
    }
}
