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
package com.ash.drishti.server.bi.migrate;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.bi.layer.DatasetDefParser;
import com.ash.drishti.server.bi.layer.ModelDefParser;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The dataset-to-model converter (docs/architecture/BI_DESIGN.md › The model layer: the specification of wave W1, section 8.3; {@code drishti.py bi migrate --models}, the start-up migration of stored
 * items): reads a set of {@code rachana: 1} items (shipped files or stored items), plans each in dependency order (datasets with tables, then composed,
 * then derived, then their consumers), checks every output with the model layer's parsers, and answers what to write, what to remove and a plan report.
 * Datasets with tables become user datasets and a model ({@link DatasetSplitter}); composed datasets fold into the model that reads their sources and
 * derived ones become Python datasets ({@link Folding}); reports bind to the models ({@link ReportRewrite}). A {@code rachana: 2} item is skipped, so a second
 * run changes nothing. Pure and thread-safe; it reads no data (the equivalence of the answers is the tests' job, section 8.6).
 */
public final class ModelConverter {

    /** What a role restricted on one dataset sees of the model's other datasets (see {@link DatasetSplitter}). */
    public enum Exposure { NOTE, CLOSED }

    /** The note of a converted dataset whose old file stays (its reports still read it). */
    static final String KEPT = "the 2.1 dataset is kept";

    /**
     * One item to convert.
     *
     * @param key its path under the BI folder ({@code datasets/trading-risk.dataset.yaml}) or a stored item's id
     * @param kind {@code dataset}, {@code report}, {@code board}, {@code notebook}, {@code python} or {@code model}
     * @param yaml its text
     */
    public record Source(String key, String kind, String yaml) {}

    /** A file or item the conversion makes: {@code dataset} or {@code model}, its name, its path under the BI folder and its text. */
    public record Output(String kind, String name, String path, String yaml) {}

    /**
     * What became of one item: {@code state} is {@code converted} (a dataset: its outputs; a report: its new text), {@code removed} (a composed dataset folded
     * into a model), {@code unchanged}, {@code manual} (left as it is, with the steps a person takes) or {@code problem} (refused: {@code problem} says why).
     */
    public record Item(String key, String kind, String name, String state, String became, List<Output> outputs, String yaml, List<String> changes, List<String> notes,
            List<String> manual, String problem) {

        public Map<String, Object> toMap(boolean withText) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key);
            m.put("kind", kind);
            m.put("name", name);
            m.put("state", state);
            m.put("became", became);
            m.put("outputs", outputs.stream().map(o -> withText ? Map.of("kind", o.kind(), "name", o.name(), "path", o.path(), "yaml", o.yaml())
                    : Map.of("kind", o.kind(), "name", o.name(), "path", o.path())).toList());
            if (withText && yaml != null) {
                m.put("yaml", yaml);
            }
            m.put("changes", changes);
            m.put("notes", notes);
            m.put("manual", manual);
            m.put("problem", problem);
            return m;
        }
    }

    /** The plan: every item, the bindings consumers follow, and the report in Markdown. */
    public record Result(List<Item> items, Map<String, Map<String, Object>> bindings, String report) {

        /** The files to write (path to text) when the sources are files: the outputs of converted datasets and the rewritten consumers. */
        public Map<String, String> writes() {
            Map<String, String> out = new LinkedHashMap<>();
            for (Item i : items) {
                i.outputs().forEach(o -> out.put(o.path(), o.yaml()));
                if (i.yaml() != null && "converted".equals(i.state())) {
                    out.put(i.key(), i.yaml());
                }
            }
            return out;
        }

        /** The source files a conversion replaces (the old datasets, converted or folded), when the sources are files. */
        public List<String> removes() {
            return items.stream().filter(i -> "dataset".equals(i.kind()) && ("converted".equals(i.state()) || "removed".equals(i.state())))
                    .filter(i -> i.notes().stream().noneMatch(n -> n.startsWith(KEPT))).map(Item::key).filter(k -> writes().get(k) == null).toList();
        }

        public long count(String state) {
            return items.stream().filter(i -> state.equals(i.state())).count();
        }
    }

    private ModelConverter() {}

    public static Result convert(List<Source> sources, Exposure exposure) {
        DatasetSplitter.Exposure ex = exposure == Exposure.CLOSED ? DatasetSplitter.Exposure.CLOSED : DatasetSplitter.Exposure.NOTE;
        Map<String, Parsed> datasets = new LinkedHashMap<>();
        List<Item> items = new ArrayList<>();
        for (Source s : sources) {
            if (!"dataset".equals(s.kind())) {
                continue;
            }
            if (DatasetDefParser.isVersion2(s.yaml())) {
                items.add(item(s, nameOf(s), "unchanged", "already rachana: 2", List.of(), null, List.of(), List.of(), List.of(), null));
                continue;
            }
            DatasetParser.Result r = DatasetParser.parse(s.yaml());
            List<String> errors = r.problems().stream().filter(DatasetParser.Problem::error).map(p -> p.code() + " " + p.path() + ": " + p.message()).toList();
            if (r.dataset() == null || !errors.isEmpty() && !r.dataset().framed()) {           // a composed or derived one is checked against its sources below
                items.add(item(s, nameOf(s), "problem", null, List.of(), null, List.of(), List.of(), List.of(),
                        "DRS-9190 the old form is not valid: " + (errors.isEmpty() ? "it does not parse" : errors.get(0))));
                continue;
            }
            JsonNode root;
            try {
                root = DatasetSplitter.YAML.readTree(s.yaml());
            } catch (Exception e) {
                items.add(item(s, nameOf(s), "problem", null, List.of(), null, List.of(), List.of(), List.of(), "DRS-9190 not YAML"));
                continue;
            }
            datasets.put(s.key(), new Parsed(s, r.dataset(), root, header(s.yaml(), s.key())));
        }
        Map<String, DatasetSplitter.Split> splits = new LinkedHashMap<>();
        Map<String, Binding> bindings = new LinkedHashMap<>();
        Map<String, Item> dsItems = new LinkedHashMap<>();
        for (Parsed p : datasets.values()) {
            if (p.dataset().framed()) {
                continue;
            }
            DatasetSplitter.Split s = DatasetSplitter.split(p.dataset(), p.root(), p.header(), ex);
            if (!s.problems.isEmpty()) {
                dsItems.put(p.source().key(), item(p.source(), p.dataset().name(), "problem", null, List.of(), null, List.of(), s.notes, s.manual, String.join("; ", s.problems)));
                continue;
            }
            splits.put(p.source().key(), s);
            bindings.put(p.source().key(), new Binding(p.dataset().name(), s.ref(), Map.of(), Map.of(), null, p.dataset().tables().values().stream().anyMatch(Dataset.Table::isLive)));
        }
        java.util.function.Function<String, Optional<String>> keyOf = ref -> resolve(ref, datasets);
        Map<String, List<String>> composedNotes = new LinkedHashMap<>();
        Map<String, Map<String, Binding>> covered = new LinkedHashMap<>();
        for (Parsed p : datasets.values()) {
            if (!p.dataset().composed()) {
                continue;
            }
            Folding.Folded f = Folding.composed(p.dataset(), Folding.declared(p.root()), ref -> keyOf.apply(ref).map(splits::get));
            if (f.problem() != null) {
                dsItems.put(p.source().key(), item(p.source(), p.dataset().name(), "problem", null, List.of(), null, List.of(), List.of(), List.of(), f.problem()));
                continue;
            }
            bindings.put(p.source().key(), f.binding());
            f.covered().forEach((name, b) -> covered.computeIfAbsent(b.model(), k -> new LinkedHashMap<>()).put(name, b));
            composedNotes.computeIfAbsent(keyOf(splits, f.host()), k -> new ArrayList<>()).addAll(f.notes());
            dsItems.put(p.source().key(), item(p.source(), p.dataset().name(), "removed", "folded into the model " + f.host().ref(), List.of(), null,
                    renames(f.binding()), f.notes(), List.of(), null));
        }
        for (Parsed p : datasets.values()) {
            if (!p.dataset().derivedFrame()) {
                continue;
            }
            ModelText m = new ModelText(p.dataset().name(), Math.max(1, p.dataset().version()) + ".0.0");
            m.header = p.header();
            DatasetSplitter.catalogue(p.root(), m.catalogue);
            DatasetSplitter.Split s = new DatasetSplitter.Split(p.dataset(), m);
            List<String> manual = new ArrayList<>();
            Optional<String> problem = Folding.derived(p.dataset(), p.root(), p.header(), ref -> keyOf.apply(ref).map(bindings::get), s, manual);
            if (problem.isPresent()) {
                dsItems.put(p.source().key(), item(p.source(), p.dataset().name(), "problem", null, List.of(), null, List.of(), List.of(), List.of(), problem.get()));
                continue;
            }
            s.manual.addAll(manual);
            s.notes.add("the derived dataset is a Python dataset (" + DatasetSplitter.datasetName(p.dataset().name(), Dataset.RESULT) + ") and a model of the old name over it");
            splits.put(p.source().key(), s);
            bindings.put(p.source().key(), Binding.plain(p.dataset().name(), s.ref()));
        }
        for (Map.Entry<String, DatasetSplitter.Split> e : splits.entrySet()) {
            DatasetSplitter.Split s = e.getValue();
            List<Output> outs = new ArrayList<>();
            s.datasets.forEach(o -> outs.add(new Output(o.kind(), o.name(), o.path(), o.yaml())));
            outs.add(new Output("model", s.v1.name(), "models/" + s.v1.name() + ".model.yaml", s.model.render()));
            List<String> invalid = check(outs);
            Parsed p = datasets.get(e.getKey());
            List<String> notes = new ArrayList<>(s.notes);
            if (s.v1.tables().values().stream().anyMatch(com.ash.drishti.server.bi.dataset.Dataset.Table::isLive)) {
                notes.add(KEPT + ": its reports are live, and a report on a model follows live data from wave W3 (standing queries)");
            }
            notes.addAll(composedNotes.getOrDefault(e.getKey(), List.of()));
            if (!invalid.isEmpty()) {
                bindings.remove(e.getKey());
                dsItems.put(e.getKey(), item(p.source(), s.v1.name(), "problem", null, outs, null, List.of(), notes, s.manual, "DRS-9190 the converted item does not pass "
                        + "Check: " + invalid.get(0)));
                continue;
            }
            dsItems.put(e.getKey(), item(p.source(), s.v1.name(), "converted", "model " + s.ref() + " over " + s.datasets.stream().map(o -> o.name()).toList(), outs, null,
                    List.of(), notes, s.manual, null));
        }
        items.addAll(dsItems.values());
        for (Source s : sources) {
            if ("dataset".equals(s.kind()) || "model".equals(s.kind())) {
                continue;
            }
            items.add(consumer(s, ref -> keyOf.apply(ref).map(bindings::get), (ref, model) -> keyOf.apply(ref).map(datasets::get)
                    .map(p -> covered.getOrDefault(model, Map.of()).get(p.dataset().name()))));
        }
        Map<String, Map<String, Object>> bview = new LinkedHashMap<>();
        bindings.forEach((k, b) -> bview.put(k, b.toMap()));
        return new Result(items, bview, MigrateReport.markdown(items));
    }

    private static Item consumer(Source s, java.util.function.Function<String, Optional<Binding>> bindings,
            java.util.function.BiFunction<String, String, Optional<Binding>> covered) {
        String name = nameOf(s);
        if ("report".equals(s.kind())) {
            ReportRewrite.Result r;
            try {
                r = ReportRewrite.rewrite(s.yaml(), bindings, covered);
            } catch (RuntimeException e) {
                return item(s, name, "problem", null, List.of(), null, List.of(), List.of(), List.of(), "DRS-9190 not YAML: " + e.getMessage());
            }
            if (r.problem() != null) {
                return item(s, name, "problem", null, List.of(), null, List.of(), List.of(), r.manual(), "DRS-9190 " + r.problem());
            }
            if (r.changes().isEmpty()) {
                return item(s, name, r.manual().isEmpty() ? "unchanged" : "manual", null, List.of(), null, List.of(), List.of(), r.manual(), null);
            }
            com.ash.drishti.server.bi.report.ReportParser.Result parsed = com.ash.drishti.server.bi.report.ReportParser.parse(r.yaml());
            if (!parsed.ok()) {
                return item(s, name, "problem", null, List.of(), r.yaml(), r.changes(), List.of(), r.manual(), "DRS-9190 the converted report does not parse: " + parsed.firstError());
            }
            return item(s, name, "converted", parsed.report().model(), List.of(), r.yaml(), r.changes(), List.of(), r.manual(), null);
        }
        List<String> refs = datasetRefs(s.yaml());
        List<String> manual = new ArrayList<>();
        for (String ref : refs) {
            bindings.apply(ref).ifPresent(b -> manual.add("python".equals(s.kind())
                    ? "the Transform reads the dataset " + ref + ", now the model " + b.model() + ": a Python dataset hands it that model's query answers "
                            + "(its source.inputs), each column named by the last part of its field or measure name"
                    : "the notebook reads the dataset " + ref + ", now the model " + b.model() + ": rebind it to the model (`dataset: mq:" + b.model()
                            + "`, and each extract's `dataset:` the same way, its fields as alias.column), as the sample notebook pnl-vs-exposure-pandas does"));
        }
        return item(s, name, manual.isEmpty() ? "unchanged" : "manual", null, List.of(), null, List.of(), List.of(), manual, null);
    }

    /** The dataset references of a notebook ({@code dataset:}, {@code extracts[].dataset}) or a Python item ({@code inputs:}). */
    private static List<String> datasetRefs(String yaml) {
        List<String> out = new ArrayList<>();
        try {
            JsonNode n = DatasetSplitter.YAML.readTree(yaml);
            if (n == null) {
                return out;
            }
            if (n.path("dataset").isTextual()) {
                out.add(n.path("dataset").asText());
            }
            n.path("extracts").forEach(x -> {
                if (x.path("dataset").isTextual()) {
                    out.add(x.path("dataset").asText());
                }
            });
            if ("transform".equals(n.path("interface").asText())) {
                n.path("inputs").forEach(x -> out.add(x.asText()));
            }
        } catch (Exception e) {
            // not YAML: nothing to rebind
        }
        return out;
    }

    /** Checks every output with the model layer's parsers; the first error of each that fails. */
    private static List<String> check(List<Output> outs) {
        List<String> errors = new ArrayList<>();
        for (Output o : outs) {
            String first = "model".equals(o.kind()) ? (ModelDefParser.parse(o.yaml()).ok() ? null : ModelDefParser.parse(o.yaml()).firstError())
                    : (DatasetDefParser.parse(o.yaml()).ok() ? null : DatasetDefParser.parse(o.yaml()).firstError());
            if (first != null) {
                errors.add(o.path() + ": " + first);
            }
        }
        return errors;
    }

    private static List<String> renames(Binding b) {
        List<String> out = new ArrayList<>();
        b.fields().forEach((k, v) -> out.add(k + " -> " + v));
        b.measures().forEach((k, v) -> out.add(k + " -> " + v));
        return out;
    }

    private record Parsed(Source source, Dataset dataset, JsonNode root, String header) {}

    private static String keyOf(Map<String, DatasetSplitter.Split> splits, DatasetSplitter.Split s) {
        return splits.entrySet().stream().filter(e -> e.getValue() == s).map(Map.Entry::getKey).findFirst().orElse("");
    }

    /** The key of the dataset a consumer names: its key, its name, {@code file:dataset:<name>} or {@code <name>.dataset.yaml}. */
    private static Optional<String> resolve(String ref, Map<String, Parsed> datasets) {
        if (ref == null) {
            return Optional.empty();
        }
        String r = ref.strip();
        String bare = r.startsWith("file:dataset:") ? r.substring("file:dataset:".length()) : r.endsWith(".dataset.yaml") ? r.substring(0, r.length() - ".dataset.yaml".length()) : r;
        for (Parsed p : datasets.values()) {
            if (p.source().key().equals(r) || p.dataset().name().equals(bare) || p.source().key().endsWith("/" + bare + ".dataset.yaml")) {
                return Optional.of(p.source().key());
            }
        }
        return Optional.empty();
    }

    private static Item item(Source s, String name, String state, String became, List<Output> outputs, String yaml, List<String> changes, List<String> notes,
            List<String> manual, String problem) {
        return new Item(s.key(), s.kind(), name, state, became, List.copyOf(outputs), yaml, List.copyOf(changes), List.copyOf(notes), List.copyOf(manual), problem);
    }

    private static String nameOf(Source s) {
        try {
            JsonNode n = DatasetSplitter.YAML.readTree(s.yaml());
            if (n != null && n.path(s.kind()).isTextual()) {
                return n.path(s.kind()).asText();
            }
        } catch (Exception e) {
            // not YAML
        }
        String k = s.key();
        return k.substring(k.lastIndexOf('/') + 1).replaceAll("\\..*$", "");
    }

    /**
     * The comment block a converted file starts with: the original's leading comments (the licence header and what the file is about), then a line saying
     * where it came from.
     */
    static String header(String yaml, String key) {
        StringBuilder b = new StringBuilder();
        for (String line : yaml.split("\n", -1)) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) {
                break;
            }
            b.append(line).append('\n');
        }
        String h = b.toString().replaceAll("\n{3,}", "\n\n");
        if (h.isBlank()) {
            h = "";
        } else if (!h.endsWith("\n\n")) {
            h += "\n";
        }
        String from = key.contains("/") ? key : "the stored item " + key;
        return h + "# CONVERTED from " + from + " (rachana: 1) by the dataset-to-model converter (drishti.py bi migrate --models;\n"
                + "# docs/architecture/BI_DESIGN.md › The model layer: the specification of wave W1, section 8). The model keeps the old name and table names as aliases, so fields read as before.\n\n";
    }

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
