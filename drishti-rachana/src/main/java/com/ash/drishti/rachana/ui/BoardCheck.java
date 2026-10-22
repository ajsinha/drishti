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
package com.ash.drishti.rachana.ui;

import com.ash.drishti.rachana.ui.BoardDoc.Area;
import com.ash.drishti.rachana.ui.BoardDoc.Grid;
import com.ash.drishti.rachana.ui.BoardDoc.Instance;
import com.ash.drishti.rachana.ui.BoardDoc.Mapping;
import com.ash.drishti.rachana.ui.BoardDoc.Trace;
import com.ash.drishti.rachana.ui.BoardDoc.TransformKind;
import com.ash.drishti.rachana.ui.PinCompatibility.Result;
import com.ash.drishti.rachana.ui.PinSpec.ColumnSpec;
import com.ash.drishti.rachana.ui.PinSpec.PinRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The board check (UI_BOARD.md section 5): static, metadata only, no data read. It answers every problem with its rule
 * ({@code BC-01} .. {@code BC-11}, {@code BC-W1} .. {@code BC-W5}), the place, a message a person can act on, and a fix
 * where one is obvious. Errors block save and render; warnings do not. Stateless and thread-safe.
 */
public final class BoardCheck {

    private static final Pattern PARAM_REF = Pattern.compile("%\\{board\\.params\\.([a-z][a-z0-9-]*)");

    private BoardCheck() {}

    /** True when any problem is an error. */
    public static boolean failed(List<UiProblem> problems) {
        return problems.stream().anyMatch(UiProblem::error);
    }

    /** Checks a board document's text (YAML or JSON): its size, then it reads, then the rules. */
    public static List<UiProblem> check(String text, BoardCheckContext ctx) {
        List<UiProblem> p = new ArrayList<>();
        int bytes = text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > ctx.limits().bytes()) {
            p.add(UiProblem.error("BC-09", "", "the document is " + bytes + " bytes, over the limit of " + ctx.limits().bytes(),
                    "split it into modules or several boards"));
            return p;
        }
        UiDocuments.Read<BoardDoc> r = ctx.fragment() ? fragment(text) : UiDocuments.parseBoard(text);
        p.addAll(r.problems());
        if (r.doc() != null) {
            p.addAll(check(r.doc(), ctx));
        }
        return p;
    }

    private static UiDocuments.Read<BoardDoc> fragment(String text) {
        try {
            List<UiProblem> p = new ArrayList<>();
            return new UiDocuments.Read<>(UiDocuments.board(UiDocuments.tree(text), "", p, true), p);
        } catch (UiDocumentException e) {
            return new UiDocuments.Read<>(null, e.problems());
        }
    }

    /** Checks a board already read (BC-02 to BC-11 and the warnings; BC-01 is the reader's). */
    public static List<UiProblem> check(BoardDoc board, BoardCheckContext ctx) {
        return new Run(board, ctx).run();
    }

    /** One check of one board. */
    private static final class Run {

        private final BoardDoc b;
        private final BoardCheckContext ctx;
        private final List<UiProblem> p = new ArrayList<>();
        private final Map<String, ChipDoc> resolved = new LinkedHashMap<>();
        /** in pin ({@code inst.pin}) to the places feeding it. */
        private final Map<String, List<String>> feeds = new HashMap<>();
        private final java.util.Set<String> traced = new java.util.HashSet<>();
        /** out pins ({@code inst.pin}) that drive at least one trace. */
        private final Set<String> driving = new HashSet<>();

        Run(BoardDoc b, BoardCheckContext ctx) {
            this.b = b;
            this.ctx = ctx;
        }

        List<UiProblem> run() {
            sizes();
            b.chips().values().forEach(this::resolve);
            for (int i = 0; i < b.traces().size(); i++) {
                trace(i, b.traces().get(i));
            }
            b.chips().values().forEach(this::instance);
            cycles();
            if (!ctx.fragment() || b.layout() != BoardDoc.Layout.EMPTY) {
                layout();
            }
            return p;
        }

        // ---------------------------------------------------------------------------------------- BC-09

        private void sizes() {
            if (b.chips().size() > ctx.limits().chips()) {
                p.add(UiProblem.error("BC-09", "chips", b.chips().size() + " chips, over the limit of " + ctx.limits().chips(), "split the board, or group chips into a module"));
            }
            if (b.traces().size() > ctx.limits().traces()) {
                p.add(UiProblem.error("BC-09", "traces", b.traces().size() + " traces, over the limit of " + ctx.limits().traces(), "split the board"));
            }
            for (int i = 0; i < b.localChips().size(); i++) {
                int depth = depth(b.localChips().get(i).body(), 1);
                if (depth > ctx.limits().moduleDepth()) {
                    p.add(UiProblem.error("BC-09", "local-chips[" + i + "]", "modules nest " + depth + " deep, over the limit of " + ctx.limits().moduleDepth(), "flatten a level"));
                }
            }
        }

        private static int depth(JsonNode body, int level) {
            JsonNode inner = body == null ? null : body.path("board").path("local-chips");
            int max = body != null && body.has("board") ? level : 0;
            if (inner != null && inner.isArray()) {
                for (JsonNode c : inner) {
                    max = Math.max(max, depth(c.path("body"), level + 1));
                }
            }
            return max;
        }

        // ---------------------------------------------------------------------------------------- BC-02, BC-11, BC-W2

        private void resolve(Instance in) {
            String at = "chips." + in.name() + ".use";
            if (in.use() == null) {
                if (in.useText() != null) {
                    p.add(UiProblem.error("BC-02", at, "'" + in.useText() + "' is not <chip>@<version>; a chip is always used at a fixed version",
                            "write " + in.useText().replaceAll("@.*", "") + "@<version>"));
                }
                return;
            }
            Optional<ChipDoc> local = b.localChips().stream().filter(c -> in.use().equals(c.ref())).findFirst();
            if (local.isPresent()) {
                resolved.put(in.name(), local.get());
                return;
            }
            Optional<ChipCatalog.Entry> e = ctx.chips().find(in.use());
            if (e.isEmpty()) {
                p.add(UiProblem.error("BC-02", at, "no chip " + in.use() + " in the Chip Store or the board's local-chips", "choose a chip from the palette, or check the version"));
                return;
            }
            ChipState s = e.get().state();
            if (s == ChipState.WITHDRAWN) {
                p.add(UiProblem.error("BC-02", at, in.use() + " is withdrawn", e.get().newerCompatible() == null ? "use another version" : "use " + in.use().name() + "@" + e.get().newerCompatible()));
                return;
            }
            resolved.put(in.name(), e.get().doc());
            if (!ctx.fragment() && ctx.shared()) {
                if (ctx.certifiedOnly() && s != ChipState.CERTIFIED) {
                    p.add(UiProblem.error("BC-11", at, in.use() + " is " + label(s) + "; this workspace accepts certified chips only", "use a certified version, or ask for certification"));
                } else if (!s.released()) {
                    p.add(UiProblem.error("BC-11", at, in.use() + " is " + label(s) + "; a shared board uses published chips only", "publish the chip first, or keep the board personal"));
                }
            }
            if (s == ChipState.DEPRECATED) {
                p.add(UiProblem.warning("BC-W2", at, in.use() + " is deprecated", e.get().newerCompatible() == null ? null : "upgrade to " + in.use().name() + "@" + e.get().newerCompatible()));
            } else if (e.get().newerCompatible() != null) {
                p.add(UiProblem.warning("BC-W2", at, "a newer compatible version " + in.use().name() + "@" + e.get().newerCompatible() + " exists",
                        "upgrade to " + in.use().name() + "@" + e.get().newerCompatible()));
            }
        }

        private static String label(ChipState s) {
            return s.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }

        // ---------------------------------------------------------------------------------------- traces: BC-06, BC-04

        private void trace(int i, Trace t) {
            String at = "traces[" + i + "]";
            if (t.from() == null || t.to() == null) {
                return;                                                    // BC-01 said so
            }
            TypeRef source = null;
            Map<String, TypeRef> sourceShape = null;
            if (t.from().startsWith(BoardDoc.PARAMS)) {
                PinSpec param = b.params().get(t.from().substring(BoardDoc.PARAMS.length()));
                if (param == null) {
                    p.add(UiProblem.error("BC-06", at + ".from", "the board has no parameter '" + t.from().substring(BoardDoc.PARAMS.length()) + "'",
                            "declare it under params:, or fix the name (" + b.params().keySet() + ")"));
                } else {
                    source = param.type();
                }
            } else {
                String[] f = split(t.from());
                ChipDoc from = f == null ? null : resolved.get(f[0]);
                if (f == null || !b.chips().containsKey(f[0])) {
                    p.add(UiProblem.error("BC-06", at + ".from", "'" + t.from() + "' names no instance", "write <instance>.<out pin>; instances: " + b.chips().keySet()));
                } else if (from != null) {
                    PinSpec out = from.outPins().get(f[1]);
                    if (out == null) {
                        p.add(UiProblem.error("BC-06", at + ".from", from.ref() + " has no out pin '" + f[1] + "'", "out pins: " + from.outPins().keySet()));
                    } else {
                        driving.add(t.from());
                        source = outType(b.chips().get(f[0]), from, out);
                        if (out.shapeOf() != null && from.inPins().containsKey(out.shapeOf())) {
                            sourceShape = columns(from.inPins().get(out.shapeOf()).shape());
                        }
                    }
                }
            }
            String[] to = split(t.to());
            if (to == null || !b.chips().containsKey(to[0])) {
                p.add(UiProblem.error("BC-06", at + ".to", "'" + t.to() + "' names no instance", "write <instance>.<in pin>; instances: " + b.chips().keySet()));
                return;
            }
            ChipDoc target = resolved.get(to[0]);
            if (target == null) {
                return;                                                    // BC-02 said so
            }
            PinSpec pin = target.inPins().get(to[1]);
            if (pin == null) {
                p.add(UiProblem.error("BC-06", at + ".to", target.ref() + " has no in pin '" + to[1] + "'"
                        + (target.outPins().containsKey(to[1]) ? " ('" + to[1] + "' is an out pin; a trace ends at an in pin)" : ""), "in pins: " + target.inPins().keySet()));
                return;
            }
            if (pin.type().type() != PinType.FILTER || traced.add(t.to())) {
                feed(t.to(), at, at + ".to");                              // filters combine: a filter pin takes several traces (12.4), counted once
            }
            if (t.transform() != null && t.transform().kind() == TransformKind.FILTER_ON) {
                String col = t.transform().column();
                String[] c = split(col);
                PinSpec data = c == null ? null : target.inPins().get(c[0]);
                if (data == null || !data.shape().containsKey(c[1])) {
                    p.add(UiProblem.error("BC-06", at + ".transform", "'filter-on: " + col + "' names no column of " + target.ref(), "name <data pin>.<column> of " + to[0]));
                }
            }
            if (source == null) {
                return;
            }
            if (source.type() == PinType.ROWSET && pin.type().type() == PinType.ROWSET && t.transform() == null) {
                if (sourceShape != null) {
                    for (String m : PinCompatibility.checkShape(sourceShape, pin.shape(), ctx.subtype())) {
                        p.add(UiProblem.error("BC-04", at, m, "feed it rows that carry the column, or choose another chip"));
                    }
                }
                return;
            }
            report(PinCompatibility.check(source, pin.type(), t.transform(), ctx.subtype()), at, t.from() + " (" + source + ") to " + t.to() + " (" + pin.type() + ")");
        }

        /** An out pin's type; an id that is {@code of: <pin>.<column>} carries the kind of the attribute mapped there, if the supplier gives one. */
        private TypeRef outType(Instance in, ChipDoc chip, PinSpec out) {
            if (out.ofPin() == null || ctx.suppliers() == null || in.supply() == null) {
                return out.type();
            }
            Mapping m = in.map().get(out.ofPin());
            Optional<SupplierCatalog.Info> info = ctx.suppliers().find(in.supply());
            if (m == null || info.isEmpty() || m.attributes().isEmpty()) {
                return out.type();
            }
            TypeRef attr = info.get().fields().get(m.attributes().get(0));
            return attr == null || attr.of() == null ? out.type() : out.type().withKind(attr.of());
        }

        private void feed(String pin, String place, String at) {
            List<String> f = feeds.computeIfAbsent(pin, k -> new ArrayList<>());
            f.add(place);
            if (f.size() == 2) {
                p.add(UiProblem.error("BC-06", at, "in pin " + pin + " has two sources (" + f.get(0) + " and " + f.get(1) + "); an in pin takes one",
                        "keep one source; combine filters in a chip upstream"));
            }
        }

        // ---------------------------------------------------------------------------------------- instances: BC-03, BC-04, BC-05, BC-08, BC-W3, BC-W5

        private void instance(Instance in) {
            ChipDoc chip = resolved.get(in.name());
            String at = "chips." + in.name();
            if (in.supply() != null && in.supply().query() != null) {
                Matcher m = PARAM_REF.matcher(in.supply().query().toString());
                while (m.find()) {
                    if (!b.params().containsKey(m.group(1))) {
                        p.add(UiProblem.error("BC-06", at + ".supply.query", "the query names board.params." + m.group(1) + ", which the board does not declare", "declare it under params:"));
                    }
                }
            }
            if (chip == null) {
                return;
            }
            SupplierCatalog.Info info = null;
            if (in.supply() != null && ctx.suppliers() != null) {
                info = ctx.suppliers().find(in.supply()).orElse(null);
                if (info == null) {
                    p.add(UiProblem.error("BC-08", at + ".supply", "no supplier " + in.supply().key().replace(":", " ") + " that you may read",
                            "check the name in the palette's Suppliers tab, or ask for access"));
                }
            }
            for (Mapping m : in.map().values()) {
                mapping(in, chip, m, info, at + ".map." + m.key());
            }
            for (Map.Entry<String, JsonNode> c : in.controls().entrySet()) {
                control(in, chip, c.getKey(), c.getValue(), at + ".controls." + c.getKey());
            }
            boolean unfinished = false;
            for (PinSpec pin : chip.inPins().values()) {
                String key = in.name() + "." + pin.name();
                boolean traced = feeds.containsKey(key);
                boolean constant = in.controls().containsKey(pin.name());
                boolean supplied = pin.role() == PinRole.DATA && in.supply() != null && !traced;
                if (!traced && !constant && !supplied && !pin.hasDefault()) {
                    if (pin.required()) {
                        p.add(UiProblem.error("BC-03", at + "." + pin.name(), "required " + pin.role().name().toLowerCase(Locale.ROOT) + " pin '" + pin.name()
                                + "' (" + pin.type() + ") is not connected",
                                pin.role() == PinRole.DATA ? "add a supply: and map:, or draw a trace to " + key
                                        : "set controls." + pin.name() + ", or draw a trace to " + key));
                    } else {
                        unfinished = true;
                    }
                }
                if (supplied && !pin.shape().isEmpty() && !in.map().containsKey(pin.name())) {
                    for (ColumnSpec c : pin.shape().values()) {
                        if (c.required() && !in.map().containsKey(pin.name() + "." + c.name())) {
                            p.add(UiProblem.error("BC-03", at + ".map." + pin.name() + "." + c.name(), "required column " + pin.name() + "." + c.name() + " (" + c.type()
                                    + ") is not mapped", "map " + pin.name() + "." + c.name() + ": <supplier attribute>"));
                        }
                    }
                }
            }
            boolean drives = chip.outPins().keySet().stream().anyMatch(o -> driving.contains(in.name() + "." + o));
            if (unfinished && !chip.outPins().isEmpty() && !drives && !ctx.fragment()) {
                p.add(UiProblem.warning("BC-W3", at, "no out pin of " + in.name() + " drives anything and an optional in pin is empty with no default; is it unfinished?", null));
            }
        }

        private void mapping(Instance in, ChipDoc chip, Mapping m, SupplierCatalog.Info info, String at) {
            String[] k = m.key().contains(".") ? split(m.key()) : new String[] {m.key(), null};
            PinSpec pin = k == null ? null : chip.inPins().get(k[0]);
            if (pin == null) {
                p.add(UiProblem.error("BC-05", at, "'" + m.key() + "' names no in pin of " + chip.ref(), "map onto " + chip.inPins().keySet() + " or their columns"));
                return;
            }
            if (in.supply() == null) {
                p.add(UiProblem.error("BC-05", at, "a map needs a supply: on the instance", "add supply: { connector: <pack>/<kind> }"));
                return;
            }
            if (feeds.containsKey(in.name() + "." + pin.name())) {
                p.add(UiProblem.error("BC-06", at, "pin '" + pin.name() + "' is fed by a trace and by the supply; an in pin takes one", "remove the trace or the map entry"));
            }
            TypeRef want;
            if (k[1] == null) {
                want = pin.type().type() == PinType.ROWSET || pin.type().type() == PinType.DOCUMENT ? null : pin.type();   // rows: <path> of a document
                if (m.list()) {
                    p.add(UiProblem.error("BC-05", at, "pin '" + pin.name() + "' takes one attribute, not a list", null));
                }
            } else {
                ColumnSpec c = pin.shape().get(k[1]);
                if (c == null) {
                    p.add(UiProblem.error("BC-05", at, pin.name() + " has no column '" + k[1] + "'", "columns: " + pin.shape().keySet()));
                    return;
                }
                if (m.list() && !c.many()) {
                    p.add(UiProblem.error("BC-05", at, "column " + m.key() + " takes one attribute, not a list", "map one attribute"));
                } else if (m.list() && c.max() > 0 && m.attributes().size() > c.max()) {
                    p.add(UiProblem.error("BC-05", at, "column " + m.key() + " takes at most " + c.max() + " attributes, not " + m.attributes().size(),
                            "map " + c.max() + ", or choose a chip with more columns"));
                }
                want = c.type();
            }
            if (info == null) {
                return;
            }
            for (String attr : m.attributes()) {
                TypeRef have = info.fields().get(attr);
                if (have == null) {
                    String near = info.fields().keySet().stream().filter(f -> f.equalsIgnoreCase(attr)).findFirst().orElse(null);
                    p.add(UiProblem.error("BC-05", at, "the supplier " + in.supply().name() + " has no attribute '" + attr + "'",
                            near != null ? "did you mean " + near + "?" : "choose one of the supplier's attributes in the mapping sheet"));
                    continue;
                }
                if (info.masked().contains(attr)) {
                    p.add(UiProblem.warning("BC-W5", at, attr + " is masked for you; viewers like you will see •••", null));
                }
                if (want != null) {
                    report(PinCompatibility.check(have, want, null, ctx.subtype()), at, attr + " (" + have + ") to " + m.key() + " (" + want + ")");
                }
            }
        }

        private void control(Instance in, ChipDoc chip, String name, JsonNode v, String at) {
            PinSpec pin = chip.inPins().get(name);
            if (pin == null || pin.role() != PinRole.CONTROL) {
                p.add(UiProblem.error("BC-05", at, "'" + name + "' is not a control pin of " + chip.ref(),
                        "control pins: " + chip.inPins().values().stream().filter(x -> x.role() == PinRole.CONTROL).map(PinSpec::name).toList()));
                return;
            }
            if (feeds.containsKey(in.name() + "." + name)) {
                p.add(UiProblem.error("BC-06", at, "pin '" + name + "' is fed by a trace and by a constant; an in pin takes one", "remove one"));
            }
            String why = constant(pin.type(), v);
            if (why != null) {
                p.add(UiProblem.error("BC-04", at, "the constant " + v + " " + why, "give a " + pin.type().type().yamlName() + " value the pin takes"));
            }
        }

        /** Null when {@code v} is a valid constant of {@code t}, else why not. */
        static String constant(TypeRef t, JsonNode v) {
            switch (t.type()) {
                case NUMBER, MEASURE -> {
                    if (!v.isNumber()) {
                        return "is not a number";
                    }
                    double d = v.asDouble();
                    if (t.min() != null && d < t.min() || t.max() != null && d > t.max()) {
                        return "is outside " + fmt(t.min()) + " to " + fmt(t.max());
                    }
                    if (t.integer() && d != Math.rint(d)) {
                        return "is not a whole number";
                    }
                    return null;
                }
                case BOOL -> {
                    return v.isBoolean() ? null : "is not true or false";
                }
                case ENUM -> {
                    return t.values().contains(v.asText()) ? null : "is not one of " + t.values();
                }
                case DATE -> {
                    return v.isTextual() && DATE.matcher(v.asText()).matches() ? null : "is not a date (yyyy-mm-dd, today, T-1, EOM)";
                }
                case ID, TEXT, DIMENSION, DATETIME -> {
                    return v.isValueNode() && !v.isNull() ? null : "is not a single value";
                }
                case SPEC -> {
                    return v.isNull() ? "is empty" : null;
                }
                case ROWSET, DOCUMENT -> {
                    return "cannot be a constant: rows and documents come from a supply or a trace";
                }
                default -> {
                    return null;
                }
            }
        }

        private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}|today|T-\\d+|EOM|EOY|SOM|SOY|BOM|T");

        private static String fmt(Double d) {
            return d == null ? "any" : d == Math.rint(d) ? String.valueOf(d.longValue()) : d.toString();
        }

        private void report(Result r, String at, String what) {
            if (r.verdict() == PinCompatibility.Verdict.NO) {
                p.add(UiProblem.error("BC-04", at, what + ": " + r.reason(), r.fix()));
            } else if (r.verdict() == PinCompatibility.Verdict.WARN) {
                p.add(UiProblem.warning(r.rule(), at, what + ": " + r.reason(), r.fix()));
            }
        }

        // ---------------------------------------------------------------------------------------- BC-07

        private void cycles() {
            Map<String, List<Edge>> out = new LinkedHashMap<>();
            for (int i = 0; i < b.traces().size(); i++) {
                Trace t = b.traces().get(i);
                String[] f = t.from() == null || t.from().startsWith(BoardDoc.PARAMS) ? null : split(t.from());
                String[] to = t.to() == null ? null : split(t.to());
                if (f == null || to == null || !resolved.containsKey(f[0]) || !resolved.containsKey(to[0])) {
                    continue;
                }
                PinSpec src = resolved.get(f[0]).outPins().get(f[1]);
                PinSpec dst = resolved.get(to[0]).inPins().get(to[1]);
                if (src == null || dst == null) {
                    continue;
                }
                boolean selection = src.type().type() == PinType.SELECTION || dst.type().type() == PinType.SELECTION
                        || src.type().type() == PinType.FILTER && dst.type().type() == PinType.FILTER;   // a cross-filter: set by a person's click, it settles (12.4)
                out.computeIfAbsent(f[0], k -> new ArrayList<>()).add(new Edge(i, f[0], to[0], dst.role() == PinRole.DATA, selection));
            }
            for (Set<String> comp : Tarjan.components(b.chips().keySet(), out)) {
                List<Edge> inside = out.values().stream().flatMap(List::stream).filter(e -> comp.contains(e.from) && comp.contains(e.to)).toList();
                if (inside.isEmpty()) {
                    continue;
                }
                boolean data = inside.stream().anyMatch(e -> e.data);
                boolean selection = inside.stream().anyMatch(e -> e.selection);
                if (data || !selection) {
                    p.add(UiProblem.error("BC-07", "traces[" + inside.get(0).index + "]", "the traces among " + comp + " form a cycle"
                            + (data ? " through a data pin" : " of control pins that do not settle (no selection pin)"),
                            "remove one of the traces " + inside.stream().map(e -> "traces[" + e.index + "]").toList()));
                }
            }
        }

        private record Edge(int index, String from, String to, boolean data, boolean selection) {}

        // ---------------------------------------------------------------------------------------- BC-10, BC-W4

        private void layout() {
            Grid d = b.layout().desktop();
            if (d == null) {
                p.add(UiProblem.error("BC-10", "layout.desktop", "the board has no desktop layout", "add layout: { desktop: { columns: 12, areas: [ { chip: <instance>, at: [1, 1], span: [12, 4] } ] } }"));
            } else {
                grid(d, "layout.desktop", true);
            }
            if (b.layout().tablet() != null) {
                grid(b.layout().tablet(), "layout.tablet", false);
            }
            BoardDoc.Phone ph = b.layout().phone();
            if (ph != null) {
                for (String s : ph.order()) {
                    if (!b.chips().containsKey(s)) {
                        p.add(UiProblem.error("BC-10", "layout.phone.order", "'" + s + "' names no instance", "instances: " + b.chips().keySet()));
                    }
                }
                for (String s : ph.collapsed()) {
                    if (!b.chips().containsKey(s)) {
                        p.add(UiProblem.error("BC-10", "layout.phone.collapsed", "'" + s + "' names no instance", null));
                    }
                }
                for (String s : b.chips().keySet()) {
                    if (!ph.order().contains(s)) {
                        p.add(UiProblem.warning("BC-W4", "layout.phone.order", "the phone layout leaves out '" + s + "'; it is appended at the end", "add it to order:"));
                    }
                }
            }
        }

        private void grid(Grid g, String at, boolean everyone) {
            Set<String> placed = new HashSet<>();
            for (int i = 0; i < g.areas().size(); i++) {
                Area a = g.areas().get(i);
                String ap = at + ".areas[" + i + "]";
                if (!b.chips().containsKey(a.chip())) {
                    p.add(UiProblem.error("BC-10", ap, "'" + a.chip() + "' names no instance", "instances: " + b.chips().keySet()));
                }
                if (!placed.add(a.chip())) {
                    p.add(UiProblem.error("BC-10", ap, "'" + a.chip() + "' is placed twice", "remove one area"));
                }
                if (a.column() < 1 || a.row() < 1 || a.width() < 1 || a.height() < 1 || a.column() + a.width() - 1 > g.columns()) {
                    p.add(UiProblem.error("BC-10", ap, "'" + a.chip() + "' at [" + a.column() + ", " + a.row() + "] span [" + a.width() + ", " + a.height()
                            + "] is outside the " + g.columns() + "-column grid", "keep column + span - 1 within " + g.columns()));
                }
                for (int j = 0; j < i; j++) {
                    if (a.overlaps(g.areas().get(j))) {
                        p.add(UiProblem.error("BC-10", ap, "'" + a.chip() + "' overlaps '" + g.areas().get(j).chip() + "'", "move or resize one of them"));
                    }
                }
            }
            if (everyone) {
                for (String s : b.chips().keySet()) {
                    if (!placed.contains(s)) {
                        p.add(UiProblem.error("BC-10", at, "'" + s + "' is not placed on the desktop", "add an area for it"));
                    }
                }
            }
        }
    }

    /** {@code a.b} to {@code [a, b]} (the first dot); null when there is no dot. */
    static String[] split(String s) {
        int dot = s == null ? -1 : s.indexOf('.');
        return dot <= 0 || dot == s.length() - 1 ? null : new String[] {s.substring(0, dot), s.substring(dot + 1)};
    }

    private static Map<String, TypeRef> columns(Map<String, ColumnSpec> shape) {
        Map<String, TypeRef> m = new LinkedHashMap<>();
        shape.values().forEach(c -> m.put(c.name(), c.type()));
        return m;
    }

    /** Strongly connected components (Tarjan), for BC-07. */
    private static final class Tarjan {

        static List<Set<String>> components(Set<String> nodes, Map<String, List<Run.Edge>> out) {
            Map<String, Integer> index = new HashMap<>();
            Map<String, Integer> low = new HashMap<>();
            java.util.Deque<String> stack = new java.util.ArrayDeque<>();
            Set<String> on = new HashSet<>();
            List<Set<String>> result = new ArrayList<>();
            int[] counter = {0};
            for (String n : nodes) {
                if (!index.containsKey(n)) {
                    strong(n, out, index, low, stack, on, result, counter);
                }
            }
            return result;
        }

        private static void strong(String v, Map<String, List<Run.Edge>> out, Map<String, Integer> index, Map<String, Integer> low,
                java.util.Deque<String> stack, Set<String> on, List<Set<String>> result, int[] counter) {
            index.put(v, counter[0]);
            low.put(v, counter[0]++);
            stack.push(v);
            on.add(v);
            for (Run.Edge e : out.getOrDefault(v, List.of())) {
                if (!index.containsKey(e.to())) {
                    strong(e.to(), out, index, low, stack, on, result, counter);
                    low.put(v, Math.min(low.get(v), low.get(e.to())));
                } else if (on.contains(e.to())) {
                    low.put(v, Math.min(low.get(v), index.get(e.to())));
                }
            }
            if (low.get(v).equals(index.get(v))) {
                Set<String> comp = new java.util.TreeSet<>();
                String w;
                do {
                    w = stack.pop();
                    on.remove(w);
                    comp.add(w);
                } while (!w.equals(v));
                result.add(comp);
            }
        }
    }
}
