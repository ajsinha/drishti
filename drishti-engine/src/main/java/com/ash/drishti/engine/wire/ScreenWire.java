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
package com.ash.drishti.engine.wire;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.bind.BindContext;
import com.ash.drishti.engine.bind.Binder;
import com.ash.drishti.engine.source.SourceFailures;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.engine.view.ViewModel.PanelView;
import com.ash.drishti.rachana.SutraWiring;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PanelKind;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.model.Wiring;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Binds the wired panels of a Sutra Next screen (SUTRA_NEXT.md 6 to 8, 15): per-panel sources over any enabled pack's kinds, events
 * evaluated on the server per click, and child panels filled lazily when their parent fires. Everything is read as the viewer: a source
 * of a kind the viewer may not open is never fetched; its documents are masked before a panel sees them; a payload carries what the
 * viewer saw ({@code seen}, masked fields as {@code •••}) and, kept on the server only, what the server follows ({@code raw}): the
 * source of a child or a listening panel is looked up with the raw payload, so the same person may follow a masked id without ever
 * being sent it, while everything drawn (titles, cells, filters) reads the seen payload. Thread-safe and stateless: the fired
 * events come with each call.
 */
public final class ScreenWire {

    /** The most entities of a kind a {@code where} source reads before keeping those its condition holds for. */
    public static final int WHERE_SCAN = 500;

    /**
     * One fired event: the panel, the event, the payload as the viewer saw it and the payload the server follows (never sent out).
     */
    public record Selection(String panel, String event, DataNode seen, DataNode raw) {}

    /**
     * One request's screen: the Sutra, its documents raw and as the viewer sees them, the binding context (seen) and the raw
     * evaluation context, the business date, the viewer's masks and kinds, and the events fired so far.
     */
    public record Screen(Sutra sutra, Map<String, SutraWiring.Node> nodes, BindContext ctx, EvalContext rawEval, AsOf asOf,
            UnaryOperator<DataNode> redact, Predicate<String> mayOpen, Map<String, Map<String, Selection>> fired,
            Map<String, Pair> cache) {

        public Screen {
            fired = fired == null ? Map.of() : fired;
            cache = cache == null ? new ConcurrentHashMap<>() : cache;
        }

        Screen withFired(Map<String, Map<String, Selection>> next) {
            return new Screen(sutra, nodes, ctx, rawEval, asOf, redact, mayOpen, next, cache);
        }

        boolean fired(String panel, String event) {
            Map<String, Selection> m = fired.get(panel);
            return m != null && (event == null ? !m.isEmpty() : m.containsKey(event));
        }
    }

    /** What a source read: as the viewer sees it and as stored (for following a click), or why the viewer may not have it. */
    public record Pair(Object seen, Object raw, String denied) {}

    /**
     * A group of child panels for the console: whose children ({@code parent}), which group, where and when they show, the panels
     * (empty and {@code hidden} when the parent has no selection the group shows on).
     */
    public record ChildrenView(String parent, int group, String place, String show, boolean hidden, List<PanelView> panels) {}

    /**
     * What a click changed: the event as the viewer saw it, every panel that reads it (or waits on one that does), the children of the
     * clicked panel, and the panels whose own selections the click cleared (their children close).
     */
    public record Result(Selection selection, List<PanelView> panels, List<ChildrenView> children, List<String> cleared) {}

    private final SourceRouter router;
    private final Binder binder;
    private final ElCompiler el;
    private final Duration budget;

    public ScreenWire(SourceRouter router, Binder binder, ElCompiler el, Duration budget) {
        this.router = router;
        this.binder = binder;
        this.el = el;
        this.budget = budget;
    }

    /** Whether a screen panel goes through this class: it is wired, or reads an event or {@code $src}. */
    public static boolean wired(Panel p) {
        return !p.wiring().isNone() || !SutraWiring.reads(p, null).isEmpty();
    }

    // ---- binding --------------------------------------------------------------------------------------------------------------

    /** One panel of the screen (at any depth) as the viewer may see it, given the events fired. */
    public PanelView bind(Screen s, Panel p) {
        SutraWiring.Node n = s.nodes().get(p.id());
        Optional<Wiring.Ref> missing = missing(s, n);
        if (missing.isPresent()) {
            return waiting(s, n, missing.get());
        }
        Ctx c = context(s, n);
        if (c.denied != null) {
            return new PanelView(p.id(), p.kind().id(), p.title(), p.code(), p.key(), area(p), false, null, null, null, true,
                    p.span().orElse(null), p.height().orElse(null), c.denied, wire(p, n, null));
        }
        if (!binder.shown(p, c.seen.eval())) {                 // when: over an event that fired, not true: it waits again
            return waiting(s, n, null);
        }
        return binder.bind(p, c.seen).withWire(wire(p, n, null));
    }

    /** Whether a screen panel is drawn at first paint: one whose {@code when} reads an event is (it waits); else its {@code when}. */
    public boolean shown(Screen s, Panel p) {
        return p.options().get(Panel.WHEN) instanceof String w && !Wiring.refs(w).isEmpty() || binder.shown(p, s.ctx().eval());
    }

    /** The groups of children of {@code panelId}, bound; a group that shows on a selection the parent has not made is hidden. */
    public List<ChildrenView> children(Screen s, String panelId) {
        SutraWiring.Node n = node(s, panelId);
        List<ChildrenView> out = new ArrayList<>();
        List<Wiring.Children> groups = n.panel().wiring().children();
        boolean selected = s.fired(panelId, null);
        for (int i = 0; i < groups.size(); i++) {
            Wiring.Children g = groups.get(i);
            boolean shown = "always".equals(g.show()) || selected;
            List<PanelView> panels = new ArrayList<>();
            if (shown && missing(s, n).isEmpty()) {
                for (Panel c : g.panels()) {
                    if (binder.shown(c, context(s, s.nodes().get(c.id())).seen.eval())) {
                        panels.add(bind(s, c));
                    }
                }
            }
            out.add(new ChildrenView(panelId, i, g.place(), g.show(), !shown, panels));
        }
        return out;
    }

    /**
     * A click on row {@code row} (its place in the panel's data) of {@code panelId}, firing {@code event}: the payload evaluated as
     * the viewer, the panels that read it rebound, the clicked panel's children filled, the selections it makes stale cleared.
     *
     * @throws DrishtiException {@code DRS-5001} for a panel, event or row the screen does not have, {@code DRS-5002} for a panel the
     *     viewer may not read
     */
    public Result event(Screen s, String panelId, String event, int row) {
        Selection sel = click(s, panelId, event, row);
        Set<String> down = downstream(s, panelId);
        Map<String, Map<String, Selection>> next = new LinkedHashMap<>();
        s.fired().forEach((k, v) -> {
            if (!down.contains(k)) {
                next.put(k, new LinkedHashMap<>(v));
            }
        });
        next.computeIfAbsent(panelId, k -> new LinkedHashMap<>()).put(event, sel);
        Screen after = s.withFired(next);
        List<PanelView> panels = new ArrayList<>();
        for (String id : down) {
            SutraWiring.Node n = s.nodes().get(id);
            if (n.parent() == null || n.parent().id().equals(panelId)) {
                if (n.parent() == null) {
                    panels.add(bind(after, n.panel()));       // the clicked panel's own children come as its children below
                }
            } else if (!down.contains(n.parent().id())) {
                panels.add(bind(after, n.panel()));           // a child elsewhere on the screen that reads this event
            }
        }
        List<String> cleared = down.stream().filter(s.fired()::containsKey).toList();
        return new Result(sel, panels, children(after, panelId), cleared);
    }

    /** The selection a click makes, evaluated as the viewer (seen) and as stored (raw, for following it). */
    Selection click(Screen s, String panelId, String event, int row) {
        SutraWiring.Node n = node(s, panelId);
        Panel p = n.panel();
        Wiring.Emit emit = p.wiring().emit(event).orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST,
                "panel " + panelId + " emits no event '" + event + "'"));
        if (missing(s, n).isPresent()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "panel " + panelId + " is waiting for an event and has no rows to click");
        }
        Ctx c = context(s, n);
        if (c.denied != null) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "panel " + panelId + ": " + c.denied);
        }
        String rowsExpr = p.option("rows").orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, "panel " + panelId + " has no rows"));
        DataNode seenAll = node(el.compile(rowsExpr).eval(c.seen.eval()));
        DataNode rawAll = node(el.compile(rowsExpr).eval(c.raw));
        int[] kept = binder.originals(p, seenAll, c.seen.eval());
        boolean shown = row >= 0 && row < seenAll.size() && (kept == null || java.util.Arrays.stream(kept).anyMatch(k -> k == row));
        if (!shown) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "panel " + panelId + " shows no row " + row);
        }
        DataNode seenRow = seenAll.get(row);
        DataNode rawRow = rawAll.size() == seenAll.size() ? rawAll.get(row) : null;   // a list shaped by a masked field: follow only what was seen
        Map<String, Object> seen = new LinkedHashMap<>();
        Map<String, Object> raw = new LinkedHashMap<>();
        emit.payload().forEach((field, f) -> {
            Object sv = soft(() -> Values.simplify(el.compile(f.bind()).eval(c.seen.eval().withRow(seenRow, row))));
            Object rv = rawRow == null ? sv : soft(() -> Values.simplify(el.compile(f.bind()).eval(c.raw.withRow(rawRow, row))));
            seen.put(field, sv instanceof DataNode d && d.isMissing() ? null : sv);
            raw.put(field, rv instanceof DataNode d && d.isMissing() ? null : rv);
        });
        return new Selection(panelId, event, DataNode.of(seen), DataNode.of(raw));
    }

    // ---- contexts and sources -------------------------------------------------------------------------------------------------

    /** A panel's binding context as the viewer sees it, the raw context the server follows clicks with, and why it is refused. */
    private record Ctx(BindContext seen, EvalContext raw, String denied) {}

    private Ctx context(Screen s, SutraWiring.Node n) {
        EvalContext.Scope seenScope = scope(s, n, false);
        EvalContext.Scope rawScope = scope(s, n, true);
        EvalContext seenEval = s.ctx().eval().withScope(seenScope);
        EvalContext rawEval = s.rawEval().withScope(rawScope);
        Wiring.Source src = n.panel().wiring().source() != null ? n.panel().wiring().source() : n.group() == null ? null : n.group().source();
        if (src == null) {
            return new Ctx(s.ctx().withEval(seenEval), rawEval, null);
        }
        Pair got = read(s, src, rawEval);
        if (got.denied() != null) {
            return new Ctx(s.ctx().withEval(seenEval), rawEval, got.denied());
        }
        return new Ctx(s.ctx().withEval(seenEval.withScope(seenScope.withSrc(got.seen()))), rawEval.withScope(rawScope.withSrc(got.raw())), null);
    }

    /** The events a panel reads: every panel's ({@code @<id>}) and, for a child, its parent's ({@code @parent}). */
    private static EvalContext.Scope scope(Screen s, SutraWiring.Node n, boolean raw) {
        Map<String, DataNode> events = new HashMap<>();
        s.fired().forEach((panel, byEvent) -> events.put(panel, payloads(byEvent, raw)));
        DataNode parent = n.parent() == null ? null : events.get(n.parent().id());
        return new EvalContext.Scope(events, parent, null);
    }

    private static DataNode payloads(Map<String, Selection> byEvent, boolean raw) {
        Map<String, DataNode> out = new LinkedHashMap<>();
        byEvent.forEach((e, sel) -> out.put(e, raw ? sel.raw() : sel.seen()));
        return new DataNode.Obj(out);
    }

    /**
     * What a source names, looked up with the raw events (the same person follows a masked id) and read as the viewer: one entity by
     * id, or the entities of a kind a condition keeps (each read as the viewer sees it, so a hidden field never selects one), or a search.
     */
    private Pair read(Screen s, Wiring.Source src, EvalContext lookup) {
        if (!s.mayOpen().test(src.kind())) {
            return new Pair(null, null, "no access to " + src.kind());
        }
        AsOf asOf = s.asOf();
        if (src.asOf() != null) {
            String d = Values.text(soft(() -> el.compile(src.asOf()).eval(lookup)));
            try {
                asOf = d == null || d.isBlank() ? asOf : AsOf.of(java.time.LocalDate.parse(d.substring(0, Math.min(10, d.length()))));
            } catch (java.time.format.DateTimeParseException e) {
                return new Pair(DataNode.missing(), DataNode.missing(), null);
            }
        }
        AsOf at = asOf;
        if (src.id() != null) {
            Object v = soft(() -> Values.simplify(el.compile(src.id()).eval(lookup)));
            if (Values.masked(v)) {
                return new Pair(null, null, "not available to your role");      // the id itself is hidden from this person at its source
            }
            String id = v == null ? "" : Values.text(v);
            if (id == null || id.isBlank()) {
                return new Pair(DataNode.missing(), DataNode.missing(), null);
            }
            return s.cache().computeIfAbsent(src.kind() + "|" + id + "|" + at, k -> {
                EntityRef ref = EntityRef.of(src.kind(), id);
                EntityDocument d = router.fetchAll(Set.of(ref), budget, at).get(ref);
                return d == null ? new Pair(DataNode.missing(), DataNode.missing(), null) : new Pair(s.redact().apply(d.data()), d.data(), null);
            });
        }
        List<EntityRef> refs;
        if (src.search() != null) {
            String text = Values.text(soft(() -> Values.simplify(el.compile(src.search()).eval(lookup))));
            if (text == null || text.isBlank() || Values.masked(text)) {
                return new Pair(new DataNode.Arr(List.of()), new DataNode.Arr(List.of()), null);
            }
            refs = router.search(src.kind(), text, src.limit(), budget, at).stream().map(EntityHit::ref).toList();
        } else {
            refs = router.listAcross(src.kind(), WHERE_SCAN, budget, at, new SourceFailures()).hits().stream().map(EntityHit::ref).toList();
        }
        String key = src.kind() + "|" + (src.where() != null ? "w:" + src.where() : "s:" + src.search()) + "|" + at + "|" + lookup.scope().events();
        return s.cache().computeIfAbsent(key, k -> {
            Map<EntityRef, EntityDocument> docs = refs.isEmpty() ? Map.of() : router.fetchAll(new LinkedHashSet<>(refs), budget, at);
            List<DataNode> seen = new ArrayList<>();
            List<DataNode> raw = new ArrayList<>();
            var cond = src.where() == null ? null : el.compile(src.where());
            for (EntityRef r : refs) {
                EntityDocument d = docs.get(r);
                if (d == null || seen.size() >= src.limit()) {
                    continue;
                }
                DataNode visible = s.redact().apply(d.data());
                if (cond != null) {
                    int i = seen.size();
                    if (!Values.truthy(soft(() -> cond.eval(lookup.withRow(visible, i))))) {
                        continue;
                    }
                }
                seen.add(visible);
                raw.add(d.data());
            }
            return new Pair(new DataNode.Arr(seen), new DataNode.Arr(raw), null);
        });
    }

    // ---- waiting, the graph -----------------------------------------------------------------------------------------------------

    /** The first event a panel reads that has not fired (its parent's, for a child of a parent not selected), if any. */
    private static Optional<Wiring.Ref> missing(Screen s, SutraWiring.Node n) {
        if (n == null) {
            return Optional.empty();
        }
        for (String t : SutraWiring.texts(n.panel(), n.group())) {
            for (Wiring.Ref r : Wiring.refs(t)) {
                String panel = "parent".equals(r.panel()) ? n.parent() == null ? null : n.parent().id() : r.panel();
                if (panel != null && !s.fired(panel, r.event())) {
                    return Optional.of(r);
                }
            }
        }
        return Optional.empty();
    }

    private PanelView waiting(Screen s, SutraWiring.Node n, Wiring.Ref on) {
        Panel p = n.panel();
        String text = p.wiring().waits() != null ? p.wiring().waits() : n.group() != null && n.group().waits() != null ? n.group().waits() : null;
        if (text == null && on == null) {
            text = "Nothing to show for this selection";
        } else if (text == null) {
            SutraWiring.Node from = "parent".equals(on.panel()) ? s.nodes().get(n.parent().id()) : s.nodes().get(on.panel());
            Panel f = from == null ? null : from.panel();
            String what = f != null && f.kind() == PanelKind.HBAR ? "a bar" : "a row";
            text = "Pick " + what + " in " + (f == null ? on.panel() : f.title() != null && !f.title().contains("${") ? f.title() : f.id()) + " to fill this panel";
        }
        String title = p.title() == null ? null : p.title().contains("${") ? p.title().replaceAll("\\$\\{[^}]*}", "").replaceAll("\\s+", " ").trim() : p.title();
        return new PanelView(p.id(), p.kind().id(), title, p.code(), p.key(), area(p), false, null, null, null, true,
                p.span().orElse(null), p.height().orElse(null), null, wire(p, n, text));
    }

    /** Every panel that waits, directly or through others, on {@code panelId}'s events or is its descendant. */
    public static Set<String> downstream(Screen s, String panelId) {
        Map<String, Set<String>> dependents = new HashMap<>();
        s.nodes().forEach((id, n) -> {
            for (String r : SutraWiring.reads(n.panel(), n.group())) {
                String on = "parent".equals(r) ? n.parent() == null ? null : n.parent().id() : r;
                if (on != null) {
                    dependents.computeIfAbsent(on, k -> new LinkedHashSet<>()).add(id);
                }
            }
            if (n.parent() != null) {
                dependents.computeIfAbsent(n.parent().id(), k -> new LinkedHashSet<>()).add(id);
            }
        });
        Set<String> out = new LinkedHashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>(List.of(panelId));
        while (!todo.isEmpty()) {
            for (String d : dependents.getOrDefault(todo.pop(), Set.of())) {
                if (!d.equals(panelId) && out.add(d)) {
                    todo.add(d);
                }
            }
        }
        return out;
    }

    private static SutraWiring.Node node(Screen s, String panelId) {
        SutraWiring.Node n = s.nodes().get(panelId);
        if (n == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "this screen has no panel '" + panelId + "'");
        }
        return n;
    }

    /** The wiring the console needs, {@code waits} only while the panel waits. */
    static ViewModel.Wire wire(Panel p, SutraWiring.Node n, String waits) {
        Wiring w = p.wiring();
        List<Map<String, String>> emits = w.emits().stream().map(e -> Map.of("name", e.name(), "on", e.on())).toList();
        List<Map<String, String>> kids = w.children().stream().map(g -> Map.of("place", g.place(), "show", g.show())).toList();
        Wiring.Source src = w.source() != null ? w.source() : n != null && n.group() != null ? n.group().source() : null;
        Map<String, String> source = null;
        if (src != null) {
            source = new LinkedHashMap<>();
            source.put("kind", src.kind());
            if (src.pack() != null) {
                source.put("pack", src.pack());
            }
        }
        return new ViewModel.Wire(emits, waits, kids, source, n == null || n.parent() == null ? null : n.parent().id());
    }

    private static String area(Panel p) {
        return p.area().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static DataNode node(Object v) {
        return v instanceof DataNode n ? n : DataNode.missing();
    }

    private static <T> T soft(java.util.function.Supplier<T> f) {
        try {
            return f.get();
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }
}
