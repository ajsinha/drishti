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
package com.ash.drishti.tables.catalog;

import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.check.ExpressionChecker;
import com.ash.drishti.tables.check.LayerExpressionChecker;
import com.ash.drishti.tables.check.ModelChecks;
import com.ash.drishti.tables.check.TableChecks;
import com.ash.drishti.tables.def.ColumnDef;
import com.ash.drishti.tables.def.ModelDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.def.TableName;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.tables.lang.Trees;
import com.ash.drishti.tables.yaml.ModelYaml;
import com.ash.drishti.tables.yaml.TableYaml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The catalogue of the table layer: every table and model from {@code config/tables/<domain>/<name>.table.yaml} and {@code .model.yaml}
 * (planned in VIRTUAL_TABLES.md section 4, built here) and from the item store (Q16), by name, with {@code was:} names resolved for one
 * release (Q18), the checks of every definition and of the catalogue as a whole, and lineage (who uses a table). Immutable: a change is a
 * new catalogue ({@link #load}), swapped in whole by its holder.
 */
public final class Catalogue {

    /** One definition and where it came from. */
    public record Entry(String kind, String name, String source, TableDef table, ModelDef model, List<Problem> readProblems) {
        public static final String TABLE = "table";
        public static final String MODEL = "model";
    }

    private final Map<String, Entry> tables = new LinkedHashMap<>();
    private final Map<String, Entry> models = new LinkedHashMap<>();
    private final Map<String, String> was = new LinkedHashMap<>();
    private final List<Problem> problems = new ArrayList<>();

    private Catalogue() {}

    /** A catalogue of the given definitions, in order (the tests' and the migration's way in). */
    public static Catalogue of(Collection<TableDef> tableDefs, Collection<ModelDef> modelDefs) {
        return of(tableDefs, modelDefs, d -> "memory");
    }

    /** As {@link #of(Collection, Collection)}, each definition labelled ({@code bi/trading_risk_trades.table.yaml}) for its problems. */
    public static Catalogue of(Collection<TableDef> tableDefs, Collection<ModelDef> modelDefs, java.util.function.Function<Object, String> label) {
        Catalogue c = new Catalogue();
        tableDefs.forEach(t -> c.add(new Entry(Entry.TABLE, t.name(), label.apply(t), t, null, List.of())));
        modelDefs.forEach(m -> c.add(new Entry(Entry.MODEL, m.name(), label.apply(m), null, m, List.of())));
        return c;
    }

    /**
     * The table as its readers see it: a table over a table ({@code from: { table }}) has its base's columns, rules and keys, its own added
     * (an own column of the same name replaces the base's). A table over anything else is itself.
     */
    public Optional<TableDef> effective(TableName.Ref ref) {
        return table(ref).map(t -> effective(t, 0));
    }

    private TableDef effective(TableDef t, int depth) {
        if (t.from() == null || t.from().form() != TableDef.Source.Form.TABLE || depth > 10) {
            return t;
        }
        Optional<TableDef> base = table(t.from().table()).map(b -> effective(b, depth + 1));
        if (base.isEmpty()) {
            return t;
        }
        Map<String, ColumnDef> columns = new LinkedHashMap<>(base.get().columns());
        columns.putAll(t.columns());
        List<TableDef.Rule> rules = new ArrayList<>(base.get().rules());
        rules.addAll(t.rules());
        List<String> keys = t.keys().isEmpty() ? base.get().keys() : t.keys();
        return new TableDef(t.name(), t.was(), t.version(), t.about(), t.from(), t.grain(), t.filter(), columns, keys, t.keepLatest(), t.says(),
                t.materialize(), t.asOf(), t.retention(), t.refresh(), rules, t.hiddenFor(), t.tests());
    }

    /**
     * Reads {@code root} ({@code config/tables}: a folder per domain) and the stored items. A file that cannot be read is kept as an entry with
     * its problems, so {@code tables check} reports it; it resolves nothing.
     */
    public static Catalogue load(Path root, ItemStore store) throws IOException {
        Catalogue c = new Catalogue();
        if (root != null && Files.isDirectory(root)) {
            try (Stream<Path> walk = Files.walk(root, 2)) {
                for (Path f : walk.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList()) {
                    String file = f.getFileName().toString();
                    if (!file.endsWith(".table.yaml") && !file.endsWith(".model.yaml")) {
                        continue;
                    }
                    String label = root.relativize(f).toString().replace('\\', '/');
                    String folder = f.getParent().equals(root) ? null : f.getParent().getFileName().toString();
                    c.read(file.endsWith(".table.yaml") ? Entry.TABLE : Entry.MODEL, Files.readString(f), label, folder);
                }
            }
        }
        for (ItemStore.StoredItem i : (store == null ? ItemStore.NONE : store).list()) {
            c.read(i.kind(), i.yaml(), i.label(), null);
        }
        return c;
    }

    private void read(String kind, String yaml, String label, String folder) {
        if (Entry.TABLE.equals(kind)) {
            TableYaml.Result r = TableYaml.read(yaml);
            List<Problem> ps = new ArrayList<>(r.problems().stream().map(p -> p.in(label)).toList());
            if (folder == null && label.indexOf('/') < 0 && !label.startsWith("item:")) {
                ps.add(Problem.error(TableCodes.NAME, "", "a table's file is in its domain's folder: config/tables/<domain>/<name>.table.yaml").in(label));
            }
            add(new Entry(kind, r.table() == null ? label : r.table().name(), label, r.table(), null, ps));
        } else {
            ModelYaml.Result r = ModelYaml.read(yaml);
            add(new Entry(kind, r.model() == null ? label : r.model().name(), label, null, r.model(), r.problems().stream().map(p -> p.in(label)).toList()));
        }
    }

    private void add(Entry e) {
        Map<String, Entry> into = Entry.TABLE.equals(e.kind()) ? tables : models;
        if (e.table() == null && e.model() == null) {
            into.put("unreadable:" + e.source(), e);
            return;
        }
        Entry before = into.putIfAbsent(e.name(), e);
        if (before != null) {
            problems.add(Problem.error(TableCodes.DUPLICATE, e.kind(), "'" + e.name() + "' is defined twice: " + before.source() + " and " + e.source()).in(e.source()));
            return;
        }
        if (e.table() != null && e.table().was() != null) {
            String other = was.putIfAbsent(e.table().was(), e.name());
            if (other != null) {
                problems.add(Problem.error(TableCodes.DUPLICATE, "was", "'" + e.table().was() + "' was the name of both " + other + " and " + e.name()).in(e.source()));
            }
        }
    }

    /** Every table, in the order read. */
    public List<TableDef> tables() {
        return tables.values().stream().map(Entry::table).filter(t -> t != null).toList();
    }

    /** Every model, in the order read. */
    public List<ModelDef> models() {
        return models.values().stream().map(Entry::model).filter(m -> m != null).toList();
    }

    /** Every entry, tables first, unreadable files included. */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>(tables.values());
        out.addAll(models.values());
        return out;
    }

    /** The table a reference names: by name, else by its {@code was:} name; the major honoured. */
    public Optional<TableDef> table(TableName.Ref ref) {
        Entry e = tables.get(ref.name());
        if (e == null && was.containsKey(ref.name())) {
            e = tables.get(was.get(ref.name()));
        }
        if (e == null || e.table() == null) {
            return Optional.empty();
        }
        return ref.major() == null || ref.major() == e.table().major() ? Optional.of(e.table()) : Optional.empty();
    }

    public Optional<TableDef> table(String name) {
        return table(TableName.Ref.parse(name));
    }

    /** The model a reference names ({@code trading-risk}, {@code trading-risk@1}). */
    public Optional<ModelDef> model(TableName.Ref ref) {
        Entry e = models.get(ref.name());
        if (e == null || e.model() == null) {
            return Optional.empty();
        }
        return ref.major() == null || ref.major() == e.model().major() ? Optional.of(e.model()) : Optional.empty();
    }

    /** The current name of {@code name} when it is a {@code was:} name, else null. */
    public String renamed(String name) {
        return was.get(name);
    }

    /** Every problem: each file's reading, each definition's checks, and the catalogue's own (duplicates, references, cycles). */
    public List<Problem> check() {
        return check(new LayerExpressionChecker());
    }

    /** As {@link #check()}, with {@code expressions} (the layer's checker, then Rachana's checked mode when it is chained). */
    public List<Problem> check(ExpressionChecker expressions) {
        List<Problem> out = new ArrayList<>(problems);
        TableChecks tc = new TableChecks(expressions);
        ModelChecks mc = new ModelChecks(expressions);
        for (Entry e : entries()) {
            out.addAll(e.readProblems());
            if (e.table() != null) {
                String folder = e.source().contains("/") && !e.source().startsWith("item:") ? e.source().substring(0, e.source().indexOf('/')) : null;
                tc.check(effective(e.table(), 0), folder).forEach(p -> out.add(p.in(e.source())));
                references(e.table(), e.source(), out);
            } else if (e.model() != null) {
                mc.check(e.model(), this::effective).forEach(p -> out.add(p.in(e.source())));
                e.model().tables().values().stream().filter(u -> renamed(u.table().name()) != null)
                        .forEach(u -> out.add(Problem.warning(TableCodes.REFERENCE, "tables." + u.alias(), "'" + u.table().name() + "' is now "
                                + renamed(u.table().name()) + " (the old name resolves for one release)").in(e.source())));
            }
        }
        cycles(out);
        return out;
    }

    private void references(TableDef t, String source, List<Problem> out) {
        if (t.from() != null && t.from().form() == TableDef.Source.Form.TABLE && table(t.from().table()).isEmpty()) {
            out.add(Problem.error(TableCodes.REFERENCE, "from.table", "no table '" + t.from().table() + "' in the catalogue").in(source));
        }
        if (t.from() != null && t.from().form() == TableDef.Source.Form.MODEL && model(TableName.Ref.parse(t.from().model())).isEmpty()) {
            out.add(Problem.error(TableCodes.REFERENCE, "from.model", "no model '" + t.from().model() + "' in the catalogue").in(source));
        }
        for (ColumnDef c : t.columns().values()) {
            for (String[] rel : related(c.expr())) {
                Optional<TableDef> other = table(rel[0]);
                if (other.isEmpty()) {
                    out.add(Problem.error(TableCodes.REFERENCE, "columns." + c.name() + ".expr", "related(...) names no table '" + rel[0] + "'").in(source));
                } else if (!other.get().columns().containsKey(rel[1]) && !TableDef.BUILT_INS.contains(rel[1])) {
                    out.add(Problem.error(TableCodes.REFERENCE, "columns." + c.name() + ".expr", rel[0] + " has no column '" + rel[1] + "'").in(source));
                }
            }
        }
    }

    /** The {@code [table, column]} pairs of the {@code related(domain.table.COLUMN, on = ...)} calls of an expression. */
    static List<String[]> related(String expr) {
        List<String[]> out = new ArrayList<>();
        if (expr == null) {
            return out;
        }
        try {
            Trees.walk(Trees.parse(expr), n -> {
                if (n instanceof Expr.Call c && c.name().equals("related") && !c.args().isEmpty()) {
                    String x = Trees.name(c.args().get(0));
                    int last = x == null ? -1 : x.lastIndexOf('.');
                    if (last > 0) {
                        out.add(new String[] {x.substring(0, last), x.substring(last + 1)});
                    }
                }
            });
        } catch (RuntimeException e) {
            // reported by the table's own checks
        }
        return out;
    }

    private void cycles(List<Problem> out) {
        for (Entry e : tables.values()) {
            if (e.table() == null) {
                continue;
            }
            Set<String> seen = new HashSet<>();
            TableDef t = e.table();
            while (t != null && t.from() != null && t.from().form() == TableDef.Source.Form.TABLE) {
                if (!seen.add(t.name())) {
                    out.add(Problem.error(TableCodes.CYCLE, "from.table", "tables over tables come back to " + t.name() + ": " + String.join(" > ", seen)).in(e.source()));
                    break;
                }
                t = table(t.from().table()).orElse(null);
            }
        }
    }

    /**
     * Lineage (VIRTUAL_TABLES.md section 8): who in the catalogue uses {@code table}: models that read it (with their alias), tables over it,
     * tables whose columns look it up ({@code related}). Consumers outside the layer (BI's reports, alerts, Flight's tokens) add theirs
     * through {@link Lineage}.
     */
    public List<Lineage.Use> usedBy(String table) {
        String name = renamed(table) != null ? renamed(table) : table;
        List<Lineage.Use> out = new ArrayList<>();
        for (ModelDef m : models()) {
            m.tables().values().stream().filter(u -> name.equals(u.table().name()) || name.equals(renamed(u.table().name())))
                    .forEach(u -> out.add(new Lineage.Use(Entry.MODEL, m.name(), "reads it as " + u.alias() + " (" + u.role() + ")")));
        }
        for (TableDef t : tables()) {
            if (t.from() != null && t.from().form() == TableDef.Source.Form.TABLE && name.equals(table(t.from().table()).map(TableDef::name).orElse(null))) {
                out.add(new Lineage.Use(Entry.TABLE, t.name(), "is a table over it"));
            }
            for (ColumnDef c : t.columns().values()) {
                for (String[] rel : related(c.expr())) {
                    if (name.equals(table(rel[0]).map(TableDef::name).orElse(null))) {
                        out.add(new Lineage.Use(Entry.TABLE, t.name(), "column " + c.name() + " looks up " + rel[1]));
                    }
                }
            }
        }
        return out;
    }

    /** The tables a model reads, by alias (what a model's lineage shows upstream). */
    public Map<String, String> reads(ModelDef m) {
        Map<String, String> out = new LinkedHashMap<>();
        m.tables().values().forEach(u -> out.put(u.alias(), table(u.table()).map(TableDef::name).orElse(u.table() + " (missing)")));
        return out;
    }
}
