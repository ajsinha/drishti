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
package com.ash.drishti.engine.design;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.engine.shape.BuilderProperties;
import com.ash.drishti.inference.ColumnInference;
import com.ash.drishti.inference.PanelRecipes;
import com.ash.drishti.inference.Role;
import com.ash.drishti.inference.Semantics;
import com.ash.drishti.rachana.model.Area;
import com.ash.drishti.rachana.model.Column;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The panel-choice rules, from a shape: for one field (or a dimension and measure pair) the panel kinds that suit it,
 * best first, each with its options filled and a reason. Runtime inference's rules chose from one document; these choose
 * from the roles of a shape and what the samples say (series length, value variety), reusing inference's recipes
 * ({@link PanelRecipes}), column rules ({@link ColumnInference}) and field semantics ({@link Semantics}), so a screen
 * drafted here and the one inference would show use the same options. Stateless apart from its inputs: thread-safe.
 */
final class PanelChooser {

    private final Semantics semantics;
    private final ColumnInference columns;
    private final BuilderProperties props;
    private final SampleStats stats;
    private final ShapeModel model;

    PanelChooser(Semantics semantics, BuilderProperties props, SampleStats stats, ShapeModel model) {
        this.semantics = semantics;
        this.columns = new ColumnInference(semantics);
        this.props = props;
        this.stats = stats;
        this.model = model;
    }

    // ----------------------------------------------------------------------------------------------------- entry

    /** Ranked choices for the field at {@code f}; empty when no panel is worth drawing for it. */
    List<PanelChoice> choose(FieldNode f) {
        if (f.path().contains("[]") && f.scalar()) {
            return leaf(f);
        }
        List<PanelChoice> out = switch (f.roleName()) {
            case "series" -> series(f);
            case "ohlc" -> ohlc(f);
            case "grid" -> grid(f);
            case "steps" -> steps(f);
            case "distribution" -> distribution(f);
            case "graph" -> graph(f);
            case "tree" -> f.array() ? tree(f) : List.of();
            case "events" -> events(f);
            case "table" -> table(f);
            case "link" -> links(f);
            default -> generic(f);
        };
        return ranked(out);
    }

    /** Ranked choices for a dimension and a measure (or two measures) of the same rows, in either order. */
    List<PanelChoice> pair(FieldNode a, FieldNode b) {
        FieldNode rows = parentRows(a);
        if (rows == null || !a.path().startsWith(rows.path()) || !b.path().startsWith(rows.path() + "[].")) {
            return ranked(List.of(kvOf(List.of(a, b), "the two fields, side by side")));
        }
        FieldNode dim = a.is("dimension") || a.is("status") ? a : b.is("dimension") || b.is("status") ? b : null;
        FieldNode measure = a.is("measure") ? a : b.is("measure") ? b : null;
        List<PanelChoice> out = new ArrayList<>();
        if (dim != null && measure != null) {
            PanelChoice pv = pivot(rows, dim, measure, 0.85, "dimension '" + dim.name() + "' by measure '" + measure.name() + "'");
            if (pv != null) {
                out.add(pv);
            }
            if (distinctOf(dim) <= 12) {
                Role r = roleOf(measure);
                out.add(choice("hbar", 0.7, "'" + dim.name() + "' has few values: one bar each for '" + measure.name() + "'", Area.MAIN,
                        title(measure) + " by " + title(dim).toLowerCase(Locale.ROOT), PanelRecipes.hbar(rows.path(), dim.name(), measure.name(), r), List.of()));
            }
            out.add(tableOf(rows, List.of(dim, measure), 0.5, "just the two columns"));
        } else if (a.is("measure") && b.is("measure")) {
            FieldNode label = rows.props().values().stream().filter(p -> "string".equals(p.type())).findFirst().orElse(null);
            out.add(scatter(rows, a, b, label, 0.8, "two measures per row: one point per row"));
            out.add(tableOf(rows, List.of(a, b), 0.4, "just the two columns"));
        } else {
            out.add(tableOf(rows, List.of(a, b), 0.6, "the two fields as columns"));
        }
        return ranked(out);
    }

    // ----------------------------------------------------------------------------------------------------- series

    private List<PanelChoice> series(FieldNode f) {
        List<FieldNode> nums = numbers(f);
        FieldNode axis = f.props().values().stream().filter(p -> "string".equals(p.type()) && p.date()).findFirst()
                .orElseGet(() -> f.props().values().stream().filter(p -> "string".equals(p.type())).findFirst().orElse(null));
        if (axis == null || nums.isEmpty()) {
            return table(f);
        }
        boolean dated = axis.date();
        int pts = points(f);
        int extras = f.props().size() - 1 - nums.size();
        String what = "'" + axis.name() + "' (" + (dated ? "date" : "tenor") + ") with " + nums.size() + " number column"
                + (nums.size() == 1 ? "" : "s") + ", " + (pts < 0 ? "length unknown" : pts + " points") + over(f);
        FieldNode y = nums.get(0);
        List<PanelChoice> out = new ArrayList<>();
        PanelChoice line = choice("line", 0.9, what + ": a line over time", Area.MAIN, title(f),
                PanelRecipes.line(f.path(), axis.name(), y.name()), List.of());
        PanelChoice area = choice("area", nums.size() >= 2 ? 0.85 : 0.6, what + ": " + nums.size() + " series on one axis", Area.MAIN,
                title(f), PanelRecipes.area(f.path(), axis.name(), nums.stream().map(FieldNode::name).toList()), List.of());
        PanelChoice ladder = choice("ladder", 0.5, what + ": every row, latest highlighted", Area.MAIN, title(f),
                PanelRecipes.ladder(f.path(), Math.max(pts, 1)), columnsOf(f));
        PanelChoice tbl = tableOf(f, new ArrayList<>(f.props().values()), 0.45, "every column of the rows");
        if (dated) {
            boolean enough = pts < 0 || pts >= props.lineMinPoints();
            if (enough && extras == 0 && nums.size() == 1) {
                out.add(line);
                out.add(area);
                out.add(ladder);
            } else if (enough && extras == 0) {
                out.add(area.withScore(0.85, what + ": " + nums.size() + " series on one axis"));
                out.add(line.withScore(0.7, what + ": only the first measure, '" + y.name() + "'"));
                out.add(ladder);
            } else {
                String why = pts >= 0 && pts < props.lineMinPoints() ? "only " + pts + " points: read them as rows"
                        : "dated rows with other columns: read them as rows";
                out.add(ladder.withScore(0.8, what + "; " + why));
                out.add(line.withScore(pts >= 3 || pts < 0 ? 0.6 : 0.2, what + ": chart '" + y.name() + "' over time"));
                out.add(tbl);
            }
        } else if (nums.size() >= 2) {
            out.add(area);
            out.add(line.withScore(0.6, what + ": only the first measure, '" + y.name() + "'"));
            out.add(tbl);
        } else {
            Role r = roleOf(y);
            boolean signed = !Double.isNaN(y.min()) && y.min() < 0;
            boolean money = r.name().contains("money") || signed;
            PanelChoice bars = choice("hbar", money ? 0.8 : 0.6, what + (money ? ": signed amounts by bucket read best as bars" : ": bars by bucket"), Area.MAIN,
                    title(f), PanelRecipes.hbar(f.path(), axis.name(), y.name(), r), List.of());
            boolean few = pts >= 0 ? pts <= 12 : f.maxItems() <= 12;
            if (money && few) {
                out.add(bars);
                out.add(line.withScore(0.7, what + ": the same as a curve"));
            } else {
                out.add(line.withScore(0.82, what + ": a curve"));
                if (few) {
                    out.add(bars);
                }
            }
            out.add(tbl);
        }
        return out;
    }

    private List<PanelChoice> ohlc(FieldNode f) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("rows", f.path());
        FieldNode x = f.props().values().stream().filter(p -> "string".equals(p.type())).findFirst().orElse(null);
        if (x != null) {
            o.put("x", x.name());
        }
        FieldNode volume = find(f, "volume");
        if (volume != null) {
            o.put("volume", volume.name());
        }
        for (String n : props.ohlcNames()) {
            FieldNode p = find(f, n);
            if (p != null && !p.name().equals(n)) {
                o.put(n, p.name());
            }
        }
        FieldNode close = find(f, "close");
        Role r = close == null ? Role.PLAIN : roleOf(close);
        o.put("fmt", r.fmt() == null || "date".equals(r.fmt()) ? "price2" : r.fmt());
        String what = "records with " + String.join("/", props.ohlcNames()) + (volume == null ? "" : " and volume") + over(f);
        List<PanelChoice> out = new ArrayList<>();
        out.add(choice("candlestick", 0.95, what + ": candles", Area.MAIN, title(f), o, List.of()));
        if (close != null && x != null) {
            out.add(choice("line", 0.6, what + ": just the closing price as a line", Area.MAIN, title(f),
                    PanelRecipes.line(f.path(), x.name(), close.name()), List.of()));
        }
        out.add(tableOf(f, new ArrayList<>(f.props().values()), 0.4, "every column of the rows"));
        return out;
    }

    private List<PanelChoice> grid(FieldNode f) {
        List<FieldNode> nums = numbers(f);
        FieldNode y = f.props().values().stream().filter(p -> "string".equals(p.type())).findFirst().orElse(null);
        if (y == null) {
            return table(f);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("rows", f.path());
        o.put("y", y.name());
        List<Column> cols = new ArrayList<>();
        nums.forEach(n -> cols.add(new Column(Semantics.humanize(n.name()), "@." + n.name(), null, null, false, false)));
        List<PanelChoice> out = new ArrayList<>();
        out.add(choice("surface", 0.9, nums.size() + " number columns along '" + y.name() + "': a grid of values over two axes"
                + over(f), Area.MAIN, title(f), o, cols));
        out.add(tableOf(f, new ArrayList<>(f.props().values()), 0.6, "the same grid as a table"));
        return out;
    }

    private List<PanelChoice> steps(FieldNode f) {
        FieldNode label = f.props().values().stream().filter(p -> "string".equals(p.type())).findFirst().orElse(null);
        FieldNode value = numbers(f).stream().findFirst().orElse(null);
        if (label == null || value == null) {
            return table(f);
        }
        Role r = roleOf(value);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("rows", f.path());
        o.put("label", label.name());
        o.put("value", value.name());
        o.put("fmt", r.fmt() == null ? "signed0" : r.fmt());
        List<PanelChoice> out = new ArrayList<>();
        out.add(choice("waterfall", 0.9, "ordered steps with signed '" + value.name() + "': rises and falls as floating bars" + over(f),
                Area.MAIN, title(f), o, List.of()));
        out.add(choice("hbar", 0.6, "the same steps as plain bars", Area.MAIN, title(f), PanelRecipes.hbar(f.path(), label.name(), value.name(), r),
                List.of()));
        out.add(tableOf(f, new ArrayList<>(f.props().values()), 0.4, "the steps as rows"));
        return out;
    }

    private List<PanelChoice> distribution(FieldNode f) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("rows", f.path());
        FieldNode value = f.props().isEmpty() ? null : f.props().values().iterator().next();
        String col = value == null ? f.name() : value.name();
        if (value != null) {
            o.put("value", value.name());
        }
        Role r = semantics.role(col, DataNode.of(1000));
        if (r.fmt() != null && !"date".equals(r.fmt())) {
            o.put("fmt", r.fmt());
        }
        List<PanelChoice> out = new ArrayList<>();
        out.add(choice("histogram", 0.9, (value == null ? "a list of numbers" : "records with one measure, '" + col + "'")
                + ": binned to show how they spread" + over(f), Area.MAIN, title(f), o, List.of()));
        if (value != null) {
            out.add(tableOf(f, new ArrayList<>(f.props().values()), 0.4, "the values as rows"));
        }
        return out;
    }

    private List<PanelChoice> graph(FieldNode f) {
        FieldNode nodes = named(f, props.graphNodeNames());
        FieldNode edges = named(f, props.graphEdgeNames());
        if (nodes == null || edges == null) {
            return List.of();
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("nodes", nodes.path());
        o.put("edges", edges.path());
        List<PanelChoice> out = new ArrayList<>();
        out.add(choice("graph", 0.9, "'" + nodes.name() + "' and '" + edges.name() + "' lists: entities and the relations between them",
                Area.MAIN, title(f), o, List.of()));
        out.add(tableOf(nodes, new ArrayList<>(nodes.props().values()), 0.5, "the nodes as a table"));
        return out;
    }

    private List<PanelChoice> tree(FieldNode f) {
        String child = f.props().values().stream().filter(p -> p.tree()).map(FieldNode::name).findFirst().orElse(null);
        if (child == null) {
            return table(f);
        }
        List<Column> cols = columnsOf(f);
        Map<String, Object> o = PanelRecipes.table(f.path(), cols);
        o.put("children", "@." + child);
        o.put("expand", 1);
        List<PanelChoice> out = new ArrayList<>();
        out.add(choice("table", 0.9, "each row holds a list '" + child + "' of the same shape: a table that expands level by level" + over(f),
                Area.MAIN, title(f), o, cols));
        List<FieldNode> dims = withRole(f, "dimension");
        List<FieldNode> meas = withRole(f, "measure");
        if (!dims.isEmpty() && !meas.isEmpty()) {
            PanelChoice pv = pivot(f, dims.get(0), best(meas), 0.5, "the top level grouped by '" + dims.get(0).name() + "'");
            if (pv != null) {
                out.add(pv);
            }
        }
        out.add(choice("table", 0.4, "only the top level, flat", Area.MAIN, title(f), PanelRecipes.table(f.path(), cols), cols));
        return out;
    }

    private List<PanelChoice> events(FieldNode f) {
        FieldNode date = f.props().values().stream().filter(p -> "string".equals(p.type()) && p.date()).findFirst().orElse(null);
        List<FieldNode> texts = f.props().values().stream().filter(p -> "string".equals(p.type()) && !p.date()).toList();
        FieldNode label = texts.stream().filter(p -> props.labelNames().stream().anyMatch(l -> l.equalsIgnoreCase(p.name()))).findFirst()
                .orElse(texts.isEmpty() ? null : texts.get(0));
        if (date == null || label == null) {
            return table(f);
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("rows", f.path());
        if (!"date".equals(date.name())) {
            o.put("date", date.name());
        }
        o.put("label", label.name());
        texts.stream().filter(p -> p != label && !p.is("status")).findFirst().ifPresent(p -> o.put("detail", p.name()));
        texts.stream().filter(p -> p.is("status")).findFirst().ifPresent(p -> o.put("status", p.name()));
        List<PanelChoice> out = new ArrayList<>();
        out.add(choice("timeline", 0.9, "dated '" + label.name() + "' entries: events in order" + over(f), Area.MAIN, title(f), o, List.of()));
        out.add(tableOf(f, new ArrayList<>(f.props().values()), 0.55, "the events as rows"));
        return out;
    }

    // ------------------------------------------------------------------------------------------------------ table

    private List<PanelChoice> table(FieldNode f) {
        List<FieldNode> scal = scalars(f);
        if (scal.isEmpty()) {
            return List.of();
        }
        List<FieldNode> dims = new ArrayList<>(withRole(f, "dimension"));
        withRole(f, "status").forEach(dims::add);
        List<FieldNode> meas = withRole(f, "measure");
        int pts = points(f);
        int hi = pts >= 0 ? pts : f.maxItems();
        int lo = pts >= 0 ? pts : f.minItems();
        List<PanelChoice> out = new ArrayList<>();
        List<Column> cols = columnsOf(f);
        String what = f.props().size() + "-field records" + (pts < 0 ? "" : ", " + pts + " rows") + over(f);
        out.add(choice("table", 0.65, what + ": a table", Area.MAIN, title(f), PanelRecipes.table(f.path(), cols), cols));
        if (lo >= 2 && hi <= 4 && scal.size() >= 5) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("each", f.path());
            o.put("tabTitle", "'" + Semantics.humanize(f.name()).replaceAll("s$", "") + " ' + (#index + 1)");
            out.add(choice("tabs", 0.9, hi + " similar records with " + scal.size() + " fields each: one tab per record", Area.MAIN, title(f), o,
                    kvColumns(scal)));
        }
        if (dims.size() >= 2 && !meas.isEmpty() && (pts < 0 || pts >= 3)) {
            PanelChoice pv = pivot(f, dims, best(meas), 0.85, dims.size() + " dimensions and a measure");
            if (pv != null) {
                out.add(pv);
            }
        } else if (dims.size() == 1 && !meas.isEmpty()) {
            List<FieldNode> more = scal.stream().filter(p -> p != dims.get(0) && "string".equals(p.type())).toList();
            PanelChoice pv = more.isEmpty() ? null : pivot(f, List.of(dims.get(0), more.get(0)), best(meas), 0.55, "'" + dims.get(0).name() + "' by a measure");
            if (pv != null) {
                out.add(pv);
            }
        }
        if (scal.size() == 2 && hi >= 2 && hi <= 12) {
            FieldNode text = scal.stream().filter(p -> "string".equals(p.type())).findFirst().orElse(null);
            FieldNode num = scal.stream().filter(p -> "number".equals(p.type())).findFirst().orElse(null);
            if (text != null && num != null) {
                Role r = roleOf(num);
                boolean money = r.name().contains("money");
                out.add(choice("hbar", money ? 0.8 : 0.45, "a short list of label and amount (" + hi + " rows): bars", Area.MAIN, title(f),
                        PanelRecipes.hbar(f.path(), text.name(), num.name(), r), List.of()));
            }
        }
        if (meas.size() >= 2) {
            FieldNode label = scal.stream().filter(p -> "string".equals(p.type())).findFirst().orElse(null);
            out.add(scatter(f, meas.get(0), meas.get(1), label, 0.55, "two measures per row, '" + meas.get(0).name() + "' against '"
                    + meas.get(1).name() + "'"));
        }
        return out;
    }

    private PanelChoice tableOf(FieldNode rows, List<FieldNode> fields, double score, String reason) {
        List<FieldNode> scal = fields.stream().filter(p -> p.scalar() && p.path().startsWith(rows.path() + "[].")).toList();
        List<Column> cols = columnsFor(rows, scal);
        return choice("table", score, reason, Area.MAIN, title(rows), PanelRecipes.table(rows.path(), cols), cols);
    }

    private PanelChoice scatter(FieldNode rows, FieldNode x, FieldNode y, FieldNode label, double score, String reason) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("rows", rows.path());
        o.put("x", x.name());
        o.put("y", y.name());
        if (label != null) {
            o.put("label", label.name());
        }
        o.put("xLabel", Semantics.humanize(x.name()));
        o.put("yLabel", Semantics.humanize(y.name()));
        return choice("scatter", score, reason, Area.MAIN, title(y) + " against " + title(x).toLowerCase(Locale.ROOT), o, List.of());
    }

    /**
     * A pivot of {@code rows}: the dimensions with the fewest values go across and down the side, coarse to fine
     * ({@code by: [a, b]}); the rest of the order is by how many values each takes. Null with fewer than two dimensions
     * (a pivot needs an {@code across}).
     */
    private PanelChoice pivot(FieldNode rows, List<FieldNode> dims, FieldNode measure, double score, String reason) {
        if (dims.size() < 2 || measure == null) {
            return null;
        }
        List<FieldNode> sorted = new ArrayList<>(dims);
        sorted.sort(Comparator.comparingInt(this::distinctOf));
        FieldNode across = sorted.get(0);
        List<Object> by = new ArrayList<>();
        sorted.stream().skip(1).limit(3).forEach(d -> by.add(d.name()));
        Role r = roleOf(measure);
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("rows", rows.path());
        o.put("by", by.size() == 1 ? by.get(0) : by);
        o.put("across", across.name());
        o.put("value", measure.name());
        if (r.fmt() != null && !"date".equals(r.fmt())) {
            o.put("fmt", "compact");
        }
        if (r.tone() != null) {
            o.put("tone", r.tone());
        }
        if (by.size() > 1) {
            o.put("expand", 1);
        }
        String text = reason + ": '" + measure.name() + "' summed by " + String.join(" then ", by.stream().map(Object::toString).toList())
                + ", across '" + across.name() + "'";
        return choice("pivot", score, text + over(rows), Area.MAIN, title(measure) + " by " + String.join(", ", by.stream().map(Object::toString).toList()), o, List.of());
    }

    private PanelChoice pivot(FieldNode rows, FieldNode dim, FieldNode measure, double score, String reason) {
        List<FieldNode> others = rows.props().values().stream().filter(p -> p != dim && (p.is("dimension") || p.is("status"))).toList();
        if (others.isEmpty()) {
            return null;
        }
        return pivot(rows, List.of(dim, others.get(0)), measure, score, reason);
    }

    // ------------------------------------------------------------------------------------------ objects and scalars

    private List<PanelChoice> generic(FieldNode f) {
        if (f.object()) {
            return object(f);
        }
        if (f.array()) {
            return List.of();
        }
        if (!f.scalar()) {
            return List.of();
        }
        return scalarChoices(f);
    }

    private List<PanelChoice> object(FieldNode f) {
        List<FieldNode> scal = scalars(f);
        if (scal.size() < 2) {
            return List.of();
        }
        List<FieldNode> statuses = scal.stream().filter(p -> p.is("status")).toList();
        List<PanelChoice> out = new ArrayList<>();
        Area area = scal.size() <= 6 ? Area.RIGHT : Area.MAIN;
        PanelChoice kv = choice("kv", 0.55, scal.size() + " fields of '" + f.name() + "'" + over(f), area, title(f), PanelRecipes.kv(f.path()),
                kvColumns(scal));
        out.add(kv);
        if (!statuses.isEmpty()) {
            PanelChoice st = statusOf(statuses, title(f), statuses.size() == scal.size() ? 0.9 : 0.4,
                    statuses.size() + " of its fields are states");
            out.add(st);
        }
        return out;
    }

    /** The document's own top-level fields (not ids, links or prose) as one panel of labelled values; null when fewer than two. */
    PanelChoice details(FieldNode root) {
        List<FieldNode> own = scalars(root).stream().filter(p -> !p.is("id") && !p.is("link") && !p.is("text")
                && p.presence() >= props.rareBelow()).toList();
        if (own.size() < 2) {
            return null;
        }
        List<FieldNode> shown = own.subList(0, Math.min(own.size(), semantics.limit("kvMaxFields", 16)));
        PanelChoice kv = kvOf(shown, shown.size() + " top-level fields of the document, as labelled values");
        return kv.inArea(Area.MAIN).withTitle("Details").withScore(0.95, kv.reason());
    }

    PanelChoice statusOf(List<FieldNode> fields, String title, double score, String reason) {
        List<Object> list = new ArrayList<>();
        fields.forEach(p -> list.add(Map.of("label", Semantics.humanize(p.name()), "bind", p.path(), "tone", "status")));
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("fields", list);
        return choice("status", score, reason + ": each as a coloured state", Area.RIGHT, title, o, List.of());
    }

    private List<PanelChoice> scalarChoices(FieldNode f) {
        List<PanelChoice> out = new ArrayList<>();
        Role r = roleOf(f);
        switch (f.roleName()) {
            case "measure" -> {
                FieldNode limit = limitOf(f);
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("value", f.path());
                if (limit != null) {
                    o.put("max", limit.path());
                }
                if (r.fmt() != null && !"date".equals(r.fmt())) {
                    o.put("fmt", r.fmt());
                }
                out.add(choice("gauge", limit != null ? 0.85 : 0.6, limit != null ? "'" + limit.name() + "' sits beside it: how much of the limit is used"
                        : "one measure: a dial (set its maximum)", Area.RIGHT, title(f), o, List.of()));
            }
            case "status" -> out.add(statusOf(List.of(f), title(f), 0.8, "a state"));
            case "text" -> {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("text", "${" + f.path() + "}");
                out.add(choice("markdown", 0.8, "long text: shown as prose", Area.RIGHT, title(f), o, List.of()));
            }
            case "link" -> out.add(choice("links", 0.7, "refers to " + (f.role().kind() == null ? "another entity" : "a " + f.role().kind())
                    + ": opens it", Area.RIGHT, "Linked entities", new LinkedHashMap<>(), List.of()));
            default -> {
            }
        }
        out.add(kvOf(List.of(f), "the field as a labelled value"));
        return out;
    }

    private List<PanelChoice> links(FieldNode f) {
        List<PanelChoice> out = new ArrayList<>();
        out.add(choice("links", 0.8, f.role().reason() + ": every link in the document is listed and opens its entity", Area.RIGHT,
                "Linked entities", new LinkedHashMap<>(), List.of()));
        if (f.scalar()) {
            out.add(kvOf(List.of(f), "the field as a labelled value"));
        }
        return out;
    }

    private List<PanelChoice> leaf(FieldNode f) {
        FieldNode rows = parentRows(f);
        if (rows == null) {
            return scalarChoices(f);
        }
        List<PanelChoice> out = new ArrayList<>();
        if (f.is("measure")) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("rows", rows.path());
            o.put("value", f.name());
            Role r = roleOf(f);
            if (r.fmt() != null && !"date".equals(r.fmt())) {
                o.put("fmt", r.fmt());
            }
            out.add(choice("histogram", 0.7, "'" + f.name() + "' over every row: how the values spread", Area.MAIN, title(f) + " spread", o, List.of()));
            List<FieldNode> dims = withRole(rows, "dimension");
            if (!dims.isEmpty()) {
                PanelChoice pv = pivot(rows, dims.get(0), f, 0.65, "'" + f.name() + "' by '" + dims.get(0).name() + "'");
                if (pv != null) {
                    out.add(pv);
                }
            }
            out.add(tableOf(rows, List.of(f), 0.4, "the column on its own"));
        } else if (f.is("dimension") || f.is("status")) {
            List<FieldNode> meas = withRole(rows, "measure");
            if (!meas.isEmpty()) {
                out.addAll(pair(f, best(meas)));
            }
            out.add(tableOf(rows, List.of(f), 0.4, "the column on its own"));
        } else {
            out.add(tableOf(rows, List.of(f), 0.5, "the column on its own"));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private PanelChoice kvOf(List<FieldNode> fields, String reason) {
        List<Column> cols = new ArrayList<>();
        fields.forEach(p -> {
            Column c = columns.fieldColumn(p.name(), exemplar(p), "$.");
            cols.add(new Column(c.label(), p.path(), c.fmt(), c.tone(), false, false));
        });
        return choice("kv", 0.4, reason, Area.RIGHT, fields.size() == 1 ? title(fields.get(0)) : "Fields", new LinkedHashMap<>(), cols);
    }

    private List<Column> kvColumns(List<FieldNode> scal) {
        List<Column> cols = new ArrayList<>();
        int max = semantics.limit("kvMaxFields", 16);
        for (FieldNode p : scal) {
            if (cols.size() < max) {
                cols.add(columns.fieldColumn(p.name(), exemplar(p), "@."));
            }
        }
        return cols;
    }

    List<Column> columnsOf(FieldNode rows) {
        return columnsFor(rows, scalars(rows));
    }

    private List<Column> columnsFor(FieldNode rows, List<FieldNode> scal) {
        Map<String, DataNode> samples = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        double base = Math.max(rows.presence(), 1e-9);
        for (FieldNode p : scal) {
            samples.put(p.name(), exemplar(p));
            counts.put(p.name(), (int) Math.round(Math.min(1.0, p.presence() / base) * 100));
        }
        return columns.fromFields(samples, counts, 100);
    }

    /** A stand-in value of the field's kind, so inference's name-and-value semantics give it a format and tone. */
    DataNode exemplar(FieldNode p) {
        return switch (p.type()) {
            case "number" -> DataNode.of(!Double.isNaN(p.min()) && !Double.isNaN(p.max()) && Math.abs(p.max()) < 1 && Math.abs(p.min()) < 1 ? 0.5 : 1000);
            case "boolean" -> DataNode.of(true);
            default -> DataNode.of(p.date() ? "2026-01-01" : "x");
        };
    }

    Role roleOf(FieldNode p) {
        return semantics.role(p.name(), exemplar(p));
    }

    private List<FieldNode> scalars(FieldNode f) {
        return f.props().values().stream().filter(FieldNode::scalar).toList();
    }

    private List<FieldNode> numbers(FieldNode f) {
        return f.props().values().stream().filter(p -> "number".equals(p.type())).toList();
    }

    private List<FieldNode> withRole(FieldNode f, String role) {
        return f.props().values().stream().filter(p -> p.is(role) && p.scalar()).toList();
    }

    /** The measure that reads most like money: the heaviest semantic role, the first on a tie. */
    FieldNode best(List<FieldNode> measures) {
        FieldNode best = null;
        int w = -1;
        for (FieldNode m : measures) {
            int mw = roleOf(m).weight();
            if (mw > w) {
                best = m;
                w = mw;
            }
        }
        return best;
    }

    private FieldNode find(FieldNode f, String name) {
        return f.props().values().stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private FieldNode named(FieldNode f, List<String> names) {
        for (String n : names) {
            for (FieldNode p : f.props().values()) {
                if (p.name().equalsIgnoreCase(n) && p.array()) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * A sibling that holds the limit of the measure {@code f} (a gauge's maximum): named like the measure plus a limit word
     * ({@code exposureLimit}), or a bare limit word when {@code f} is the only measure beside it.
     */
    FieldNode limitOf(FieldNode f) {
        int dot = f.path().lastIndexOf('.');
        FieldNode parent = dot <= 1 ? model.root() : model.find(f.path().substring(0, dot));
        if (parent == null) {
            return null;
        }
        String me = f.name().toLowerCase(Locale.ROOT);
        for (String word : props.limitNames()) {
            String w = word.toLowerCase(Locale.ROOT);
            for (FieldNode p : parent.props().values()) {
                String other = p.name().toLowerCase(Locale.ROOT);
                if (p == f || !"number".equals(p.type()) || !other.contains(w)) {
                    continue;
                }
                String rest = other.replace(w, "");
                if (rest.isEmpty() ? soleMeasure(parent, f, p) : me.contains(rest) || rest.contains(me)) {
                    return p;
                }
            }
        }
        return null;
    }

    private boolean soleMeasure(FieldNode parent, FieldNode f, FieldNode limit) {
        return parent.props().values().stream().filter(p -> p.is("measure") && p != limit).allMatch(p -> p == f);
    }

    private FieldNode parentRows(FieldNode f) {
        int i = f.path().lastIndexOf("[].");
        if (i < 0) {
            return null;
        }
        FieldNode p = model.find(f.path().substring(0, i));
        return p != null && p.array() ? p : null;
    }

    private int distinctOf(FieldNode d) {
        JsonNodeEnum e = new JsonNodeEnum(d);
        int n = e.size();
        if (n > 0) {
            return n;
        }
        return stats.count() > 0 ? stats.distinct(d.path(), 1000) : 999;
    }

    private int points(FieldNode f) {
        int p = stats.count() > 0 ? stats.typicalLength(f.path()) : -1;
        return p >= 0 ? p : -1;
    }

    private String over(FieldNode f) {
        int n = stats.count();
        if (n <= 1) {
            return "";
        }
        int present = stats.present(f.path());
        return " (present in " + present + " of " + n + " samples)";
    }

    private static String title(FieldNode f) {
        return Semantics.humanize(f.name());
    }

    private static PanelChoice choice(String kind, double score, String reason, Area area, String title, Map<String, Object> options,
            List<Column> cols) {
        return new PanelChoice(kind, score, reason, area, title, options, cols);
    }

    private List<PanelChoice> ranked(List<PanelChoice> in) {
        List<PanelChoice> out = new ArrayList<>(in);
        out.sort(Comparator.comparingDouble(PanelChoice::score).reversed());
        List<PanelChoice> deduped = new ArrayList<>();
        for (PanelChoice c : out) {
            if (deduped.stream().noneMatch(d -> d.kind().equals(c.kind()) && d.options().equals(c.options()))) {
                deduped.add(c);
            }
        }
        return deduped;
    }

    /** The count of an enum's values, or 0 when the field is not an enum. */
    private record JsonNodeEnum(FieldNode f) {
        int size() {
            var e = f.schema().get("enum");
            return e != null && e.isArray() ? (int) java.util.stream.StreamSupport.stream(e.spliterator(), false).filter(x -> !x.isNull()).count() : 0;
        }
    }
}
