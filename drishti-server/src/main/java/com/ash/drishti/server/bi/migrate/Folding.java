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
import com.ash.drishti.server.bi.dataset.Node;
import com.ash.drishti.server.bi.expr.Expressions;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Composed and derived datasets in the converter (docs/architecture/BI_MODEL_SPEC.md sections 8.1 and 8.2).
 *
 * <p>A <b>composed</b> dataset joined the answers of other datasets; after conversion those datasets' tables are datasets of one model with relationships
 * to shared dimensions. The converter looks for a <i>host</i>: a source whose model already reads every table the other sources use (the same connector, kind
 * and columns), as {@code trading-risk} reads the netting sets and counterparties of {@code counterparty-exposure}. The composed measures and any source
 * measure the host lacks move into the host model; the consumers bind to the host with the frame's names ({@code joined.name}, {@code Desk P&L}) renamed to
 * the host's ({@code counterparties.name}, {@code P&L 1d}); a left join's "only rows of the left source" becomes a filter on the left source's count measure
 * ({@code Trades > 0}). A composed dataset with no host is refused ({@code DRS-9191}) with the Python-dataset alternative.
 *
 * <p>A <b>derived</b> dataset becomes a Python dataset ({@code source.python}, its inputs model queries) named {@code <old>-result} and a model of the old
 * name over it under the alias {@code result}, so its measures and the consumers' {@code result.*} fields keep their meaning.
 */
final class Folding {

    /** What folding a composed dataset gave: the host's split and the binding of the composed dataset's consumers, or the problem. */
    record Folded(DatasetSplitter.Split host, Binding binding, Map<String, Binding> covered, List<String> notes, String problem) {}

    private Folding() {}

    // ---- composed ------------------------------------------------------------------------------------------------------

    static Folded composed(Dataset c, Set<String> declared, Function<String, Optional<DatasetSplitter.Split>> splits) {
        Map<String, DatasetSplitter.Split> sources = new LinkedHashMap<>();
        for (Dataset.Input in : c.compose().from().values()) {
            Optional<DatasetSplitter.Split> s = splits.apply(in.dataset());
            if (s.isEmpty()) {
                return new Folded(null, null, Map.of(), List.of(), "DRS-9191 the source '" + in.dataset() + "' of input '" + in.alias() + "' is not a dataset with tables this run converts");
            }
            sources.put(in.alias(), s.get());
        }
        for (Map.Entry<String, DatasetSplitter.Split> candidate : sources.entrySet()) {
            DatasetSplitter.Split host = candidate.getValue();
            Map<String, Map<String, String>> aliasMaps = new LinkedHashMap<>();
            boolean covers = true;
            for (Map.Entry<String, DatasetSplitter.Split> other : sources.entrySet()) {
                Optional<Map<String, String>> map = cover(host.v1, other.getValue().v1, used(c.compose().from().get(other.getKey()), other.getValue().v1));
                if (map.isEmpty()) {
                    covers = false;
                    break;
                }
                aliasMaps.put(other.getKey(), map.get());
            }
            if (covers) {
                return fold(c, declared, host, sources, aliasMaps);
            }
        }
        return new Folded(null, null, Map.of(), List.of(), "DRS-9191 the sources of '" + c.name() + "' (" + sources.values().stream().map(s -> s.v1.name()).toList()
                + ") are in different models and no one of them reads the others' tables: put their datasets in one model with relationships to shared dimensions, "
                + "or write the join as a Python dataset (source.python with model-query inputs)");
    }

    private static Folded fold(Dataset c, Set<String> declared, DatasetSplitter.Split host, Map<String, DatasetSplitter.Split> sources, Map<String, Map<String, String>> aliasMaps) {
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> measures = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (Dataset.Input in : c.compose().from().values()) {
            DatasetSplitter.Split src = sources.get(in.alias());
            Map<String, String> tables = aliasMaps.get(in.alias());
            List<Dataset.Named> cols = in.rows() ? in.fields() : in.by();
            for (Dataset.Named n : cols) {
                String frame = Dataset.JOINED + "." + (n.as() != null ? n.as() : n.source().substring(n.source().lastIndexOf('.') + 1));
                fields.putIfAbsent(frame, Expressions.withRenames(n.source(), tables, Map.of()));
            }
            for (Dataset.Named n : in.measures()) {
                String frame = n.as() != null ? n.as() : n.source();
                String target = hostMeasure(host, src, n.source(), tables, notes);
                if (!frame.equals(target)) {
                    measures.put(frame, target);
                }
            }
        }
        for (Dataset.Measure m : c.measures().values().stream().filter(x -> declared.contains(x.name())).toList()) {          // its own, not the frame's generated ones
            String expr = Expressions.withRenames(m.expr(), Map.of(), measures);
            String name = m.name();
            ModelText.Measure existing = host.model.measures.get(name);
            if (existing != null && !same(existing.expr(), expr)) {
                name = m.name() + " (" + c.name() + ")";
                measures.put(m.name(), name);
            }
            if (existing == null || !name.equals(m.name())) {
                Dataset.Measure moved = new Dataset.Measure(name, expr, m.format(), m.description(), m.python(), m.inputs(), m.by());
                host.model.measures.put(name, new ModelText.Measure(name, DatasetSplitter.measure(moved), expr, "the composed dataset " + c.name()));
                notes.add("measure '" + name + "' moved from the composed dataset " + c.name() + " into the model " + host.v1.name());
            }
        }
        String having = null;
        Dataset.Join first = c.compose().joins().isEmpty() ? null : c.compose().joins().get(0);
        if (first != null && !"full".equals(first.type())) {
            Dataset.Input left = c.compose().from().get(first.left());
            String count = left.measures().stream().map(Dataset.Named::source).filter(x -> isCount(sources.get(left.alias()).v1.measures().get(x))).findFirst()
                    .orElse(left.measures().isEmpty() ? null : left.measures().get(0).source());
            if (count != null) {
                having = hostMeasure(host, sources.get(left.alias()), count, aliasMaps.get(left.alias()), new ArrayList<>());
                notes.add("BI_MODEL_SPEC.md 8.5: the " + first.type() + " join kept only the counterparties of '" + left.alias() + "'; its consumers filter on "
                        + having + " > 0 to keep that meaning (a total over all rows, such as a card, now counts every row of the other fact)");
            }
        }
        notes.add("the composed dataset " + c.name() + " is removed: its consumers read the model " + host.ref() + (fields.isEmpty() ? "" : " with " + fields.size() + " field(s) renamed"));
        Map<String, Binding> covered = new LinkedHashMap<>();
        for (Map.Entry<String, DatasetSplitter.Split> e : sources.entrySet()) {
            DatasetSplitter.Split src = e.getValue();
            if (src == host || covered.containsKey(src.v1.name())) {
                continue;
            }
            Map<String, String> tables = aliasMaps.get(e.getKey());
            Map<String, String> ms = new LinkedHashMap<>();
            for (Dataset.Measure m : src.v1.measures().values()) {
                String target = hostMeasure(host, src, m.name(), tables, notes);
                if (!target.equals(m.name())) {
                    ms.put(m.name(), target);
                }
            }
            covered.put(src.v1.name(), new Binding(src.v1.name(), host.ref(), tables, ms, null));    // a visual of the report that read this source reads the host
        }
        return new Folded(host, new Binding(c.name(), host.ref(), fields, measures, having), covered, notes, null);
    }

    /** The host measure an input's measure is: the same name with the same meaning, or added to the host (renamed when the name is taken). */
    private static String hostMeasure(DatasetSplitter.Split host, DatasetSplitter.Split src, String name, Map<String, String> tables, List<String> notes) {
        Dataset.Measure m = src.v1.measures().get(name);
        if (m == null || m.expr() == null) {
            return name;
        }
        String expr = Expressions.withRenames(m.expr(), tables, Map.of());
        ModelText.Measure there = host.model.measures.get(name);
        if (there != null && same(there.expr(), expr)) {
            return name;
        }
        String target = there == null ? name : name + " (" + src.v1.name() + ")";
        if (!host.model.measures.containsKey(target)) {
            Dataset.Measure moved = new Dataset.Measure(target, expr, m.format(), m.description(), null);
            host.model.measures.put(target, new ModelText.Measure(target, DatasetSplitter.measure(moved), expr, src.v1.name()));
            notes.add("measure '" + target + "' of " + src.v1.name() + " added to the model " + host.v1.name());
        }
        return target;
    }

    private static boolean isCount(Dataset.Measure m) {
        if (m == null || m.expr() == null) {
            return false;
        }
        try {
            return Expressions.parse(m.expr()) instanceof Node.Call call && "count".equals(call.function()) && call.args().size() == 1 && call.args().get(0) instanceof Node.Ref;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean same(String a, String b) {
        if (Objects.equals(a, b)) {
            return true;
        }
        try {
            return a != null && b != null && Expressions.parse(a).equals(Expressions.parse(b));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The tables of a source an input reads: those its fields name and those its measures aggregate. */
    private static Set<String> used(Dataset.Input in, Dataset d) {
        Set<String> out = new LinkedHashSet<>();
        List<Dataset.Named> cols = new ArrayList<>(in.by());
        cols.addAll(in.fields());
        cols.forEach(n -> out.add(n.source().split("\\.")[0]));
        for (Dataset.Named n : in.measures()) {
            Dataset.Measure m = d.measures().get(n.source());
            if (m != null && m.expr() != null) {
                try {
                    Node.walk(Expressions.parse(m.expr()), x -> {
                        if (x instanceof Node.Ref r && d.tables().containsKey(r.parts().get(0))) {
                            out.add(r.parts().get(0));
                        }
                    });
                } catch (RuntimeException e) {
                    // a measure that does not parse: the composed dataset was refused already
                }
            }
        }
        return out;
    }

    /** For every used table of {@code other}, the table of {@code host} that reads the same kind through the same connector with at least its columns. */
    private static Optional<Map<String, String>> cover(Dataset host, Dataset other, Set<String> used) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String t : used) {
            Dataset.Table o = other.tables().get(t);
            if (o == null) {
                return Optional.empty();
            }
            Dataset.Table match = null;
            for (Dataset.Table h : host.tables().values()) {
                if (!h.isHistory() && Objects.equals(blank(h.connector()), blank(o.connector())) && Objects.equals(h.kind(), o.kind()) && Objects.equals(blank(h.each()), blank(o.each()))
                        && o.columns().values().stream().allMatch(oc -> sameColumn(h.columns().get(oc.name()), oc))) {
                    if (match == null || h.name().equals(t)) {
                        match = h;
                    }
                }
            }
            if (match == null) {
                return Optional.empty();
            }
            map.put(t, match.name());
        }
        map.entrySet().removeIf(e -> e.getKey().equals(e.getValue()));
        return Optional.of(map);
    }

    private static boolean sameColumn(Dataset.Column h, Dataset.Column o) {
        return h != null && Objects.equals(h.type(), o.type()) && Objects.equals(h.path(), o.path()) && Objects.equals(h.expr(), o.expr());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? "" : s;
    }

    /** The measures a composed or derived dataset declares itself (the engine adds one per input measure, which are not its own). */
    static Set<String> declared(JsonNode root) {
        Set<String> out = new LinkedHashSet<>();
        root.path("measures").fieldNames().forEachRemaining(out::add);
        return out;
    }

    // ---- derived -------------------------------------------------------------------------------------------------------

    /** The Python dataset and model of a derived dataset, or the problem; {@code bindings} gives each input dataset's model. */
    static Optional<String> derived(Dataset d, JsonNode root, String header, Function<String, Optional<Binding>> bindings, DatasetSplitter.Split into, List<String> manual) {
        Dataset.Derived spec = d.derived();
        String name = DatasetSplitter.datasetName(d.name(), Dataset.RESULT);
        StringBuilder b = new StringBuilder(header);
        b.append("rachana: 2\nexpressions: 2                  # DEL v2, the Python-like expression language (docs/guides/EXPRESSIONS.md)\n");
        b.append("dataset: ").append(name).append('\n');
        b.append("version: ").append(into.model.version).append('\n');
        b.append("title: ").append(YamlText.quoted(DatasetSplitter.text(root, "title", d.name()) + ": the Transform's table")).append('\n');
        b.append("description: ").append(YamlText.quoted("The table the Python Transform " + spec.transform() + " makes from model queries (the derived dataset "
                + d.name() + " before 2.2).")).append('\n');
        for (String k : List.of("owner", "sample", "endorsement", "tags", "audience")) {
            if (into.model.catalogue.containsKey(k)) {
                b.append(k).append(": ").append(into.model.catalogue.get(k)).append('\n');
            }
        }
        b.append("source:\n  python: ").append(YamlText.scalar(spec.transform())).append("\n  inputs:                       # each a model query, asked as the reader (their row rules and masks)\n");
        for (Dataset.Input in : spec.inputs().values()) {
            Optional<Binding> bound = bindings.apply(in.dataset());
            if (bound.isEmpty()) {
                return Optional.of("DRS-9190 the input '" + in.alias() + "' reads '" + in.dataset() + "', which this run does not convert to a model");
            }
            Binding bd = bound.get();
            List<String> fields = new ArrayList<>();
            List<String> renames = new ArrayList<>();
            for (Dataset.Named n : in.rows() ? in.fields() : in.by()) {
                String f = Objects.requireNonNullElse(bd.rename(n.source()), n.source());
                fields.add(f);
                if (n.as() != null) {
                    renames.add(f.substring(f.lastIndexOf('.') + 1) + " -> " + n.as());
                }
            }
            List<String> measures = new ArrayList<>();
            for (Dataset.Named n : in.measures()) {
                String x = Objects.requireNonNullElse(bd.rename(n.source()), n.source());
                measures.add(x);
                if (n.as() != null) {
                    renames.add(x + " -> " + n.as());
                }
            }
            Map<String, String> select = new LinkedHashMap<>();
            select.put("fields", YamlText.list(fields));
            select.put("measures", measures.isEmpty() ? null : YamlText.list(measures));
            b.append("    ").append(YamlText.key(in.alias())).append(": { model: ").append(bd.model()).append(", select: ").append(YamlText.flow(select)).append(" }\n");
            if (!renames.isEmpty()) {
                manual.add("the Transform " + spec.transform() + " now receives input '" + in.alias() + "' with each column named by the last part of its field or measure (counterparties.id is id); the derived dataset renamed "
                        + String.join(", ", renames) + ": rename them at the top of its function");
            }
        }
        if (spec.params() != null && !spec.params().isEmpty()) {
            b.append("  params: ").append(YamlText.node(DatasetSplitter.YAML.valueToTree(spec.params()))).append('\n');
        }
        Dataset.Table result = d.tables().get(Dataset.RESULT);
        List<String> cols = result == null ? List.of() : new ArrayList<>(result.columns().keySet());
        if (cols.isEmpty()) {
            return Optional.of("DRS-9190 the derived dataset declares no columns");
        }
        b.append("grain: { keys: [").append(YamlText.scalar(cols.get(0))).append("] }   # the converter takes the first column as the key: check it says what one row is\n");
        b.append("columns:\n");
        int w = cols.stream().mapToInt(String::length).max().orElse(0);
        for (Dataset.Column c : result.columns().values()) {
            b.append("  ").append(ModelText.pad(YamlText.key(c.name()) + ":", w + 1)).append(" { type: ").append(c.type()).append(" }\n");
        }
        into.datasets.add(new DatasetSplitter.Output("dataset", name, "datasets/" + name + ".dataset.yaml", b.toString()));
        into.tableDataset.put(Dataset.RESULT, name);
        into.model.datasets.put(Dataset.RESULT, new String[] {name, "fact"});
        for (Dataset.Measure m : d.measures().values().stream().filter(x -> declared(root).contains(x.name())).toList()) {
            into.model.measures.put(m.name(), new ModelText.Measure(m.name(), DatasetSplitter.measure(m), m.expr(), null));
        }
        d.hierarchies().forEach((k, v) -> into.model.hierarchies.put(k, List.copyOf(v)));
        manual.add("the Python dataset " + name + " takes its first column (" + cols.get(0) + ") as its key: check `grain:` says what one row is");
        return Optional.empty();
    }
}
