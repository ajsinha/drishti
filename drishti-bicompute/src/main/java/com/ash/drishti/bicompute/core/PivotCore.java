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
package com.ash.drishti.bicompute.core;

import com.ash.drishti.kernel.api.pivot.AggregationPlan;
import com.ash.drishti.kernel.api.pivot.CellKey;
import com.ash.drishti.kernel.api.pivot.CellSet;
import com.ash.drishti.kernel.api.pivot.Dim;
import com.ash.drishti.kernel.api.pivot.Partials;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.bicompute.cluster.OrderedMerge;
import com.ash.drishti.kernel.api.pivot.AggregationPlan.FunctionCall;
import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.kernel.api.pivot.Kind;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * The pivot engine's core (ARCHITECTURE.md sections 26.1, 26.2 and 26.8): a view becomes cell sets (the cells, every open node's subtotals, the
 * axis totals and the grand total), planned level by level so that only open nodes are computed; each set is asked of the source's provider
 * once (the tabular provider answers sets that share a restriction in one {@code GROUP BY GROUPING SETS} query), through the partial-state
 * cache, merged in a fixed order when shards answer, and finished into figures. The core never computes a figure itself: a mergeable total
 * is its own set's state (made from its own rows), a holistic total its own set's figure; nothing is ever a sum of the figures shown.
 *
 * <p>Around it: the cell budget (refused with the count, never cut short), the busy gate for big views, the minimum members per cell of
 * aggregate-only roles, top N with a correct Others (its own members), sorting per level, provenance ids per answer and per cell.
 */
public final class PivotCore {

    /**
     * The engine's limits.
     *
     * @param maxCells the most visible cells a view may have, totals included
     * @param maxHolisticCells the most cells when a measure is holistic
     * @param busyCells a view of more cells asks the busy gate first
     * @param minMembersPerCell a cell with fewer members shows no figures (aggregate-only roles; 1: no limit)
     * @param provenanceKept the answers whose provenance is kept for the popover
     * @param localShards the parts a mergeable plan is split into on this node and merged in order (1: one pass)
     * @param confirmSeconds an estimate over this asks the person first
     * @param confirmRows an estimate over this many rows asks the person first
     */
    public record Settings(int maxCells, int maxHolisticCells, int busyCells, int minMembersPerCell, int provenanceKept, int localShards,
            double confirmSeconds, long confirmRows) {

        public static Settings defaults() {
            return new Settings(20_000, 5_000, 2_000, 1, 200, 1, 60, 500_000_000L);
        }
    }

    /** Busy protection (OPERATIONS_GUIDE.md section 12.5): refuses politely by throwing; the host's gate. */
    @FunctionalInterface
    public interface Admission {
        void admit(Viewer v, String what);
    }

    /**
     * The scale extension points (phase P3, ARCHITECTURE.md section 26.6): a host adds hooks for sketches chosen by size, rollups (aggregate
     * awareness), the cluster's scatter and gather, sampled first answers and measured scan rates. Each method has a default that does
     * nothing, so the core runs alone exactly as without them.
     */
    public interface Hooks {
        /** The view's parsed functions, before anything runs: a policy may answer a measure ≈ (its sketch) or exact; notes say so. */
        default void functions(Viewer v, PivotView view, PivotProvider p, ScaleOptions o, List<FunctionCall> functions,
                Supplier<PivotProvider.Estimate> estimate, List<String> notes) {}

        /** A plan answered before the provider is asked (a rollup, the cluster's nodes); empty when this hook does not answer it. */
        default Optional<Routed> route(Viewer v, PivotProvider p, AggregationPlan plan, ScaleOptions o) {
            return Optional.empty();
        }

        /** A provider that reads a sample of {@code fraction} of the rows (a ≈ first answer); empty: the core samples by row hash. */
        default Optional<PivotProvider> sampler(PivotProvider p, double fraction) {
            return Optional.empty();
        }

        /** A provider run finished, the whole plan read (measured scan rates); not called for a sampled run. */
        default void ran(Viewer v, PivotProvider p, AggregationPlan plan, Partials parts, long millis) {}

        /** The scan estimate's answer, to add to (the rollup that covers it, the nodes, the measured rate, the options). */
        default void estimated(Viewer v, PivotProvider p, AggregationPlan plan, Map<String, Object> answer) {}
    }

    /**
     * A plan answered by a hook.
     *
     * @param partials the states, as the provider would have answered them
     * @param how in words, for the answer's notes and provenance ("from the rollup desk-month")
     * @param cacheable true when the states equal the provider's (a rollup, the cluster); false for anything approximate
     */
    public record Routed(Partials partials, String how, boolean cacheable) {}

    private final List<Hooks> hooks = new CopyOnWriteArrayList<>();
    private final List<PivotProvider> providers;
    private final StateCache cache;
    private volatile Settings settings;
    private volatile Admission admission;
    private volatile Functions.Resolver resolver;
    private final Map<String, Map<String, Object>> provenance;
    /** The provenance ids in the order they were given: the oldest leave first beyond {@code provenanceKept}. */
    private final java.util.concurrent.ConcurrentLinkedDeque<String> provenanceOrder = new java.util.concurrent.ConcurrentLinkedDeque<>();

    public PivotCore(List<PivotProvider> providers, StateCache cache, Settings settings) {
        this.providers = List.copyOf(providers);
        this.cache = cache == null ? StateCache.none() : cache;
        this.settings = settings == null ? Settings.defaults() : settings;
        this.provenance = new java.util.concurrent.ConcurrentHashMap<>();
    }

    public void admission(Admission a) {
        this.admission = a;
    }

    /** Adds scale hooks; routes are tried in the order added (rollups before the cluster). */
    public void hooks(Hooks h) {
        hooks.add(h);
    }

    public List<Hooks> hooks() {
        return List.copyOf(hooks);
    }

    /** Custom functions (ARCHITECTURE.md section 26.5): the host's resolver of {@code fn:owner/name@v(...)} calls (null: none). */
    public void functions(Functions.Resolver r) {
        this.resolver = r;
    }

    public Settings settings() {
        return settings;
    }

    public void settings(Settings s) {
        this.settings = s;
    }

    public StateCache cache() {
        return cache;
    }

    public List<PivotProvider> providers() {
        return providers;
    }

    /** The provider of a source; refused in words when none serves it. */
    public PivotProvider provider(String source) {
        for (PivotProvider p : providers) {
            if (p.serves(source)) {
                return p;
            }
        }
        throw new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "no pivot source '" + source + "' here: pick a table from the field list "
                + "(GET /api/v1/pivot/sources)");
    }

    /** Every source this person may pivot, by provider. */
    public List<Map<String, Object>> sources(Viewer v) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PivotProvider p : providers) {
            p.sources(v).forEach((id, title) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("source", id);
                m.put("title", title);
                m.put("provider", p.id());
                out.add(m);
            });
        }
        return out;
    }

    /**
     * A source's fields, native measures and dates, and the built-in functions when the provider offers them; each field and measure with
     * its group, display folder, hidden flag and the functions valid for it, the source's hierarchies and the show-as forms (CatalogueMeta).
     */
    public Map<String, Object> catalogue(Viewer v, String source, String date) {
        PivotProvider p = provider(source);
        PivotProvider.Catalogue c = p.catalogue(v, source, date);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", c.source());
        m.put("title", c.title());
        m.put("provider", p.id());
        m.put("date", c.date());
        m.put("dates", c.dates());
        m.put("version", c.version());
        m.put("fields", c.fields());
        m.put("measures", c.measures());
        m.put("functions", p.capabilities().builtIns() ? Functions.describe() : List.of());
        m.put("masked", c.masked());
        m.put("notes", c.notes());
        m.put("defaultView", c.notes() == null ? null : c.notes().get("defaultView"));
        m.put("capabilities", p.capabilities());
        m.put("limits", Map.of("maxCells", settings.maxCells(), "maxHolisticCells", settings.maxHolisticCells(), "busyCells", settings.busyCells()));
        CatalogueMeta.put(m, c, p.capabilities().builtIns());          // the field list's metadata: groups, folders, hidden, hierarchies, functions
        return m;
    }

    // ---- one computation ------------------------------------------------------------------------------------------------------------

    /** A cell's merged states, members and notes. */
    static final class Cell {
        long members;
        Object[] states;
        String scope;
        Map<Integer, String> notes = new HashMap<>();
    }

    /** What a computation did, for the answer and the provenance. */
    static final class Work {
        int setsAsked;
        int setsFromCache;
        int queries;
        double sampled;
        long rowsRead = -1;
        long providerMs;
        final List<String> statements = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        /** How plans were answered other than by the provider (a rollup, the cluster), for the answer's "computed as". */
        final List<String> routes = new ArrayList<>();
    }

    /** A view being computed: its provider, catalogue, functions, axes, the cells known and the work done. */
    final class Computation {
        final Viewer viewer;
        final PivotView view;
        final PivotProvider provider;
        final PivotProvider.Catalogue catalogue;
        private java.util.function.BinaryOperator<String> names;
        final List<FunctionCall> functions = new ArrayList<>();
        final Map<String, Dim> dims = new LinkedHashMap<>();
        final Map<String, String> types = new HashMap<>();
        final Axis rows;
        final Axis cols;
        final Map<CellKey, Cell> cells = new HashMap<>();
        final Work work = new Work();
        final long t0 = System.nanoTime();
        final boolean holistic;
        final ScaleOptions scale;
        boolean uncacheable;
        final Map<String, String> units = new HashMap<>();
        final AggregationPlan.Money money;
        /** Levels whose top N a Space-Saving summary chose first (parent node key and dimension), with the words for provenance. */
        final Map<String, String> topChosen = new LinkedHashMap<>();
        /** The capped measures (function index to its cap): computed after the grid is laid out, for its first cells only. */
        final Map<Integer, PivotProvider.Cap> capped = new LinkedHashMap<>();
        /** The set each known cell came from (a capped measure asks the provider for the cell alone, cut from it). */
        final Map<CellKey, CellSet> origin = new HashMap<>();

        /** A member's display name from the provider, or null to show its value (read once per answer). */
        String memberName(String dim, String value) {
            if (names == null) {
                names = provider.memberNames(viewer, view.source(), view.date());
            }
            return dim == null || value == null ? null : names.apply(dim, value);
        }

        Computation(Viewer viewer, PivotView asked) {
            this(viewer, asked, ScaleOptions.AUTO);
        }

        Computation(Viewer viewer, PivotView asked, ScaleOptions scale) {
            this.viewer = viewer;
            this.scale = scale == null ? ScaleOptions.AUTO : scale;
            this.provider = provider(asked.source());
            this.view = provider.prepare(viewer, asked);             // a document source's paths, grain and once-per-parent figures
            this.catalogue = provider.catalogue(viewer, view.source(), view.date());
            catalogue.fields().forEach(f -> {
                types.put(f.name(), f.type());
                units.put(f.name(), f.unit());
            });
            for (PivotView.Value v : view.values()) {
                FunctionCall f = Functions.parse(v.measure(), v.label(), provider, resolver, viewer);
                for (String in : f.inputs()) {
                    field(in);
                }
                functions.add(f);
            }
            for (Dim d : view.rows()) {
                dim(d);
            }
            for (Dim d : view.columns()) {
                dim(d);
            }
            for (PivotView.Expansion e : view.expanded()) {
                if (e.by() != null) {
                    dim(Dim.parse(e.by()));
                }
                for (List<String> p : e.path()) {
                    dim(Dim.parse(p.get(0)));
                }
            }
            view.filters().keySet().forEach(k -> dim(Dim.parse(k)));
            view.slicers().forEach(k -> dim(Dim.parse(k)));
            for (Map.Entry<String, PivotView.Sort> s : view.sort().entrySet()) {
                measureIndex(s.getValue().by(), true);
            }
            for (Map.Entry<String, PivotView.Top> t : view.top().entrySet()) {
                measureIndex(t.getValue().by(), false);
            }
            this.rows = new Axis("rows", view.rows());
            this.cols = new Axis("columns", view.columns());
            this.money = money();
            if (!hooks.isEmpty()) {
                PivotProvider.Estimate[] once = new PivotProvider.Estimate[1];
                Supplier<PivotProvider.Estimate> est = () -> {
                    if (once[0] == null) {
                        once[0] = provider.estimate(viewer, plan(List.of(CellSet.of(List.of(), Map.of()))));
                    }
                    return once[0];
                };
                for (Hooks h : hooks) {
                    h.functions(viewer, view, provider, this.scale, functions, est, work.notes);
                }
            }
            for (int i = 0; i < functions.size(); i++) {
                Optional<PivotProvider.Cap> cap = provider.cap(functions.get(i).fn().name());
                if (cap.isPresent()) {
                    capped.put(i, cap.get());
                }
            }
            for (Map.Entry<String, PivotView.Sort> s : view.sort().entrySet()) {
                refuseCapped(measureIndex(s.getValue().by(), true), "sorted");
            }
            for (Map.Entry<String, PivotView.Top> t : view.top().entrySet()) {
                refuseCapped(measureIndex(t.getValue().by(), false), "ranked for a top N");
            }
            this.holistic = functions.stream().anyMatch(f -> f.fn().kind() == Kind.HOLISTIC);
            for (FunctionCall f : functions) {
                for (String in : f.inputs()) {
                    String u = units.getOrDefault(in, "");
                    if (("rate".equals(u) || "pct".equals(u)) && (f.fn().plainSum() || "wavg".equals(f.fn().name()) && f.inputs().indexOf(in) == 1)) {
                        throw PivotView.bad(f.id() + ": " + in + " is a " + ("pct".equals(u) ? "percentage" : "rate") + "; a sum of it means "
                                + "nothing. Use avg(" + in + ") or wavg(" + in + ", <a weight>), which are right at every level");
                    }
                }
            }
        }

        /** Money in this view: the source's money fields, its currency field, the view's conversion (section 5.4). */
        private AggregationPlan.Money money() {
            java.util.Set<String> fields = new java.util.LinkedHashSet<>();
            String ccyField = null;
            for (PivotProvider.Field f : catalogue.fields()) {
                if ("money".equals(f.unit())) {
                    fields.add(f.name());
                } else if ("currency".equals(f.unit()) && ccyField == null) {
                    ccyField = f.name();
                }
            }
            PivotView.Currency conv = view.currency();
            if (conv != null) {
                if (conv.field() != null) {
                    field(conv.field());
                    ccyField = conv.field();
                }
                if (ccyField == null) {
                    throw PivotView.bad("currency: " + catalogue.source() + " has no currency field; name it (currency.field)");
                }
            }
            return new AggregationPlan.Money(ccyField, fields, conv);
        }

        /** What a measure's figures are in: the report currency, a unit, or blank. */
        String unit(FunctionCall f) {
            if (f.fn().resultUnit() != null) {
                return f.fn().resultUnit();
            }
            if (money.money(f)) {
                return money.conversion() != null ? money.conversion().report() : "money";
            }
            if ("count".equals(f.fn().name()) || "count_distinct".equals(f.fn().name())) {
                return "count";
            }
            for (String in : f.inputs()) {
                String u = units.getOrDefault(in, "");
                if (!u.isEmpty() && !"currency".equals(u)) {
                    return u;
                }
            }
            for (PivotProvider.Native n : catalogue.measures()) {
                if (f.id().startsWith(n.name() + "(")) {
                    return n.unit() == null ? "" : n.unit();
                }
            }
            return "";
        }

        private void field(String name) {
            if (!types.containsKey(name)) {
                Object aggregate = catalogue.notes() == null ? null : catalogue.notes().get("aggregate");
                if (aggregate != null) {
                    // the provider offers this person fewer fields because they see the source in aggregate only: refused, not a typo
                    throw new DrishtiException(ErrorCode.FORBIDDEN, name + " is not offered to you: " + aggregate);
                }
                boolean masked = catalogue.masked() > 0;
                throw new DrishtiException(masked ? ErrorCode.PIVOT_MASKED : ErrorCode.PIVOT_BAD_VIEW, name + " is not a field of "
                        + catalogue.source() + " you may use" + (masked ? " (it is not there, or it is masked for you: masks apply before any "
                        + "aggregation)" : "; its fields: " + String.join(", ", catalogue.fields().stream().map(PivotProvider.Field::name).toList())));
            }
        }

        private void dim(Dim d) {
            field(d.field());
            String t = types.get(d.field());
            if (d.group() != null && Dim.DATE_GROUPS.contains(d.group()) && !"date".equals(t)) {
                throw PivotView.bad(d.id() + ": " + d.field() + " is a " + t + ", not a date; only dates group by " + d.group());
            }
            if (d.width() > 0 && !"number".equals(t)) {
                throw PivotView.bad(d.id() + ": " + d.field() + " is a " + t + ", not a number; only numbers group by width");
            }
            dims.putIfAbsent(d.id(), d);
        }

        /** The index of the measure called {@code label} (null or {@code label} with {@code labelOk}: -1, by label). */
        int measureIndex(String label, boolean labelOk) {
            if (label == null || labelOk && "label".equals(label)) {
                return labelOk ? -1 : 0;
            }
            for (int i = 0; i < functions.size(); i++) {
                if (functions.get(i).label().equals(label) || functions.get(i).id().equals(label)) {
                    return i;
                }
            }
            throw PivotView.bad("'" + label + "' is not a value of the view; its values: "
                    + String.join(", ", functions.stream().map(FunctionCall::label).toList()));
        }

        AggregationPlan plan(List<CellSet> sets) {
            return new AggregationPlan(view.source(), catalogue.date(), view.asOf(), view.filters(), dims, functions, sets, null,
                    Math.max(1_000, settings.maxCells()) * 50, money, view.options());
        }

        int expandTo(Axis a) {
            return a == rows ? view.expandRows() : view.expandColumns();
        }

        /** The dimension a discovered node is opened by, or null when it stays closed. */
        String opening(Axis a, Axis.Node n) {
            if (n.others) {
                return null;
            }
            for (PivotView.Expansion e : view.expanded()) {
                if (e.axis().equals(a.name) && e.path().equals(n.pairs())) {
                    String by = e.by() == null ? a.defaultChild(n) : Dim.parse(e.by()).id();
                    if (by != null && n.path.stream().anyMatch(p -> p[0].equals(by))) {
                        work.notes.add(n.key() + ": already broken down by " + by + "; expanded by the next dimension instead");
                        return a.defaultChild(n);
                    }
                    if (by != null && (a == rows ? cols : rows).dims.stream().anyMatch(d -> d.id().equals(by))) {
                        throw PivotView.bad(by + " is on the " + (a == rows ? "columns" : "rows") + ": a node is not broken down by it");
                    }
                    return by;
                }
            }
            return n.depth() < expandTo(a) ? a.defaultChild(n) : null;
        }

        void run() {
            List<Axis.Group> allRows = new ArrayList<>();
            List<Axis.Group> allCols = new ArrayList<>();
            List<Axis.Group> newRows = new ArrayList<>(List.of(Axis.Group.total(rows.root)));
            List<Axis.Group> newCols = new ArrayList<>(List.of(Axis.Group.total(cols.root)));
            if (!rows.dims.isEmpty()) {
                rows.root.childDim = rows.dims.get(0).id();
                newRows.add(new Axis.Group(rows.root, rows.root.childDim, false, List.of()));
            }
            if (!cols.dims.isEmpty()) {
                cols.root.childDim = cols.dims.get(0).id();
                newCols.add(new Axis.Group(cols.root, cols.root.childDim, false, List.of()));
            }
            int round = 0;
            while (!newRows.isEmpty() || !newCols.isEmpty()) {
                if (++round > 64) {
                    throw PivotView.bad("the view opens more than 64 levels");
                }
                newRows = TopFirst.choose(this, newRows, Axis.Group.total(cols.root));
                newCols = TopFirst.choose(this, newCols, Axis.Group.total(rows.root));
                LinkedHashSet<CellSet> sets = new LinkedHashSet<>();
                List<Axis.Group> colsNow = new ArrayList<>(allCols);
                colsNow.addAll(newCols);
                for (Axis.Group r : newRows) {
                    for (Axis.Group c : colsNow) {
                        sets.addAll(r.crossAll(c));
                    }
                }
                for (Axis.Group c : newCols) {
                    for (Axis.Group r : allRows) {
                        sets.addAll(r.crossAll(c));
                    }
                }
                evaluate(new ArrayList<>(sets));
                allRows.addAll(newRows);
                allCols.addAll(newCols);
                List<Axis.Group> nextRows = discover(rows, newRows, Axis.Group.total(cols.root));
                List<Axis.Group> nextCols = discover(cols, newCols, Axis.Group.total(rows.root));
                budget();
                if (round == 1 && admission != null && visibleCells() > settings.busyCells()) {
                    admission.admit(viewer, "A pivot of " + visibleCells() + " cells");
                }
                newRows = nextRows;
                newCols = nextCols;
            }
            capped(PivotAnswer.gridOrder(this));
        }

        /** The members of each new group (from its set crossed with the other axis's total): the nodes, sorted, top N, opened. */
        private List<Axis.Group> discover(Axis a, List<Axis.Group> groups, Axis.Group otherTotal) {
            List<Axis.Group> next = new ArrayList<>();
            for (Axis.Group g : groups) {
                if (g.dim() == null) {
                    continue;
                }
                CellSet s = g.cross(otherTotal);
                Map<String, String> at = s.at();
                List<String> values = new ArrayList<>();
                for (CellKey k : cells.keySet()) {
                    if (k.coords().size() == s.grouping().size() + s.marks().size() && k.coords().keySet().containsAll(s.grouping())
                            && k.coords().keySet().containsAll(s.marks().keySet()) && matches(k, at)) {
                        String v = k.coords().get(g.dim());
                        if (v != null && (g.others() ? CellKey.OTHERS.equals(v) : !CellKey.OTHERS.equals(v))) {
                            values.add(v);
                        }
                    }
                }
                if (g.others()) {
                    if (!values.isEmpty()) {
                        boolean sketched = topChosen.containsKey(g.parent().key() + "\u0001" + g.dim());
                        Axis.Node o = g.parent().child(g.dim(), CellKey.OTHERS, true, sketched ? -1 : g.parent().levelMembers - g.kept().size());
                        o.childDim = null;
                    }
                    continue;
                }
                g.parent().childDim = g.dim();
                List<String> order = order(g.parent(), g.dim(), values);
                g.parent().levelMembers = order.size();
                PivotView.Top top = view.top().get(g.dim());
                List<String> shown = order;
                if (top != null && order.size() > top.n()) {
                    int mi = measureIndex(top.by(), false);
                    List<String> ranked = new ArrayList<>(order);
                    ranked.sort(Comparator.comparing((String v) -> figure(g.parent(), g.dim(), v, mi), Comparator.nullsLast(Comparator.reverseOrder())));
                    List<String> kept = ranked.subList(0, top.n());
                    shown = order.stream().filter(kept::contains).toList();
                    next.add(new Axis.Group(g.parent(), g.dim(), true, List.copyOf(kept)));
                }
                for (String v : shown) {
                    Axis.Node n = g.parent().child(g.dim(), v, false, 0);
                    String by = opening(a, n);
                    if (by != null) {
                        next.add(new Axis.Group(n, by, false, List.of()));
                    }
                }
            }
            return next;
        }

        boolean matchesAt(CellKey k, Map<String, String> at) {
            return matches(k, at);
        }

        private boolean matches(CellKey k, Map<String, String> at) {
            for (Map.Entry<String, String> e : at.entrySet()) {
                if (!e.getValue().equals(k.coords().get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }

        /** A member's figure for ranking and sorting: its total across the other axis. */
        Double figure(Axis.Node parent, String dim, String value, int mi) {
            Map<String, String> c = new TreeMap<>(parent.coords());
            c.put(dim, value);
            Cell cell = cells.get(CellKey.of(c));
            return cell == null ? null : functions.get(mi).fn().finish(cell.states[mi]);
        }

        /** The siblings in their level's order: by a measure when the view sorts by one, else by label in the dimension's type's order. */
        private List<String> order(Axis.Node parent, String dim, List<String> values) {
            List<String> out = new ArrayList<>(values);
            PivotView.Sort s = view.sort().get(dim);
            Dim d = dims.get(dim);
            String type = d == null ? "string" : d.width() > 0 ? "number" : d.group() != null ? "date" : types.getOrDefault(d.field(), "string");
            Comparator<String> byLabel = labelOrder(type);
            if (s != null && s.by() != null && !"label".equals(s.by())) {
                int mi = measureIndex(s.by(), true);
                Comparator<Double> nums = s.desc() ? Comparator.reverseOrder() : Comparator.naturalOrder();
                out.sort(Comparator.comparing((String v) -> figure(parent, dim, v, mi), Comparator.nullsLast(nums)).thenComparing(byLabel));
            } else {
                out.sort(s != null && s.desc() ? byLabel.reversed() : byLabel);
            }
            return out;
        }

        void budget() {
            int n = visibleCells();
            int max = holistic ? Math.min(settings.maxCells(), settings.maxHolisticCells()) : settings.maxCells();
            if (n > max) {
                throw new DrishtiException(ErrorCode.PIVOT_TOO_MANY_CELLS, "the view has " + n + " cells with its totals ("
                        + visibleNodes(rows.root) + " rows × " + visibleNodes(cols.root) + " columns), more than the budget of " + max
                        + (holistic ? " for a view with a holistic measure (drishti.pivot.max-holistic-cells)" : " (drishti.pivot.max-cells)")
                        + ": filter it, collapse a level or show the top N of a level; a view is never cut short");
            }
        }

        int visibleCells() {
            return visibleNodes(rows.root) * visibleNodes(cols.root);
        }

        /** The node itself and its visible descendants (a header and a total row count as one: they share their cells). */
        int visibleNodes(Axis.Node n) {
            int c = 1;
            for (Axis.Node ch : n.children) {
                c += visibleNodes(ch);
            }
            return c;
        }

        /** A sort or a top N by a capped measure is refused in words: which cells get its figure follows the grid's order, set by them. */
        private void refuseCapped(int mi, String how) {
            if (mi >= 0 && capped.containsKey(mi)) {
                throw PivotView.bad(functions.get(mi).label() + " is computed for the first " + capped.get(mi).cells() + " cells of a view only, "
                        + "in the grid's order, so the view is not " + how + " by it: sort or rank by another value");
            }
        }

        /** Asks the provider for the sets, through the cache: a set whose every function is cached is not asked again. */
        void evaluate(List<CellSet> sets) {
            List<Integer> which = new ArrayList<>();
            for (int f = 0; f < functions.size(); f++) {
                if (!capped.containsKey(f)) {
                    which.add(f);                                   // a capped measure waits for the grid (capped(), below)
                }
            }
            evaluate(sets, which);
        }

        /**
         * The capped measures for the first cells of {@code order} (the grid's order; PivotProvider.cap): those cells are asked of the
         * provider one by one, each cut from the set it came from (through the cache: a cell's figure is its own, whichever view asks), and
         * every other known cell carries the cap's note and no figure, even when a figure of it is cached. The same view therefore always
         * gets the same figures, whatever was asked before it or beside it.
         */
        void capped(List<CellKey> order) {
            if (capped.isEmpty()) {
                return;
            }
            Map<List<Integer>, List<CellSet>> byFunctions = new LinkedHashMap<>();
            List<CellKey> known = order.stream().filter(cells::containsKey).distinct().toList();
            for (int i = 0; i < known.size(); i++) {
                List<Integer> fs = new ArrayList<>();
                for (Map.Entry<Integer, PivotProvider.Cap> e : capped.entrySet()) {
                    if (i < e.getValue().cells()) {
                        fs.add(e.getKey());
                    }
                }
                if (!fs.isEmpty()) {
                    byFunctions.computeIfAbsent(fs, k -> new ArrayList<>()).add(alone(known.get(i)));
                }
            }
            Set<CellKey> first = new java.util.HashSet<>();
            for (Map.Entry<Integer, PivotProvider.Cap> e : capped.entrySet()) {
                first.clear();
                first.addAll(known.subList(0, Math.min(known.size(), e.getValue().cells())));
                for (Map.Entry<CellKey, Cell> c : cells.entrySet()) {
                    if (!first.contains(c.getKey())) {
                        c.getValue().states[e.getKey()] = null;
                        c.getValue().notes.put(e.getKey(), e.getValue().note());
                    }
                }
            }
            byFunctions.forEach((fs, sets) -> evaluate(sets, fs));
        }

        /** One known cell as a set of its own: the set it came from, restricted to the cell's coordinates. */
        private CellSet alone(CellKey key) {
            CellSet from = origin.get(key);
            Map<String, String> at = new LinkedHashMap<>(from.at());
            for (String d : from.grouping()) {
                at.put(d, key.coords().get(d));
            }
            return new CellSet(from.grouping(), at, from.exclude(), from.marks());
        }

        /**
         * Asks the provider for the functions {@code which} of the sets, through the cache. With no function to ask (every value of the view
         * is capped) the provider is still asked, for the cells and their members.
         */
        private void evaluate(List<CellSet> sets, List<Integer> which) {
            if (sets.isEmpty()) {
                return;
            }
            String prefix = provider.id() + "|" + provider.version(viewer, view.source(), catalogue.date()) + "|" + view.source() + "|"
                    + catalogue.date() + "|" + view.asOf() + "|" + plan(List.of()).filterSignature() + "|" + (view.currency() == null ? ""
                            : view.currency().toString()) + "|" + view.optionSignature() + "|";
            Map<List<Integer>, List<CellSet>> byMissing = new LinkedHashMap<>();
            for (CellSet s : sets) {
                work.setsAsked++;
                List<Integer> missing = new ArrayList<>();
                for (int f : which) {
                    Object hit = scale.sampled() ? null : cache.get(prefix + s.signature() + "|" + functions.get(f).id());
                    if (hit instanceof Cached c) {
                        absorb(c, f, s);
                    } else {
                        missing.add(f);
                    }
                }
                if (missing.isEmpty() && !which.isEmpty()) {
                    work.setsFromCache++;
                } else {
                    byMissing.computeIfAbsent(missing, k -> new ArrayList<>()).add(s);
                }
            }
            for (Map.Entry<List<Integer>, List<CellSet>> e : byMissing.entrySet()) {
                List<FunctionCall> fns = e.getKey().stream().map(functions::get).toList();
                AggregationPlan p = plan(e.getValue()).with(e.getValue(), fns);
                Partials parts = run(p);
                work.queries += parts.stats().queries();
                work.statements.addAll(parts.stats().statements());
                work.providerMs += parts.stats().millis();
                if (parts.stats().rowsRead() >= 0) {
                    work.rowsRead = Math.max(0, work.rowsRead) + parts.stats().rowsRead();
                }
                for (Partials.SetResult r : parts.sets()) {
                    if (fns.isEmpty()) {
                        members(r);
                    }
                    for (int j = 0; j < fns.size(); j++) {
                        int f = e.getKey().get(j);
                        Cached c = new Cached(new TreeMap<>(), new TreeMap<>(), new HashMap<>(), new HashMap<>());
                        long bytes = 64;
                        for (Map.Entry<CellKey, Partials.Entry> x : r.cells().entrySet()) {
                            Object st = x.getValue().states()[j];
                            c.states.put(x.getKey(), st);
                            c.members.put(x.getKey(), x.getValue().members());
                            if (x.getValue().scope() != null) {
                                c.scopes.put(x.getKey(), x.getValue().scope());
                            }
                            String note = x.getValue().notes().get(j);
                            if (note != null) {
                                c.notes.put(x.getKey(), note);
                            }
                            bytes += 96 + 2L * x.getKey().canonical().length() + fns.get(j).fn().bytes(st);
                        }
                        if (!scale.sampled() && !uncacheable) {
                            cache.put(prefix + r.set().signature() + "|" + functions.get(f).id(), c, bytes);
                        }
                        absorb(c, f, r.set());
                    }
                }
            }
        }

        /** The plan run: one pass, or split into this node's shards and merged in order (mergeable plans of a provider that shards). */
        Partials run(AggregationPlan p) {
            long t = System.nanoTime();
            Partials out = route(p);
            for (Hooks h : scale.sampled() ? List.<Hooks>of() : hooks) {
                h.ran(viewer, provider, p, out, (System.nanoTime() - t) / 1_000_000);
            }
            return out;
        }

        /** A sample (the ≈ first answer), a hook's route (a rollup, the cluster), or the provider. */
        private Partials route(AggregationPlan p) {
            if (scale.sampled()) {
                return sample(p);
            }
            for (Hooks h : hooks) {
                Optional<Routed> r = h.route(viewer, provider, p, scale);
                if (r.isPresent()) {
                    if (!work.notes.contains(r.get().how())) {
                        work.notes.add(r.get().how());
                        work.routes.add(r.get().how());
                    }
                    uncacheable |= !r.get().cacheable();
                    return r.get().partials();
                }
            }
            return direct(p);
        }

        /** The plan on a sample: a hook's sampling provider, else one row-hash shard of 1/f (mergeable plans of a provider that shards). */
        private Partials sample(AggregationPlan p) {
            for (Hooks h : hooks) {
                Optional<PivotProvider> sp = h.sampler(provider, scale.sampleFraction());
                if (sp.isPresent()) {
                    work.sampled = scale.sampleFraction();
                    return sp.get().evaluate(viewer, p);
                }
            }
            int every = (int) Math.max(2, Math.round(1 / scale.sampleFraction()));
            if (provider.capabilities().shards() && OrderedMerge.mergeable(p)) {
                work.sampled = 1.0 / every;
                return provider.evaluate(viewer, p.on(new AggregationPlan.Shard(0, every)));
            }
            work.sampled = 1.0;
            return direct(p);
        }

        private Partials direct(AggregationPlan p) {
            int n = settings.localShards();
            if (n <= 1 || !provider.capabilities().shards() || !OrderedMerge.mergeable(p)) {
                return provider.evaluate(viewer, p);
            }
            List<Partials> parts = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                parts.add(provider.evaluate(viewer, p.on(new AggregationPlan.Shard(i, n))));
            }
            return OrderedMerge.merge(p, parts);
        }

        private void absorb(Cached c, int f, CellSet from) {
            for (Map.Entry<CellKey, Object> x : c.states.entrySet()) {
                Cell cell = cell(x.getKey(), from);
                cell.states[f] = x.getValue();
                cell.members = Math.max(cell.members, c.members.getOrDefault(x.getKey(), 0L));
                if (c.scopes.containsKey(x.getKey())) {
                    cell.scope = c.scopes.get(x.getKey());
                }
                if (c.notes.containsKey(x.getKey())) {
                    cell.notes.put(f, c.notes.get(x.getKey()));
                }
            }
        }

        /** A known cell, made when first seen (with the set it came from). */
        private Cell cell(CellKey key, CellSet from) {
            origin.putIfAbsent(key, from);
            return cells.computeIfAbsent(key, k -> {
                Cell nc = new Cell();
                nc.states = new Object[functions.size()];
                return nc;
            });
        }

        /** The cells of a set asked for no function (every value of the view is capped): their members and scopes only. */
        private void members(Partials.SetResult r) {
            for (Map.Entry<CellKey, Partials.Entry> x : r.cells().entrySet()) {
                Cell cell = cell(x.getKey(), r.set());
                cell.members = Math.max(cell.members, x.getValue().members());
                if (x.getValue().scope() != null) {
                    cell.scope = x.getValue().scope();
                }
            }
        }

        long millis() {
            return (System.nanoTime() - t0) / 1_000_000;
        }
    }

    /** A set's states for one function, as the cache keeps them. */
    record Cached(TreeMap<CellKey, Object> states, TreeMap<CellKey, Long> members, Map<CellKey, String> scopes, Map<CellKey, String> notes) {}

    /** The order of labels by type: numbers by value, dates and text by text; the blank and Others last. */
    static Comparator<String> labelOrder(String type) {
        Comparator<String> base = "number".equals(type) ? Comparator.comparing((String s) -> {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return Double.MAX_VALUE;
            }
        }).thenComparing(Comparator.naturalOrder()) : Comparator.naturalOrder();
        return Comparator.comparing((String s) -> CellKey.BLANK.equals(s) || CellKey.OTHERS.equals(s)).thenComparing(base);
    }

    // ---- the API -------------------------------------------------------------------------------------------------------------------

    /** The view's answer (section 4.4): the axes as shown, every visible cell's figures, totals, provenance id. */
    public Map<String, Object> query(Viewer v, PivotView view) {
        return query(v, view, ScaleOptions.AUTO);
    }

    /**
     * The view's answer computed as {@code scale} asks: exact on request, ≈ by sketches, or from a sample (the first answer of a
     * progressive pair, ARCHITECTURE.md section 26.6; never cached).
     */
    public Map<String, Object> query(Viewer v, PivotView view, ScaleOptions scale) {
        Computation c = new Computation(v, view, scale);
        c.run();
        Map<String, Object> answer = PivotAnswer.build(this, c);
        keep(c, answer);
        return answer;
    }

    /**
     * A node opened (lazy expansion, section 11): the view with the node expanded by {@code by}; only the node's children are computed
     * (every other cell comes from the partial-state cache). The answer is the whole view, with {@code expandedNode} naming the node.
     */
    public Map<String, Object> expand(Viewer v, PivotView view, String axis, List<List<String>> path, String by) {
        List<PivotView.Expansion> e = new ArrayList<>(view.expanded());
        String ax = "columns".equals(axis) ? "columns" : "rows";
        e.removeIf(x -> x.axis().equals(ax) && x.path().equals(path));
        e.add(new PivotView.Expansion(ax, path, by));
        PivotView opened = view.withExpanded(e, view.expandRows(), view.expandColumns());
        Map<String, Object> a = query(v, opened);
        a.put("expandedNode", Map.of("axis", ax, "path", path, "by", by == null ? "" : by));
        return a;
    }

    /** The view on two dates, cell by cell (time travel and compare, section 10 item 2): each side's answer and the differences. */
    /** Two answers of the same view, cell by cell ({@code {answer, other, diff}}): what-if against the original, two dates. */
    public static Map<String, Object> compareAnswers(Map<String, Object> a, Map<String, Object> b) {
        return PivotAnswer.compare(a, b);
    }

    public Map<String, Object> compare(Viewer v, PivotView view, String otherDate) {
        Map<String, Object> a = query(v, view);
        Map<String, Object> b = query(v, view.on(otherDate));
        return PivotAnswer.compare(a, b);
    }

    /** The scan estimate (section 9.7): what the view's first level reads, before anything runs. */
    public Map<String, Object> estimate(Viewer v, PivotView view) {
        Computation c = new Computation(v, view);
        List<CellSet> sets = new ArrayList<>(List.of(CellSet.of(List.of(), Map.of())));
        PivotProvider.Estimate e = c.provider.estimate(v, c.plan(sets));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", e.rows());
        m.put("bytes", e.bytes());
        m.put("columns", e.columns());
        m.put("ofColumns", e.ofColumns());
        m.put("seconds", e.seconds());
        m.put("how", e.how());
        boolean confirm = e.seconds() > settings.confirmSeconds() || e.rows() > settings.confirmRows();
        m.put("confirm", confirm);
        m.put("words", PivotAnswer.estimateWords(e, confirm));
        for (Hooks h : hooks) {
            h.estimated(v, c.provider, c.plan(sets), m);
        }
        return m;
    }

    /** One cell by its coordinates (a cell reference, section 10 item 8): its figures from its own members; not in the view, said so. */
    public Map<String, Object> cell(Viewer v, PivotView view, Map<String, String> coords) {
        Computation c = new Computation(v, view);
        coords.keySet().forEach(k -> c.dim(Dim.parse(k)));
        CellSet s = CellSet.of(new ArrayList<>(coords.keySet()), coords);
        c.evaluate(List.of(s));
        c.capped(List.of(CellKey.of(coords)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("coords", coords);
        Cell cell = c.cells.get(CellKey.of(coords));
        if (cell == null) {
            out.put("found", false);
            out.put("note", "not in the view: no member of " + view.source() + " has " + CellKey.of(coords).canonical() + " under the view's filters");
            return out;
        }
        out.put("found", true);
        out.put("members", cell.members);
        out.put("values", PivotAnswer.values(this, c, cell).get("values"));
        out.put("scope", cell.scope);
        return out;
    }

    /**
     * The merged states of every cell of one set (explain and what-if, ARCHITECTURE.md section 26.7 items 1 and 3): the view's functions, and
     * per cell key its members and one state per function, made by the provider from the cells' own rows (through the cache). An extension
     * point for the module's explain and what-if; the view's own answer is unchanged.
     *
     * @param functions the view's functions, in its value order
     * @param states cell key to its states
     * @param members cell key to its members
     * @param date the business date read
     */
    public record SetStates(List<FunctionCall> functions, Map<CellKey, Object[]> states, Map<CellKey, Long> members, String date) {}

    /** The states of the cells of {@code set} for the view's values, filters and date. */
    public SetStates states(Viewer v, PivotView view, CellSet set) {
        Computation c = new Computation(v, view);
        set.grouping().forEach(k -> c.dim(Dim.parse(k)));
        set.at().keySet().forEach(k -> c.dim(Dim.parse(k)));
        c.evaluate(List.of(set));
        Map<CellKey, Object[]> states = new TreeMap<>();
        Map<CellKey, Long> members = new TreeMap<>();
        java.util.Set<String> shape = new java.util.TreeSet<>(set.grouping());
        shape.addAll(set.marks().keySet());
        c.capped(c.cells.keySet().stream().filter(k -> k.coords().keySet().equals(shape) && c.matchesAt(k, set.at())).sorted().toList());
        for (Map.Entry<CellKey, Cell> e : c.cells.entrySet()) {
            CellKey k = e.getKey();
            if (!k.coords().keySet().equals(shape) || !c.matchesAt(k, set.at())) {
                continue;
            }
            states.put(k, e.getValue().states.clone());
            members.put(k, e.getValue().members);
        }
        return new SetStates(List.copyOf(c.functions), states, members, c.catalogue.date());
    }

    /** The resolver of custom functions (null: none). */
    public Functions.Resolver functions() {
        return resolver;
    }

    /** A cell's member rows (drill-through), paged, as the person may see them. */
    public PivotProvider.Rows drill(Viewer v, PivotView view, Map<String, String> coords, int offset, int limit) {
        Computation c = new Computation(v, view);
        coords.keySet().forEach(k -> c.dim(Dim.parse(k)));
        if (coords.containsValue(CellKey.OTHERS)) {
            throw PivotView.bad("the rows of an Others member are not listed yet: drill into one of the shown members, or raise top N");
        }
        return c.provider.drill(v, c.plan(List.of()), CellKey.of(coords), Math.max(0, offset), Math.max(1, Math.min(limit, 10_000)));
    }

    /** The provenance of an answer ({@code cell}: {@code rowKey|colKey}; blank: the answer's shared provenance only). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> provenance(String id, String cell) {
        Map<String, Object> kept = provenance.get(id == null ? "" : id);
        if (kept == null) {
            throw new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "provenance " + id + " is not kept any more (the last "
                    + settings.provenanceKept() + " answers keep theirs): ask the view again");
        }
        Map<String, Object> out = new LinkedHashMap<>((Map<String, Object>) kept.get("shared"));
        out.put("id", id);
        if (cell != null && !cell.isBlank()) {
            Map<String, Object> e = ((Map<String, Map<String, Object>>) kept.get("cells")).get(cell.strip());
            if (e == null) {
                throw new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "the answer " + id + " has no cell " + cell);
            }
            out.put("cell", e);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void keep(Computation c, Map<String, Object> answer) {
        Map<String, Object> kept = new LinkedHashMap<>();
        kept.put("shared", answer.get("provenanceShared"));
        kept.put("cells", answer.get("cells"));
        String id = String.valueOf(answer.get("provenance"));
        provenance.put(id, kept);
        provenanceOrder.add(id);
        int keep = Math.max(10, settings.provenanceKept());
        while (provenanceOrder.size() > keep) {
            String old = provenanceOrder.poll();
            if (old != null) {
                provenance.remove(old);
            }
        }
    }

    /** The set of measure kinds of a view's answer (for a column header's mix). */
    static Set<String> kinds(List<FunctionCall> fns) {
        Set<String> s = new LinkedHashSet<>();
        fns.forEach(f -> s.add(f.fn().kind().word()));
        return s;
    }
}
