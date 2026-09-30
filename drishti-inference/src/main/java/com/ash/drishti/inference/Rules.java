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
package com.ash.drishti.inference;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.NodeType;
import com.ash.drishti.rachana.model.Area;
import com.ash.drishti.rachana.model.Column;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.SourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** The built-in inference rules (ARCHITECTURE §6). Each is a small, stateless strategy. */
public final class Rules {

    private Rules() {}

    public static List<InferenceRule> builtIn() {
        return List.of(new LegsRule(), new HomogeneousArrayRule(), new TermStructureRule(), new DistributionRule(),
                new TimeSeriesRule(), new NestedObjectRule());
    }

    static final SourceLocation INFERRED = new SourceLocation("inferred", 0, 0);

    static Panel panel(String id, PanelKind kind, String title, Area area, List<Column> columns, Map<String, Object> options) {
        return new Panel(id, kind, title, null, null, area, true, columns, null, options, INFERRED);
    }

    static String slug(String path) {
        return path.replace("$.", "").replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "").toLowerCase();
    }

    /** Visits arrays at the top level and inside the first element of small object arrays ({@code $.legs[0].cashflows}). */
    static void arrays(DataNode doc, BiConsumer<String, DataNode.Arr> visit) {
        if (!(doc instanceof DataNode.Obj o)) {
            return;
        }
        o.fields().forEach((k, v) -> {
            if (v instanceof DataNode.Arr a) {
                visit.accept("$." + k, a);
                if (a.size() > 0 && a.size() <= 4 && a.get(0) instanceof DataNode.Obj first) {
                    first.fields().forEach((k2, v2) -> {
                        if (v2 instanceof DataNode.Arr inner) {
                            visit.accept("$." + k + "[0]." + k2, inner);
                        }
                    });
                }
            }
        });
    }

    static boolean objects(DataNode.Arr a) {
        return a.size() > 0 && a.elements().stream().allMatch(e -> e.type() == NodeType.OBJECT);
    }

    /** Scalar field names of the first row, in order. */
    static List<String> scalarKeys(DataNode.Arr a) {
        List<String> keys = new ArrayList<>();
        if (a.get(0) instanceof DataNode.Obj o) {
            o.fields().forEach((k, v) -> {
                if (v.type().isScalar()) {
                    keys.add(k);
                }
            });
        }
        return keys;
    }

    static String title(String path) {
        String last = path.substring(path.lastIndexOf('.') + 1);
        String t = Semantics.humanize(last);
        return path.contains("[0].") ? t + " · " + Semantics.humanize(path.substring(2, path.indexOf('['))) + " 1" : t;
    }

    /** 2-4 structurally similar objects with many fields: legs, tranches. Shown as tabs of kv panels. */
    static final class LegsRule implements InferenceRule {
        public String name() {
            return "LegsRule";
        }

        public void propose(RuleContext c, List<Candidate> out) {
            if (!(c.doc() instanceof DataNode.Obj o)) {
                return;
            }
            int[] order = {0};
            o.fields().forEach((k, v) -> {
                order[0]++;
                if (v instanceof DataNode.Arr a && a.size() >= 2 && a.size() <= 4 && objects(a) && a.get(0).size() >= 5) {
                    Panel body = new Panel("body", PanelKind.KV, null, null, null, Area.MAIN, true,
                            c.columns().forObject(a.get(0), "@."), null, Map.of(), INFERRED);
                    Map<String, Object> opts = new LinkedHashMap<>();
                    opts.put("each", "$." + k);
                    opts.put("tabTitle", "'" + Semantics.humanize(k).replaceAll("s$", "") + " ' + (#index + 1)");
                    Panel p = new Panel(slug(k), PanelKind.TABS, Semantics.humanize(k), null, null, Area.MAIN, true,
                            List.of(), body, opts, INFERRED);
                    out.add(new Candidate(p, 0.9, name(), a.size() + " similar objects with " + a.get(0).size() + " fields",
                            "$." + k, order[0] * 10));
                }
            });
        }
    }

    /** An array of objects sharing most keys: a table. */
    static final class HomogeneousArrayRule implements InferenceRule {
        public String name() {
            return "HomogeneousArrayRule";
        }

        public void propose(RuleContext c, List<Candidate> out) {
            int[] order = {0};
            arrays(c.doc(), (path, a) -> {
                order[0]++;
                if (!objects(a)) {
                    return;
                }
                List<Column> cols = c.columns().forRows(a);
                if (cols.isEmpty()) {
                    return;
                }
                Map<String, Object> opts = new LinkedHashMap<>();
                opts.put("rows", path);
                if (cols.stream().anyMatch(Column::total)) {
                    opts.put("totalLabel", "Total");
                }
                double score = 0.55 + 0.1 * Math.min(a.size(), 10) / 10.0;
                out.add(new Candidate(panel(slug(path), PanelKind.TABLE, title(path), Area.MAIN, cols, opts), score, name(),
                        a.size() + " rows × " + cols.size() + " columns", path, order[0] * 10 + 1));
            });
        }
    }

    /** Rows keyed by tenor or contract month with numeric values: a curve, or an area chart for several series. */
    static final class TermStructureRule implements InferenceRule {
        public String name() {
            return "TermStructureRule";
        }

        public void propose(RuleContext c, List<Candidate> out) {
            int[] order = {0};
            arrays(c.doc(), (path, a) -> {
                order[0]++;
                if (a.size() < 3 || !objects(a)) {
                    return;
                }
                String x = null;
                List<String> ys = new ArrayList<>();
                for (String k : scalarKeys(a)) {
                    DataNode v = a.get(0).get(k);
                    if (x == null && v.type() == NodeType.STRING && c.semantics().isTenor(v.asText())) {
                        x = k;
                    } else if (v.type() == NodeType.NUMBER) {
                        ys.add(k);
                    }
                }
                if (x == null || ys.isEmpty()) {
                    return;
                }
                Map<String, Object> opts = new LinkedHashMap<>();
                opts.put("rows", path);
                opts.put("x", x);
                if (ys.size() >= 2) {
                    List<Object> series = new ArrayList<>();
                    String[] tones = {"link", "accent", "pos", "neg"};
                    for (int i = 0; i < Math.min(ys.size(), 4); i++) {
                        series.add(Map.of("label", Semantics.humanize(ys.get(i)), "value", ys.get(i), "tone", tones[i]));
                    }
                    opts.put("series", series);
                    out.add(new Candidate(panel(slug(path), PanelKind.AREA, title(path), Area.MAIN, List.of(), opts), 0.85,
                            name(), ys.size() + " series over " + a.size() + " tenors", path, order[0] * 10 + 2));
                    return;
                }
                Role r = c.semantics().role(ys.get(0), a.get(0).get(ys.get(0)));
                boolean sensitivity = r.name().contains("money");
                opts.put("y", ys.get(0));
                out.add(new Candidate(panel(slug(path), PanelKind.LINE, title(path), Area.RIGHT, List.of(), opts),
                        sensitivity ? 0.5 : 0.82, name(), a.size() + " points keyed by " + x, path, order[0] * 10 + 2));
            });
        }
    }

    /** A short list of label → amount (sensitivities, risk by factor): horizontal bars. */
    static final class DistributionRule implements InferenceRule {
        public String name() {
            return "DistributionRule";
        }

        public void propose(RuleContext c, List<Candidate> out) {
            int[] order = {0};
            arrays(c.doc(), (path, a) -> {
                order[0]++;
                if (a.size() < 2 || a.size() > 12 || !objects(a) || scalarKeys(a).size() != 2) {
                    return;
                }
                List<String> keys = scalarKeys(a);
                DataNode first = a.get(0);
                String label = first.get(keys.get(0)).type() == NodeType.STRING ? keys.get(0) : keys.get(1);
                String value = label.equals(keys.get(0)) ? keys.get(1) : keys.get(0);
                if (first.get(value).type() != NodeType.NUMBER) {
                    return;
                }
                Role r = c.semantics().role(value, first.get(value));
                Map<String, Object> opts = new LinkedHashMap<>();
                opts.put("rows", path);
                opts.put("label", label);
                opts.put("value", value);
                opts.put("fmt", r.fmt() == null ? "amount0" : r.fmt());
                if (r.tone() != null) {
                    opts.put("tone", r.tone());
                }
                double score = r.name().contains("money") ? 0.8 : 0.45;
                out.add(new Candidate(panel(slug(path), PanelKind.HBAR, title(path), Area.RIGHT, List.of(), opts), score,
                        name(), a.size() + " " + Semantics.humanize(label).toLowerCase() + " values", path, order[0] * 10 + 3));
            });
        }
    }

    /** Rows with a leading date, in date order: a ladder that highlights the latest row. */
    static final class TimeSeriesRule implements InferenceRule {
        public String name() {
            return "TimeSeriesRule";
        }

        public void propose(RuleContext c, List<Candidate> out) {
            int[] order = {0};
            arrays(c.doc(), (path, a) -> {
                order[0]++;
                if (a.size() < 3 || !objects(a)) {
                    return;
                }
                List<String> keys = scalarKeys(a);
                if (keys.isEmpty() || !c.semantics().isDate(a.get(0).get(keys.get(0)).asText())) {
                    return;
                }
                String d = keys.get(0);
                for (int i = 1; i < a.size(); i++) {
                    if (a.get(i).get(d).asText().compareTo(a.get(i - 1).get(d).asText()) < 0) {
                        return;
                    }
                }
                Map<String, Object> opts = new LinkedHashMap<>();
                opts.put("rows", path);
                opts.put("highlight", "#index == " + (a.size() - 1));
                out.add(new Candidate(panel(slug(path), PanelKind.LADDER, title(path), Area.MAIN, c.columns().forRows(a), opts),
                        0.72, name(), a.size() + " dated rows in order", path, order[0] * 10 + 4));
            });
        }
    }

    /** A nested object of scalars (terms, margin, confirmation): a kv panel. */
    static final class NestedObjectRule implements InferenceRule {
        public String name() {
            return "NestedObjectRule";
        }

        public void propose(RuleContext c, List<Candidate> out) {
            if (!(c.doc() instanceof DataNode.Obj o)) {
                return;
            }
            int[] order = {0};
            o.fields().forEach((k, v) -> {
                order[0]++;
                if (!(v instanceof DataNode.Obj inner) || inner.size() < 2) {
                    return;
                }
                long scalars = inner.fields().values().stream().filter(x -> x.type().isScalar()).count();
                if (scalars < 2 || (inner.fields().containsKey("name") && inner.size() <= 2)) {
                    return;
                }
                Map<String, Object> opts = new LinkedHashMap<>();
                opts.put("rows", "$." + k);
                Area area = scalars <= 6 ? Area.RIGHT : Area.MAIN;
                out.add(new Candidate(panel(slug(k), PanelKind.KV, Semantics.humanize(k), area,
                        c.columns().forObject(inner, "@."), opts), 0.55, name(), scalars + " fields", "$." + k, order[0] * 10 + 5));
            });
        }
    }
}
