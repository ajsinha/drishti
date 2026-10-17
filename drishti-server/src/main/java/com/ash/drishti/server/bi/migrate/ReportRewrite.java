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

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * Step 5 of the converter for a report (docs/architecture/BI_MODEL_SPEC.md sections 4.1, 4.3 and 8.2): {@code dataset: X} becomes {@code model: X@1},
 * {@code rachana: 2}; a visual's own {@code dataset:} that ends in the same model is removed; {@code links:} are removed when every visual reads one model
 * (D2: links only cross owners); the fields and measures a composed dataset's frame called otherwise are renamed in wells, filters, sorts, formats and
 * bookmarks (never in titles or texts); and a composed left join's meaning is kept with a report filter on the left source's count. The edits are made in
 * the original text, so comments, key order and layout survive. Pure.
 */
final class ReportRewrite {

    /** The rewritten report, what changed, what the person still has to do, and why it could not be converted (null when it could). */
    record Result(String yaml, List<String> changes, List<String> manual, String problem) {}

    private record Edit(int start, int end, String text) {}

    /** Keys whose values are words for people or ids, never a field or measure name. */
    private static final Set<String> TEXT_KEYS = Set.of("id", "type", "title", "alt", "text", "description", "src", "hierarchy", "name", "page", "mode", "palette",
            "background", "op", "dir", "colour", "color", "theme", "action");

    private ReportRewrite() {}

    static Result rewrite(String yaml, Function<String, Optional<Binding>> bindings) {
        return rewrite(yaml, bindings, (ref, model) -> Optional.empty());
    }

    /**
     * As {@link #rewrite(String, Function)}; {@code covered} answers, for a visual's own dataset and the report's model, how that model reads the dataset's
     * tables when a composed dataset was folded into it (the visual then reads the report's model).
     */
    static Result rewrite(String yaml, Function<String, Optional<Binding>> bindings, java.util.function.BiFunction<String, String, Optional<Binding>> covered) {
        LoaderOptions options = new LoaderOptions();
        Node root = new Yaml(options).compose(new StringReader(yaml));
        if (!(root instanceof MappingNode top)) {
            return new Result(yaml, List.of(), List.of(), "not a report");
        }
        NodeTuple dataset = tuple(top, "dataset");
        if (dataset == null || !(dataset.getValueNode() instanceof ScalarNode ds)) {
            return new Result(yaml, List.of(), List.of(), null);                           // bound to a model already
        }
        Optional<Binding> found = bindings.apply(ds.getValue());
        if (found.isEmpty()) {
            return new Result(yaml, List.of(), List.of(), null);                           // its dataset is not converted in this run
        }
        Binding b = found.get();
        if (b.live()) {
            return new Result(yaml, List.of(), List.of("the report reads the live dataset " + ds.getValue() + " (now also the model " + b.model() + "): it stays on the dataset "
                    + "until a report on a model follows live data (standing queries, wave W3); then bind it with model: " + b.model()), null);
        }
        List<Edit> edits = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        List<String> manual = new ArrayList<>();
        NodeTuple rachana = tuple(top, "rachana");
        if (rachana != null && rachana.getValueNode() instanceof ScalarNode r && "1".equals(r.getValue())) {
            edits.add(new Edit(start(r), end(r), "2"));
        }
        edits.add(new Edit(start(dataset.getKeyNode()), end(ds), "model: " + b.model()));
        int eol = yaml.indexOf('\n', end(ds));
        String rest = yaml.substring(end(ds), eol < 0 ? yaml.length() : eol);
        int hash = rest.indexOf('#');
        if (hash >= 0 && rest.substring(0, hash).isBlank() && rest.contains("dataset")) { // a comment about the dataset line now describes a model
            edits.add(new Edit(end(ds) + hash, end(ds) + rest.length(), "# the model it reads: its name and major version (BI_MODEL_SPEC.md)"));
        }
        changes.add("dataset: " + ds.getValue() + " -> model: " + b.model());
        boolean oneModel = true;
        NodeTuple pages = tuple(top, "pages");
        if (pages != null && pages.getValueNode() instanceof SequenceNode ps) {
            for (Node p : ps.getValue()) {
                NodeTuple visuals = p instanceof MappingNode pm ? tuple(pm, "visuals") : null;
                if (p instanceof MappingNode pm) {
                    for (NodeTuple t : pm.getValue()) {
                        if (!"visuals".equals(key(t))) {
                            renames(t.getValueNode(), key(t), b, yaml, edits, changes);
                        }
                    }
                }
                if (visuals == null || !(visuals.getValueNode() instanceof SequenceNode vs)) {
                    continue;
                }
                for (Node v : vs.getValue()) {
                    if (!(v instanceof MappingNode vm)) {
                        continue;
                    }
                    Binding vb = b;
                    NodeTuple own = tuple(vm, "dataset");
                    if (own != null && own.getValueNode() instanceof ScalarNode od) {
                        Optional<Binding> ob = bindings.apply(od.getValue());
                        if (ob.isPresent() && !ob.get().model().equals(b.model())) {
                            ob = covered.apply(od.getValue(), b.model()).or(() -> bindings.apply(od.getValue()));
                        }
                        if (ob.isPresent() && ob.get().model().equals(b.model())) {
                            vb = ob.get();
                            edits.add(remove(vm, own, yaml));
                            changes.add("visual " + text(vm, "id") + ": its dataset " + od.getValue() + " is in the model " + b.model() + " (dataset: removed)");
                        } else {
                            oneModel = false;
                            manual.add("visual " + text(vm, "id") + " reads " + od.getValue() + (ob.isPresent() ? ", now the model " + ob.get().model() : "")
                                    + ", another model: a report reads one model; keep it as a cross-owner link (BI_MODEL_SPEC.md 4.3) or move the visual to a report of its own");
                            continue;
                        }
                    }
                    for (NodeTuple t : vm.getValue()) {
                        if (!"dataset".equals(key(t))) {
                            renames(t.getValueNode(), key(t), vb, yaml, edits, changes);
                        }
                    }
                }
            }
        }
        for (String k : List.of("filters", "bookmarks", "layouts", "tests")) {
            NodeTuple t = tuple(top, k);
            if (t != null) {
                renames(t.getValueNode(), k, b, yaml, edits, changes);
            }
        }
        NodeTuple links = tuple(top, "links");
        if (links != null) {
            if (oneModel) {
                edits.add(remove(top, links, yaml));
                changes.add("links: removed (every visual reads " + b.model() + ")");
            } else {
                renames(links.getValueNode(), "links", b, yaml, edits, changes);
            }
        }
        if (b.having() != null) {
            if (tuple(top, "filters") != null) {
                manual.add("add the report filter { field: " + b.having() + ", op: gt, value: 0 } to keep the left join's meaning (BI_MODEL_SPEC.md 8.5)");
            } else if (pages != null) {
                int at = lineStart(yaml, start(pages.getKeyNode()));
                edits.add(new Edit(at, at, "filters:                                 # the converter: the composed dataset kept only the rows of its left source\n"
                        + "  - { field: " + YamlText.scalar(b.having()) + ", op: gt, value: 0 }\n\n"));
                changes.add("filters: + { field: " + b.having() + ", op: gt, value: 0 } (the left join's meaning)");
            }
        }
        List<Edit> kept = edits.stream().filter(e -> edits.stream().noneMatch(r -> r != e && r.start() <= e.start() && e.end() <= r.end()
                && r.end() - r.start() > e.end() - e.start() && !(e.start() == e.end() && e.start() == r.end()))).sorted(Comparator.comparingInt(Edit::start).reversed()).toList();   // none inside a removed region
        StringBuilder out = new StringBuilder(yaml);
        for (Edit e : kept) {
            out.replace(e.start(), e.end(), e.text());
        }
        return new Result(out.toString(), changes, manual, null);
    }

    /** Renames the scalars under {@code n} that name a renamed field or measure, except under keys that hold words for people. */
    private static void renames(Node n, String key, Binding b, String src, List<Edit> edits, List<String> changes) {
        if (key != null && TEXT_KEYS.contains(key)) {
            return;
        }
        switch (n) {
            case ScalarNode s -> {
                String to = b.rename(s.getValue());
                if (to != null && !to.equals(s.getValue())) {
                    edits.add(new Edit(start(s), end(s), scalar(s, to)));
                    changes.add(s.getValue() + " -> " + to);
                }
            }
            case SequenceNode q -> q.getValue().forEach(x -> renames(x, null, b, src, edits, changes));
            case MappingNode m -> m.getValue().forEach(t -> renames(t.getValueNode(), key(t), b, src, edits, changes));
            default -> { }
        }
    }

    private static String scalar(ScalarNode s, String to) {
        return switch (s.getScalarStyle()) {
            case SINGLE_QUOTED -> "'" + to.replace("'", "''") + "'";
            case DOUBLE_QUOTED -> YamlText.quoted(to);
            default -> YamlText.scalar(to);
        };
    }

    /** The edit that removes one entry of a mapping: its lines in block style, its text and comma in flow style. */
    private static Edit remove(MappingNode m, NodeTuple t, String src) {
        int ks = start(t.getKeyNode());
        int ls = lineStart(src, ks);
        if (src.substring(ls, ks).isBlank() && m.getFlowStyle() != DumperOptions.FlowStyle.FLOW) {
            int idx = m.getValue().indexOf(t);
            int end;
            if (idx + 1 < m.getValue().size()) {
                end = lineStart(src, start(m.getValue().get(idx + 1).getKeyNode()));
            } else {
                int ve = end(t.getValueNode());
                int nl = src.indexOf('\n', Math.max(ve - 1, 0));
                end = nl < 0 ? src.length() : nl + 1;
                if (ve > 0 && src.charAt(ve - 1) == '\n') {
                    end = ve;
                }
            }
            return new Edit(ls, end, "");
        }
        int ve = end(t.getValueNode());
        int before = ks - 1;
        while (before >= 0 && src.charAt(before) == ' ') {
            before--;
        }
        if (before >= 0 && src.charAt(before) == ',') {
            return new Edit(before, ve, "");
        }
        int after = ve;
        while (after < src.length() && (src.charAt(after) == ' ' || src.charAt(after) == ',')) {
            after++;
        }
        return new Edit(ks, after, "");
    }

    private static int lineStart(String src, int at) {
        int nl = src.lastIndexOf('\n', Math.max(0, at - 1));
        return nl < 0 ? 0 : nl + 1;
    }

    private static NodeTuple tuple(MappingNode m, String key) {
        for (NodeTuple t : m.getValue()) {
            if (key.equals(key(t))) {
                return t;
            }
        }
        return null;
    }

    private static String key(NodeTuple t) {
        return t.getKeyNode() instanceof ScalarNode k ? k.getValue() : null;
    }

    private static String text(MappingNode m, String key) {
        NodeTuple t = tuple(m, key);
        return t != null && t.getValueNode() instanceof ScalarNode s ? s.getValue() : "?";
    }

    private static int start(Node n) {
        return n.getStartMark().getIndex();
    }

    private static int end(Node n) {
        return n.getEndMark().getIndex();
    }
}
