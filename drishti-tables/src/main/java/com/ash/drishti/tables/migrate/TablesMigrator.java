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
package com.ash.drishti.tables.migrate;

import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.TableCodes;
import com.ash.drishti.tables.catalog.Catalogue;
import com.ash.drishti.tables.catalog.ItemStore;
import com.ash.drishti.tables.def.ModelDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.def.TableName;
import com.ash.drishti.tables.yaml.ModelYaml;
import com.ash.drishti.tables.yaml.TableYaml;
import com.ash.drishti.tables.yaml.Yaml;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The one migration tool (BI_FOUNDATION_2911E.md section 4), {@code drishti.py tables migrate}: BI's datasets into virtual tables and BI's
 * models into models of the layer, every DEL expression into Rachana in the same pass. Plans first (nothing written), then a dry run prints
 * the diff per file, {@code --check} says whether anything would change (exit 1), and a write writes the files, one audit line per item.
 * Idempotent: an item already in the layer's form is left as it is, and a second run over the output changes nothing.
 *
 * <p><b>Skeleton (2.9.11.e, part B foundation).</b> Built: files of {@code config/bi/datasets} and {@code config/bi/models} ({@code rachana: 1}
 * and {@code 2}), the mapping of every key of sections 3.1 and 3.3 the shipped items use, the layer's rewrites (columns to paths, date parts,
 * the null rule), the report, the dry run, {@code --check}, the audit. The DEL translator is part A's ({@code DelToRachana}), plugged in
 * by the server through {@link DelTranslator}.
 */
public final class TablesMigrator {

    /**
     * One expression's change.
     *
     * @param item the item it is in
     * @param key the key it is written under
     * @param before the DEL text
     * @param after the Rachana text, or null when refused
     * @param notes what changed, in words
     * @param refusal why it was not translated, or null
     */
    public record Change(String item, String key, String before, String after, List<String> notes, String refusal) {
        public Change {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    /**
     * One item of the run.
     *
     * @param source the file ({@code datasets/trading-risk-trades.dataset.yaml}) or the stored item
     * @param kind {@code dataset} or {@code model}
     * @param state {@code converted}, {@code unchanged} (already in the layer's form, or the output is already there), {@code refused}
     * @param outputs the files it gives, relative to {@code config/tables}
     * @param notes what the report says about it
     * @param refusal why it was refused, or null
     */
    public record Item(String source, String kind, String state, List<String> outputs, List<String> notes, String refusal) {}

    /**
     * What a run would do.
     *
     * @param writes file (relative to {@code config/tables}) to its new text, only those that change
     * @param before file to its current text (null for a new file)
     * @param sources file to the BI file it comes from (relative to the BI folder)
     * @param items every item
     * @param changes every expression changed or refused
     * @param problems the checks of the catalogue the run would leave (the existing tables plus the new ones)
     * @param consumers a consumer file of the BI folder ({@code reports/x.report.yaml}) to its new text, only those that change
     * @param consumersBefore those files' text now
     */
    public record Plan(Map<String, String> writes, Map<String, String> before, Map<String, String> sources, List<Item> items, List<Change> changes,
            List<Problem> problems, Map<String, String> consumers, Map<String, String> consumersBefore) {

        public boolean anyWrite() {
            return !writes.isEmpty() || !consumers.isEmpty();
        }

        public long count(String state) {
            return items.stream().filter(i -> i.state().equals(state)).count();
        }

        /** The unified diff of every file that would change. */
        public String diff() {
            StringBuilder sb = new StringBuilder();
            writes.forEach((f, text) -> sb.append(UnifiedDiff.of(f, before.get(f), text)));
            consumers.forEach((f, text) -> sb.append(UnifiedDiff.of("config/bi/" + f, consumersBefore.get(f), text)));
            return sb.toString();
        }
    }

    private final DelTranslator translator;

    public TablesMigrator(DelTranslator translator) {
        this.translator = translator;
    }

    /**
     * Plans the migration of the BI folder {@code bi} ({@code config/bi}: {@code datasets/}, {@code models/}) into {@code tables}
     * ({@code config/tables}), and of its consumers ({@code reports/}, {@code boards/}, {@code alerts/}, {@code notebooks/}) in place.
     */
    public Plan plan(Path bi, Path tables) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        for (String dir : List.of("datasets", "models")) {
            Path d = bi.resolve(dir);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.list(d)) {
                for (Path f : s.filter(p -> p.toString().endsWith(".dataset.yaml") || p.toString().endsWith(".model.yaml")).sorted().toList()) {
                    files.put(dir + "/" + f.getFileName(), Files.readString(f));
                }
            }
        }
        Map<String, String> consumers = new LinkedHashMap<>();
        for (String dir : CONSUMERS) {
            Path d = bi.resolve(dir);
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.list(d)) {
                for (Path f : s.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                    consumers.put(dir + "/" + f.getFileName(), Files.readString(f));
                }
            }
        }
        return plan(files, tables, consumers);
    }

    /** The BI folder's consumers of datasets and models. */
    static final List<String> CONSUMERS = List.of("reports", "boards", "alerts", "notebooks");

    /** Plans the migration of the given BI files (relative name to text); the tests' and the server's way in. */
    public Plan plan(Map<String, String> files, Path tables) throws IOException {
        return plan(files, tables, Map.of());
    }

    /** As above, with the consumer files (relative name to text) rewritten too. */
    public Plan plan(Map<String, String> files, Path tables, Map<String, String> consumerFiles) throws IOException {
        Translate translate = new Translate(translator);
        List<Item> items = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        Map<String, String> writes = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, JsonNode> datasets = new LinkedHashMap<>();
        Map<String, JsonNode> models = new LinkedHashMap<>();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> f : files.entrySet()) {
            JsonNode n;
            try {
                n = Yaml.MAPPER.readTree(f.getValue());
            } catch (IOException e) {
                items.add(new Item(f.getKey(), "unknown", "refused", List.of(), List.of(), "not YAML: " + e.getMessage()));
                continue;
            }
            if (n == null || !n.isObject()) {
                items.add(new Item(f.getKey(), "unknown", "refused", List.of(), List.of(), "not a YAML mapping"));
                continue;
            }
            headers.put(f.getKey(), licence(f.getValue()));
            if (n.has("table") || "3".equals(n.path("rachana").asText())) {
                items.add(new Item(f.getKey(), n.has("model") ? "model" : "table", "unchanged", List.of(), List.of("already in the layer's form"), null));
            } else if (n.has("dataset")) {
                datasets.put(n.path("dataset").asText(), n);
                sources.put("dataset:" + n.path("dataset").asText(), f.getKey());
            } else if (n.has("model")) {
                models.put(n.path("model").asText(), n);
                sources.put("model:" + n.path("model").asText(), f.getKey());
            } else {
                items.add(new Item(f.getKey(), "unknown", "refused", List.of(), List.of(), "neither a dataset nor a model"));
            }
        }
        DatasetMigration dm = new DatasetMigration(translate, datasets);
        Map<String, TableDef> migrated = new LinkedHashMap<>();
        List<TableDef> newTables = new ArrayList<>();
        List<ModelDef> newModels = new ArrayList<>();
        Map<Object, String> labels = new java.util.IdentityHashMap<>();
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, String> outSource = new LinkedHashMap<>();
        // in order: version-2 datasets (one table each), then models, then version-1 datasets (a table per table and a model), so a version-1
        // original kept beside its converted form (the live samples) is the one refused for the name both give
        Map<String, String> produced = new LinkedHashMap<>();
        Map<String, Object> producedDefs = new LinkedHashMap<>();
        for (int phase = 0; phase < 3; phase++) {
            if (phase == 1) {
                for (Map.Entry<String, JsonNode> m : models.entrySet()) {
                    String src = sources.get("model:" + m.getKey());
                    List<Change> mine = new ArrayList<>();
                    List<String> notes = new ArrayList<>();
                    ModelDef def = ModelMigration.migrate(m.getValue(), translate, migrated::get, datasets::get, mine, notes);
                    changes.addAll(mine);
                    String refusal = mine.stream().map(Change::refusal).filter(r -> r != null).findFirst().orElse(null);
                    String rel = TableName.BI_DOMAIN + "/" + def.name() + ".model.yaml";
                    if (refusal == null && produced.containsKey(rel)) {
                        refusal = "the model " + def.name() + " is also made from " + produced.get(rel) + ": rename one";
                    }
                    if (refusal != null) {
                        items.add(new Item(src, "model", "refused", List.of(), notes, refusal));
                        continue;
                    }
                    produced.put(rel, src);
                    producedDefs.put(rel, def);
                    out.put(rel, ModelYaml.write(def, header(headers.get(src), src)));
                    outSource.put(rel, src);
                    newModels.add(def);
                    labels.put(def, rel);
                    items.add(new Item(src, "model", "converted", List.of(rel), notes, null));
                }
                continue;
            }
            for (Map.Entry<String, JsonNode> d : datasets.entrySet()) {
                boolean v1 = d.getValue().has("tables");
                if (v1 != (phase == 2)) {
                    continue;
                }
                String src = sources.get("dataset:" + d.getKey());
                DatasetMigration.Out o = dm.migrate(d.getValue());
                changes.addAll(o.changes());
                Map<String, Object> mine = new LinkedHashMap<>();
                o.tables().forEach(t -> mine.put(t.domain() + "/" + t.name().substring(t.domain().length() + 1) + ".table.yaml", t));
                if (o.model() != null) {
                    mine.put(TableName.BI_DOMAIN + "/" + o.model().name() + ".model.yaml", o.model());
                }
                String refusal = o.refusal();
                if (refusal == null && v1 && !mine.isEmpty() && mine.keySet().stream().allMatch(produced::containsKey)) {
                    // a version-1 original whose converted form ships beside it (the live samples, Q19): the same tables with the same
                    // columns and the same model with the same measures are already made; nothing more is written for it
                    String differs = mine.entrySet().stream().filter(e -> !sameShape(e.getValue(), producedDefs.get(e.getKey()))).map(Map.Entry::getKey)
                            .findFirst().orElse(null);
                    if (differs == null) {
                        List<String> notes = new ArrayList<>(o.notes());
                        notes.add("its converted form already ships (" + String.join(", ", mine.keySet().stream().map(produced::get).distinct().toList())
                                + "): the same tables, columns and measures; nothing more to write");
                        items.add(new Item(src, "dataset", "converted", new ArrayList<>(mine.keySet()), notes, null));
                        continue;
                    }
                    refusal = differs + " is also made from " + produced.get(differs) + " and differs from it: rename one";
                }
                if (refusal == null) {
                    refusal = mine.keySet().stream().filter(produced::containsKey).findFirst()
                            .map(rel -> rel + " is also made from " + produced.get(rel) + ": rename one").orElse(null);
                }
                if (refusal != null) {
                    items.add(new Item(src, "dataset", "refused", List.of(), o.notes(), refusal));
                    continue;
                }
                List<String> header = header(headers.get(src), src);
                for (Map.Entry<String, Object> e : mine.entrySet()) {
                    String rel = e.getKey();
                    produced.put(rel, src);
                    producedDefs.put(rel, e.getValue());
                    outSource.put(rel, src);
                    labels.put(e.getValue(), rel);
                    if (e.getValue() instanceof TableDef t) {
                        out.put(rel, TableYaml.write(t, header));
                        newTables.add(t);
                        migrated.put(t.was() != null ? t.was() : t.name(), t);
                    } else {
                        out.put(rel, ModelYaml.write((ModelDef) e.getValue(), header));
                        newModels.add((ModelDef) e.getValue());
                    }
                }
                items.add(new Item(src, "dataset", "converted", new ArrayList<>(mine.keySet()), o.notes(), null));
            }
        }
        Map<String, String> before = new LinkedHashMap<>();
        for (Map.Entry<String, String> o : out.entrySet()) {
            Path f = tables == null ? null : tables.resolve(o.getKey());
            String now = f != null && Files.exists(f) ? Files.readString(f) : null;
            if (o.getValue().equals(now)) {
                continue;                                                   // already there: a second run changes nothing
            }
            writes.put(o.getKey(), o.getValue());
            before.put(o.getKey(), now);
            sources.put(o.getKey(), outSource.get(o.getKey()));
        }
        List<Item> marked = new ArrayList<>();
        for (Item i : items) {
            boolean anyWrite = i.outputs().stream().anyMatch(writes::containsKey);
            marked.add("converted".equals(i.state()) && !anyWrite ? new Item(i.source(), i.kind(), "unchanged", i.outputs(), i.notes(), null) : i);
        }
        Catalogue existing = Catalogue.load(tables, ItemStore.NONE);
        // a definition already under config/tables keeps its own file as its label: the check reads the domain folder from it
        existing.entries().stream().filter(e -> e.table() != null || e.model() != null)
                .forEach(e -> labels.putIfAbsent(e.table() != null ? e.table() : e.model(), e.source()));
        List<TableDef> all = new ArrayList<>(newTables);
        existing.tables().stream().filter(t -> all.stream().noneMatch(x -> x.name().equals(t.name()))).forEach(all::add);
        List<ModelDef> allModels = new ArrayList<>(newModels);
        existing.models().stream().filter(x -> allModels.stream().noneMatch(y -> y.name().equals(x.name()))).forEach(allModels::add);
        List<Problem> problems = new ArrayList<>(Catalogue.of(all, allModels, d -> labels.getOrDefault(d, "config/tables")).check());
        changes.stream().filter(c -> c.refusal() != null)
                .forEach(c -> problems.add(Problem.error(TableCodes.MIGRATION_REFUSED, c.key(), c.refusal() + " (" + c.before() + ")").in(c.item())));
        Map<String, String> fileSources = new LinkedHashMap<>();
        writes.keySet().forEach(k -> fileSources.put(k, sources.get(k)));
        Map<String, Integer> becameModels = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> d : datasets.entrySet()) {
            if (d.getValue().has("tables")) {
                becameModels.put(d.getKey(), Integer.parseInt(DatasetMigration.version(d.getValue().path("version").asText("1.0.0")).split("\\.")[0]));
            }
        }
        ConsumerMigration cm = new ConsumerMigration(translate);
        Map<String, String> consumers = new LinkedHashMap<>();
        Map<String, String> consumersBefore = new LinkedHashMap<>();
        for (Map.Entry<String, String> c : consumerFiles.entrySet()) {
            List<Change> mine = new ArrayList<>();
            String now = cm.rewrite(c.getValue(), becameModels, c.getKey(), mine);
            changes.addAll(mine);
            mine.stream().filter(x -> x.refusal() != null)
                    .forEach(x -> problems.add(Problem.error(TableCodes.MIGRATION_REFUSED, x.key(), x.refusal() + " (" + x.before() + ")").in(c.getKey())));
            if (!now.equals(c.getValue())) {
                consumers.put(c.getKey(), now);
                consumersBefore.put(c.getKey(), c.getValue());
            }
        }
        return new Plan(writes, before, fileSources, marked, changes, problems, consumers, consumersBefore);
    }

    /**
     * Writes a plan into {@code tables}, one audit line per file. The BI files stay where they are until the switch (BI still reads them);
     * with {@code movedTo} they are moved there instead (the installation's {@code <data>/migrated/2.9.11.e/}, section 4.3).
     *
     * @return the files written
     */
    public static List<String> write(Plan plan, Path tables, Path bi, Path movedTo, MigrationAudit audit, String who) throws IOException {
        List<String> written = new ArrayList<>();
        for (Map.Entry<String, String> w : plan.writes().entrySet()) {
            Path f = tables.resolve(w.getKey());
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".migrating");
            Files.writeString(tmp, w.getValue());
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            audit.record(who, "file:" + w.getKey() + (plan.sources().get(w.getKey()) == null ? "" : " from " + plan.sources().get(w.getKey())),
                    plan.before().get(w.getKey()), w.getValue());
            written.add(w.getKey());
        }
        for (Map.Entry<String, String> c : plan.consumers().entrySet()) {
            Path f = bi.resolve(c.getKey());
            Path tmp = f.resolveSibling(f.getFileName() + ".migrating");
            Files.writeString(tmp, c.getValue());
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            audit.record(who, "file:config/bi/" + c.getKey() + " (a consumer)", plan.consumersBefore().get(c.getKey()), c.getValue());
            written.add("config/bi/" + c.getKey());
        }
        if (movedTo != null && bi != null) {
            for (Item i : plan.items()) {
                if (!"converted".equals(i.state()) && !"unchanged".equals(i.state()) || i.outputs().isEmpty()) {
                    continue;
                }
                Path from = bi.resolve(i.source());
                if (Files.exists(from)) {
                    Path to = movedTo.resolve(i.source());
                    Files.createDirectories(to.getParent());
                    Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return written;
    }

    /** A version-1 original and the converted form of its name: tables with the same columns; the model holding every measure of the original. */
    static boolean sameShape(Object a, Object b) {
        if (a instanceof TableDef x && b instanceof TableDef y) {
            return x.columns().keySet().equals(y.columns().keySet());
        }
        if (a instanceof ModelDef x && b instanceof ModelDef y) {
            return y.measures().keySet().containsAll(x.measures().keySet());
        }
        return false;
    }

    /** The report in words: the counts, every item, every expression changed or refused. */
    public static String report(Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("items: ").append(plan.items().size()).append(" (converted ").append(plan.count("converted")).append(", unchanged ")
                .append(plan.count("unchanged")).append(", refused ").append(plan.count("refused")).append(")\n");
        long changed = plan.changes().stream().filter(c -> c.after() != null && !c.after().equals(c.before())).count();
        long refused = plan.changes().stream().filter(c -> c.refusal() != null).count();
        sb.append("expressions: ").append(plan.changes().size()).append(" (rewritten ").append(changed).append(", refused ").append(refused).append(")\n");
        sb.append("files to write: ").append(plan.writes().size()).append('\n');
        for (Item i : plan.items()) {
            sb.append("\n").append(i.state()).append("  ").append(i.source()).append(i.outputs().isEmpty() ? "" : "  ->  " + String.join(", ", i.outputs())).append('\n');
            if (i.refusal() != null) {
                sb.append("    refused: ").append(i.refusal()).append('\n');
            }
            i.notes().forEach(n -> sb.append("    note: ").append(n).append('\n'));
            String src = i.source();
            for (Change c : plan.changes()) {
                if (!src.endsWith(c.item() + ".dataset.yaml") && !src.endsWith(c.item() + ".model.yaml") && !src.contains("/" + c.item().split("\\.")[0] + ".")) {
                    continue;
                }
                if (c.refusal() != null) {
                    sb.append("    ").append(c.key()).append(": REFUSED ").append(c.refusal()).append("\n      ").append(c.before()).append('\n');
                } else if (!c.before().equals(c.after())) {
                    sb.append("    ").append(c.key()).append(":\n      - ").append(c.before()).append("\n      + ").append(c.after()).append('\n');
                    c.notes().forEach(n -> sb.append("        (").append(n).append(")\n"));
                }
            }
        }
        return sb.toString();
    }

    /** The licence block at the top of a BI file (its leading comment lines up to the first blank line). */
    static List<String> licence(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.lines().toList()) {
            if (!line.startsWith("#")) {
                break;
            }
            out.add(line);
        }
        return out;
    }

    private static List<String> header(List<String> licence, String source) {
        List<String> out = new ArrayList<>(licence == null ? List.of() : licence);
        if (!out.isEmpty()) {
            out.add("");
        }
        out.add("# Migrated from config/bi/" + source + " by drishti.py tables migrate (docs/architecture/BI_FOUNDATION_2911E.md section 4).");
        return out;
    }
}
