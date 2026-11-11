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
package com.ash.drishti.banking.var;

import com.ash.drishti.kernel.api.pivot.AggregationPlan;
import com.ash.drishti.kernel.api.pivot.CellKey;
import com.ash.drishti.kernel.api.pivot.CellSet;
import com.ash.drishti.kernel.api.pivot.Partials;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.bicompute.functions.Final;
import com.ash.drishti.kernel.api.pivot.AggFunction;
import com.ash.drishti.kernel.api.pivot.Kind;
import com.ash.drishti.kernel.api.pivot.PivotProvider;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.var.Columns;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.banking.compute.var.VarCalc;
import com.ash.drishti.banking.compute.var.VarMath;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.banking.frtb.FrtbService;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The VaR provider of the pivot engine (ARCHITECTURE.md section 26.4, BI_GUIDE.md#p29-the-mr-cube-preset), source {@code var}: the VaR engine's book of a
 * business date as the person sees it. Members are the date's trades; a cell is the view's scope ({@code options.scope}) under its filters
 * with each coordinate, its members found from per-trade dimension codes ({@link VarPivotCodes}); every cell's and total's vector is summed
 * in <b>one</b> batch for the whole plan ({@code VarBook.vectors}: on a sharded date one call per remote shard for all the sets) and its
 * figures are the engine's own statistics of that vector through the view's lens ({@link VarPivotLens}). So every cell and every total
 * equals {@code GET /api/v1/risk/var/scope} of its scope, and a total is never a sum of cells (holistic); each cell's scope text is in its
 * provenance, to be asked of the engine itself.
 *
 * <p>With the MR cube running (engine {@code mr-cube}) the source is the market-risk cube: hierarchy levels, {@code RISK_CLASS},
 * {@code POSITION} and {@code TRADE} as dimensions; component VaR and ES, incremental VaR, the limit and its use, stressed VaR, DV01, CS01 and
 * FRTB-SA SBM as measures; the scope and the lens as the view's options. Without it the source offers VaR, ES, PV and trades by the
 * positions' columns.
 */
public final class VarPivotProvider implements PivotProvider {

    /** The source id. */
    public static final String SOURCE = "var";
    static final List<Native> BASIC = List.of(
            new Native("var", "VaR", Kind.HOLISTIC, "money", "the cell's own VaR from the summed scenario P&L vector of its trades (the VaR engine's)"),
            new Native("es", "ES", Kind.HOLISTIC, "money", "the cell's own expected shortfall from its summed vector"),
            new Native("pv", "PV", Kind.HOLISTIC, "money", "the sum of the cell's trades' present values"),
            new Native("trades", "Trades", Kind.HOLISTIC, "count", "the trades in the cell"),
            new Native("pvar", "Parametric VaR", Kind.HOLISTIC, "money", "the cell's parametric VaR, z·σ − μ of its summed scenario P&L vector "
                    + "(a cross-check, never official; docs/guides/VAR_GUIDE.md)"));
    static final List<Native> MR = List.of(
            new Native("comp_var", "Comp VaR", Kind.HOLISTIC, "money", "the cell's share of the view's total VaR (Euler: the parts add up to the total)"),
            new Native("comp_es", "Comp ES", Kind.HOLISTIC, "money", "the cell's share of the view's total ES (Euler)"),
            new Native("incr_var", "Incr VaR", Kind.HOLISTIC, "money", "the view's total VaR minus the total without the cell"),
            new Native("limit", "Limit", Kind.HOLISTIC, "money", "the VaR limit of the node the cell is, whole as you see it (var-limit)"),
            new Native("limit_use", "Limit use", Kind.HOLISTIC, "pct", "the cell's VaR over its node's limit, on the limit's horizon and confidence"),
            new Native("svar", "Stressed VaR", Kind.HOLISTIC, "money", "the cell's VaR on the stress set, computed scope by scope (USD)"),
            new Native("dv01", "DV01", Kind.HOLISTIC, "money", "the cell's interest-rate deltas per basis point (GIRR) from the position sensitivities"),
            new Native("cs01", "CS01", Kind.HOLISTIC, "money", "the cell's credit-spread deltas (CSR) from the position sensitivities"),
            new Native("sbm", "SBM capital", Kind.HOLISTIC, "money", "FRTB-SA sensitivities-based capital of the cell's own scope"));
    /** The measures whose total is the plain sum of its parts (an export may write SUM formulas for them). */
    static final Set<String> PLAIN = Set.of("pv", "trades", "comp_var", "comp_es", "dv01", "cs01");
    /** The measures read from the cells' summed vectors. */
    static final Set<String> VECTOR = Set.of("var", "es", "comp_var", "comp_es", "incr_var", "limit_use", "pvar");

    /** Parametric VaR's settings (pvar()); the defaults until the server hands its own. */
    private VarParametricProperties parametric = VarParametricProperties.defaults();

    /** The server's parametric settings: pvar() follows them, and answers in words while the measure is off. */
    public VarPivotProvider parametric(VarParametricProperties p) {
        this.parametric = p == null ? VarParametricProperties.defaults() : p;
        return this;
    }

    private final VarService svc;
    private final BooleanSupplier on;
    private final BooleanSupplier mr;
    private final MrCubeProperties props;
    private final Supplier<FrtbService> frtb;
    private final VarPivotCodes codes;

    public VarPivotProvider(VarService svc) {
        this(svc, () -> true);
    }

    /** {@code on}: whether the VaR engine runs here (ADMIN_GUIDE.md: the pivot engine uses var); off, the source is not offered. */
    public VarPivotProvider(VarService svc, BooleanSupplier on) {
        this(svc, on, () -> false, MrCubeProperties.defaults(), () -> null);
    }

    /** {@code mr}: whether the MR cube runs here (engine {@code mr-cube}); {@code frtb}: the FRTB-SA engine when it runs, else null. */
    public VarPivotProvider(VarService svc, BooleanSupplier on, BooleanSupplier mr, MrCubeProperties props, Supplier<FrtbService> frtb) {
        this.svc = svc;
        this.on = on;
        this.mr = mr;
        this.props = props == null ? MrCubeProperties.defaults() : props;
        this.frtb = frtb == null ? () -> null : frtb;
        this.codes = new VarPivotCodes(this.props);
    }

    VarService service() {
        return svc;
    }

    MrCubeProperties props() {
        return props;
    }

    Supplier<FrtbService> frtb() {
        return frtb;
    }

    /** The names of the last date asked: reference data, the same for every viewer (the loader reads each kind's names once per load). */
    private volatile Map.Entry<String, java.util.function.BinaryOperator<String>> names;

    /**
     * Desk, book, legal entity and the other hierarchy levels by their entity's name (the VaR tree's, {@code VarLoader.names}). A dimension
     * is a position column ({@code DESK}) or a level of a hierarchy ({@code MGMT.DESK}); a level with no name, or a name not readable, shows its id.
     */
    @Override
    public java.util.function.BinaryOperator<String> memberNames(Viewer v, String source, String date) {
        if (!on.getAsBoolean()) {
            return (dim, value) -> null;
        }
        String key;
        LocalDate day;
        try {
            day = svc.scoped(principal(v), "BANK", day(date)).date().date();
            key = version(v, source, date).split("\\|", 4)[2] + "|" + day;
        } catch (RuntimeException e) {
            return (dim, value) -> null;                         // no date to name: the ids stand
        }
        Map.Entry<String, java.util.function.BinaryOperator<String>> held = names;
        if (held != null && held.getKey().equals(key)) {
            return held.getValue();
        }
        Hierarchies hs = svc.hierarchies();
        Map<String, String> levels = new HashMap<>();
        hs.specs().forEach(s -> s.levelNames().forEach(l -> levels.put(l.toUpperCase(Locale.ROOT).replace('-', '_'), l)));
        com.ash.drishti.banking.compute.var.Cells.Names read = svc.loader().names(com.ash.drishti.api.AsOf.of(day), hs);
        java.util.function.BinaryOperator<String> out = (dim, value) -> {
            String level = levels.get(dim.substring(dim.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT).replace('-', '_'));
            try {
                return level == null ? null : read.of(level, value);
            } catch (RuntimeException e) {
                return null;
            }
        };
        names = Map.entry(key, out);
        return out;
    }

    /** True when the MR cube runs here. */
    boolean mr() {
        return mr.getAsBoolean();
    }

    /** Releases the kept dimension codes (the MR cube switched off). */
    void release() {
        codes.clear();
    }

    long bytes() {
        return codes.bytes();
    }

    @Override
    public String id() {
        return "var";
    }

    @Override
    public boolean serves(String source) {
        return SOURCE.equals(source) && on.getAsBoolean();
    }

    @Override
    public Map<String, String> sources(Viewer v) {
        if (!on.getAsBoolean()) {
            return Map.of();
        }
        return Map.of(SOURCE, mr() ? "VaR · MR cube (the VaR engine's trades and scenario vectors)" : "VaR (the VaR engine's trades and scenario vectors)");
    }

    @Override
    public String exactOnly() {
        return "VaR is never sampled: it shows when exact";
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(false, false, true, true);
    }

    /** The measures offered here: the basic four, and the MR cube's when it runs (SBM only with the FRTB-SA engine). */
    List<Native> natives() {
        if (!mr()) {
            return BASIC;
        }
        List<Native> out = new ArrayList<>(BASIC);
        boolean f = frtb.get() != null;
        for (Native n : MR) {
            if (f || !Set.of("dv01", "cs01", "sbm").contains(n.name())) {
                out.add(n);
            }
        }
        return out;
    }

    /**
     * Stressed VaR and SBM are computed scope by scope (a VaR run on the stress set, an FRTB-SA run), so a view gets them for its first
     * {@code drishti.cube.mr.stressed-max-cells} and {@code sbm-max-cells} cells in the grid's order (row by row as shown, each row's columns
     * left to right), whatever was asked before; the pivot engine asks this provider for those cells only (PivotCore, capped measures).
     */
    @Override
    public Optional<Cap> cap(String function) {
        return switch (function) {
            case "svar" -> Optional.of(new Cap(props.stressedMaxCells(), "stressed VaR is computed for the first " + props.stressedMaxCells()
                    + " cells of a view, row by row as the grid shows them (drishti.cube.mr.stressed-max-cells)"));
            case "sbm" -> Optional.of(new Cap(props.sbmMaxCells(), "SBM is computed for the first " + props.sbmMaxCells()
                    + " cells of a view, row by row as the grid shows them (drishti.cube.mr.sbm-max-cells)"));
            default -> Optional.empty();
        };
    }

    @Override
    public Optional<AggFunction<Object>> nativeFunction(String name, List<String> args) {
        for (Native n : BASIC) {
            if (n.name().equals(name)) {
                return Optional.of(Final.function(name, PLAIN.contains(name)));
            }
        }
        for (Native n : MR) {
            if (n.name().equals(name)) {
                if (!mr()) {
                    throw PivotView.bad(name + "() is the MR cube's (engine mr-cube), which is not running on this server: the VaR source offers "
                            + "var(), es(), pv() and trades()");
                }
                return Optional.of(Final.function(name, PLAIN.contains(name)));
            }
        }
        return Optional.empty();
    }

    private static Principal principal(Viewer v) {
        if (v.principal() instanceof Principal p) {
            return p;
        }
        throw new DrishtiException(ErrorCode.FORBIDDEN, "the pivot engine needs a signed-in person");
    }

    static LocalDate day(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.strip());
        } catch (DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.BAD_BUSINESS_DATE, "not a date: '" + date + "' (write 2026-09-30)");
        }
    }

    /** A view opened as the person (the MR cube's options refused in words when it does not run here). */
    VarPivotLens open(Viewer v, String date, Map<String, String> options) {
        if (options != null && !options.isEmpty() && !mr()) {
            throw PivotView.bad("the VaR source's options (" + String.join(", ", options.keySet()) + ") are the MR cube's (engine mr-cube), which is "
                    + "not running on this server");
        }
        return VarPivotLens.open(svc, principal(v), day(date), options, props, frtb);
    }

    /** The dimensions a person may pivot the view by, in the field list's order, with their types. */
    Map<String, String> dims(VarPivotLens o) {
        Map<String, String> out = new LinkedHashMap<>();
        VarBook book = o.book;
        if (mr()) {
            for (String l : VarPivotCodes.levels(book)) {
                out.put(l, "hierarchy");
            }
        }
        Columns pc = book.positions();
        if (!o.base.view().seesPositions(o.base.set())) {
            return out;                                                   // aggregate only: a rule over positions would probe them
        }
        if (mr()) {
            out.put(VarPivotCodes.RISK_CLASS, "string");
        }
        for (String c : pc == null ? List.<String>of() : pc.textColumns()) {
            if (!svc.access().masked(o.p, c)) {
                out.put(c, "string");
            }
        }
        if (mr()) {
            for (String c : book.trades().textColumns()) {
                if ((pc == null || !pc.has(c)) && !svc.access().masked(o.p, c) && !out.containsKey(c)) {
                    out.put(c, "string");
                }
            }
            out.put(VarPivotCodes.POSITION, "string");
            out.put(VarPivotCodes.TRADE, "string");
        }
        return out;
    }

    @Override
    public Catalogue catalogue(Viewer v, String source, String date) {
        VarPivotLens o = open(v, date, Map.of());
        List<Field> fields = new ArrayList<>();
        dims(o).forEach((c, type) -> fields.add(new Field(c, type, label(c), note(c, type))));
        List<String> dates = new ArrayList<>(new java.util.TreeSet<>(svc.openDates())).stream().map(LocalDate::toString).toList();
        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("route", "the VaR engine's book of the date: every cell's figures are the engine's answer for the cell's scope");
        notes.put("scenarios", o.book.width() + " scenarios, set " + o.book.scenarioSet());
        notes.put("hidden", o.base.hidden() == 0 ? "none" : o.base.hidden() + " trade(s) not visible to you are left out of every cell");
        if (!o.base.view().seesPositions(o.base.set())) {
            notes.put("aggregate", mr() ? "your role sees the bank in aggregate only: pivot it by hierarchy levels"
                    : "your role sees the bank in aggregate only: no dimension is offered (hierarchy levels come with the MR cube)");
        }
        if (mr()) {
            notes.put("lens", "the MR cube: options scope, horizon, method, confidence, esConfidence, set and ccy (BI_GUIDE.md section P29.4)");
            notes.put("defaultView", defaultView(o));
        }
        notes.put("folders", FOLDERS);
        notes.put("cellLink", java.util.Map.of("label", "Ask the VaR engine", "href", "/var?scope="));   // the cell popover's link (drishti_pivot.js)
        notes.put("defaultValues", java.util.List.of(java.util.Map.of("measure", "var()", "as", "VaR"), java.util.Map.of("measure", "es()", "as", "ES")));
        notes.put("measureGroup", "VaR engine");                         // the field pickers' group of the measures, and each one's note
        notes.put("perCellNote", "Computed per cell by the VaR engine from the cell's own trades: a total is the VaR of its trades, never a sum"
                + " of the cells (\u03a3 \u2212 total = the diversification)");
        notes.put("perCellSumNote", "Computed per cell by the VaR engine from the cell's own trades; it adds up: a total equals the sum of its parts");
        notes.put("addsUp", ADDS_UP);
        notes.put("hierarchies", hierarchies(o, fields));
        notes.put("hidden", fields.stream().map(Field::name).filter(n -> n.endsWith("." + com.ash.drishti.banking.compute.var.HierarchySpec.ROOT_LEVEL
                .toUpperCase(Locale.ROOT))).toList());          // a hierarchy's root level: the grand total, under More fields
        return new Catalogue(SOURCE, mr() ? "VaR · MR cube" : "VaR", fields, natives(), o.date().toString(), dates, version(v, source, date), 0, notes);
    }

    /** The measures that add up (a total is the sum of its parts): their per-cell note says so. */
    static final List<String> ADDS_UP = List.of("pv", "trades", "comp_var", "comp_es", "dv01", "cs01");

    /** The measures' display folders in the field pickers and panes (BI_GUIDE.md section P29.2). */
    static final Map<String, String> FOLDERS = Map.ofEntries(Map.entry("var", "VaR"), Map.entry("es", "VaR"), Map.entry("pvar", "VaR"),
            Map.entry("svar", "VaR"), Map.entry("comp_var", "Contribution"), Map.entry("comp_es", "Contribution"), Map.entry("incr_var", "Contribution"),
            Map.entry("limit", "Limits"), Map.entry("limit_use", "Limits"), Map.entry("dv01", "Sensitivities"), Map.entry("cs01", "Sensitivities"),
            Map.entry("sbm", "Sensitivities"), Map.entry("pv", "Book"), Map.entry("trades", "Book"));

    /**
     * The source's hierarchies as one item each (the field pickers draw them so): every configured hierarchy the book has (MGMT, LEGAL, LL
     * and any other the VaR configuration names), its levels top first as pivot fields (MGMT.DESK) and the promoted columns its levels are
     * built from (DIVISION, DESK, TT_BOOK: hidden from the plain field list, the hierarchy says them once); then the members' drill,
     * Positions › Trade, when the person sees positions.
     */
    List<Map<String, Object>> hierarchies(VarPivotLens o, List<Field> fields) {
        Set<String> offered = new java.util.HashSet<>();
        fields.forEach(f -> offered.add(f.name()));
        Map<String, com.ash.drishti.banking.compute.var.HierarchySpec> specs = new LinkedHashMap<>();
        try {
            svc.hierarchies().specs().forEach(s -> specs.put(s.id(), s));
        } catch (RuntimeException e) {
            // no configuration readable: the hierarchies are named by their ids, nothing is hidden
        }
        Map<String, List<Map<String, Object>>> levels = new LinkedHashMap<>();
        for (String l : VarPivotCodes.levels(o.book)) {
            if (!offered.contains(l)) {
                continue;
            }
            String h = l.substring(0, l.indexOf('.'));
            if (l.substring(l.indexOf('.') + 1).equalsIgnoreCase(com.ash.drishti.banking.compute.var.HierarchySpec.ROOT_LEVEL)) {
                continue;                                                  // the root (the bank): every view's total already, no level to pick
            }
            String word = l.substring(l.indexOf('.') + 1).toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
            levels.computeIfAbsent(h, k -> new ArrayList<>()).add(Map.of("field", l, "label", Character.toUpperCase(word.charAt(0)) + word.substring(1)));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        levels.forEach((h, ls) -> {
            var spec = specs.get(h);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h);
            m.put("title", spec == null ? h : spec.title());
            m.put("levels", ls);
            m.put("columns", spec == null ? List.of() : spec.levels().stream().filter(offered::contains).toList());
            out.add(m);
        });
        if (offered.contains(VarPivotCodes.POSITION) && offered.contains(VarPivotCodes.TRADE)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "POSITIONS");
            m.put("title", "Positions");
            m.put("levels", List.of(Map.of("field", VarPivotCodes.POSITION, "label", "Position"), Map.of("field", VarPivotCodes.TRADE, "label", "Trade")));
            m.put("columns", List.of());
            out.add(m);
        }
        return out;
    }

    private static String label(String c) {
        if (VarPivotCodes.isLevel(c)) {
            return c.substring(0, c.indexOf('.')) + " › " + c.substring(c.indexOf('.') + 1).toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        return c.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String note(String c, String type) {
        if ("hierarchy".equals(type)) {
            return "the nodes of a hierarchy level: each node's own trades (its scope NODE:<id>)";
        }
        return switch (c) {
            case VarPivotCodes.RISK_CLASS -> "a position's primary risk class, from its product group (drishti.cube.mr.risk-classes)";
            case VarPivotCodes.POSITION -> "each position (POS:<id>)";
            case VarPivotCodes.TRADE -> "each trade (TRD:<id>)";
            default -> "a column of the VaR engine's positions or trades";
        };
    }

    /**
     * The MR cube's landing view (layout A, docs/architecture/LENSES.md section 6.7): rows the management hierarchy's desks (then books, positions and
     * trades as each node is opened), columns the risk class, the VaR; the bank as the scope.
     */
    Map<String, Object> defaultView(VarPivotLens o) {
        List<String> levels = VarPivotCodes.levels(o.book);
        List<String> rows = new ArrayList<>();
        String desk = levels.stream().filter(l -> l.endsWith(".DESK") && l.startsWith("MGMT.")).findFirst()
                .orElse(levels.stream().filter(l -> l.endsWith(".DESK")).findFirst().orElse(levels.size() > 1 ? levels.get(1) : null));
        if (desk != null) {
            rows.add(desk);
            int at = levels.indexOf(desk);
            String h = desk.substring(0, desk.indexOf('.') + 1);
            for (int i = at + 1; i < levels.size(); i++) {
                if (levels.get(i).startsWith(h)) {
                    rows.add(levels.get(i));
                }
            }
        }
        if (o.base.view().seesPositions(o.base.set())) {
            rows.add(VarPivotCodes.POSITION);
            rows.add(VarPivotCodes.TRADE);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", SOURCE);
        m.put("rows", rows);
        m.put("columns", List.of(VarPivotCodes.RISK_CLASS));
        m.put("values", List.of(Map.of("measure", "var()", "as", "VaR")));
        m.put("options", Map.of("scope", "BANK"));
        m.put("expandTo", Map.of("rows", 1, "columns", 1));
        return m;
    }

    @Override
    public String version(Viewer v, String source, String date) {
        VarService.Scoped base = svc.scoped(principal(v), "BANK", day(date));
        VarDate d = base.date();
        return d.date() + "|" + d.currency() + "|" + d.version() + "|i" + d.intradayStamp() + "|" + base.view().signature() + (mr() ? "|mr" : "");
    }

    @Override
    public Estimate estimate(Viewer v, AggregationPlan plan) {
        VarPivotLens o = open(v, plan.date(), plan.options());
        long trades = o.base.set().size();
        return new Estimate(trades, trades * o.book.width() * 8L, plan.dims().size(), dims(o).size(),
                Math.max(0.01, trades * o.book.width() / 2e9), "the VaR engine's trades of the date and their scenario vectors (in memory)");
    }

    // ---- evaluation ----------------------------------------------------------------------------------------------------------------

    /** One cell being evaluated: its set and key, its members, its scope text. */
    private record Cell(int set, CellKey key, OrdinalSet members, String scope, Map<String, String> coords) {}

    /** The trades of the view after its filters (the members every set is cut from). */
    private OrdinalSet filtered(VarPivotLens o, AggregationPlan plan) {
        OrdinalSet members = o.base.set();
        if (plan.filters().isEmpty()) {
            return members;
        }
        List<VarPivotCodes.Codes> cs = new ArrayList<>();
        List<boolean[]> keep = new ArrayList<>();
        for (Map.Entry<String, AggregationPlan.Filter> f : plan.filters().entrySet()) {
            VarPivotCodes.Codes c = codes.codes(o.book, o.bookKey(), f.getKey());
            boolean[] k = new boolean[c.values().size() + 1];                 // index 0: the blank member
            for (int i = -1; i < c.values().size(); i++) {
                String val = i < 0 ? CellKey.BLANK : c.values().get(i);
                k[i + 1] = (f.getValue().in().isEmpty() || f.getValue().in().contains(val)) && !f.getValue().notIn().contains(val);
            }
            cs.add(c);
            keep.add(k);
        }
        VarPivotCodes.Runs r = new VarPivotCodes.Runs();
        members.forEach(x -> {
            for (int i = 0; i < cs.size(); i++) {
                int c = x < cs.get(i).code().length ? cs.get(i).code()[x] : -1;
                if (!keep.get(i)[c + 1]) {
                    return;
                }
            }
            r.add(x);
        });
        OrdinalSet out = r.build();
        return out.equals(members) ? members : out;
    }

    /** The cells of one set: the filtered trades restricted by the set's {@code at} and exclusion, grouped by its dimensions. */
    private Map<List<Integer>, VarPivotCodes.Runs> group(VarPivotLens o, CellSet set, OrdinalSet kept) {
        List<VarPivotCodes.Codes> g = new ArrayList<>();
        for (String d : set.grouping()) {
            g.add(codes.codes(o.book, o.bookKey(), d));
        }
        List<VarPivotCodes.Codes> atC = new ArrayList<>();
        List<Integer> atV = new ArrayList<>();
        for (Map.Entry<String, String> at : set.at().entrySet()) {
            VarPivotCodes.Codes c = codes.codes(o.book, o.bookKey(), at.getKey());
            int code = c.codeOf(at.getValue());
            if (code == -2) {
                return Map.of();                                              // no trade has the value: the set is empty
            }
            atC.add(c);
            atV.add(code);
        }
        List<VarPivotCodes.Codes> exC = new ArrayList<>();
        List<Set<Integer>> exV = new ArrayList<>();
        set.exclude().forEach((d, vals) -> {
            VarPivotCodes.Codes c = codes.codes(o.book, o.bookKey(), d);
            Set<Integer> s = new java.util.HashSet<>();
            vals.forEach(v -> s.add(c.codeOf(v)));
            exC.add(c);
            exV.add(s);
        });
        Map<List<Integer>, VarPivotCodes.Runs> out = new HashMap<>();
        kept.forEach(x -> {
            for (int i = 0; i < atC.size(); i++) {
                if (code(atC.get(i), x) != atV.get(i)) {
                    return;
                }
            }
            for (int i = 0; i < exC.size(); i++) {
                if (exV.get(i).contains(code(exC.get(i), x))) {
                    return;
                }
            }
            List<Integer> key = new ArrayList<>(g.size());
            for (VarPivotCodes.Codes c : g) {
                key.add(code(c, x));
            }
            out.computeIfAbsent(key, k -> new VarPivotCodes.Runs()).add(x);
        });
        return out;
    }

    private static int code(VarPivotCodes.Codes c, int x) {
        return x < c.code().length ? c.code()[x] : -1;
    }

    /** The scope text of a cell: the view's scope, its filters, the set's exclusion and the cell's coordinates; null when one cannot be named. */
    String scope(VarPivotLens o, AggregationPlan plan, CellSet set, Map<String, String> coords) {
        List<String> and = new ArrayList<>();
        List<String> minus = new ArrayList<>();
        if (!o.scope.equalsIgnoreCase("BANK")) {
            and.add(o.scope);
        }
        for (Map.Entry<String, AggregationPlan.Filter> f : plan.filters().entrySet()) {
            VarPivotCodes.Codes c = codes.codes(o.book, o.bookKey(), f.getKey());
            if (!f.getValue().in().isEmpty()) {
                String t = codes.anyOf(o.book, c, f.getKey(), f.getValue().in());
                if (t == null) {
                    return null;
                }
                and.add(t);
            }
            if (!f.getValue().notIn().isEmpty()) {
                String t = codes.anyOf(o.book, c, f.getKey(), f.getValue().notIn());
                if (t == null) {
                    return null;
                }
                minus.add(t);
            }
        }
        for (Map.Entry<String, List<String>> ex : set.exclude().entrySet()) {
            String t = codes.anyOf(o.book, codes.codes(o.book, o.bookKey(), ex.getKey()), ex.getKey(), ex.getValue());
            if (t == null) {
                return null;
            }
            minus.add(t);
        }
        Map<String, String> all = new TreeMap<>(set.at());
        all.putAll(coords);
        for (Map.Entry<String, String> c : all.entrySet()) {
            if (set.marks().containsKey(c.getKey())) {
                continue;
            }
            String t = codes.term(o.book, codes.codes(o.book, o.bookKey(), c.getKey()), c.getKey(), c.getValue());
            if (t == null) {
                return null;
            }
            and.add(t);
        }
        return VarPivotCodes.compose(and, minus);
    }

    /** Refuses a dimension or filter the person may not use, in words. */
    private void check(VarPivotLens o, AggregationPlan plan) {
        Map<String, String> allowed = dims(o);
        Set<String> named = new LinkedHashSet<>(plan.dims().keySet());
        named.addAll(plan.filters().keySet());
        for (String d : named) {
            if (allowed.containsKey(d)) {
                continue;
            }
            if (!o.base.view().seesPositions(o.base.set())) {
                throw new DrishtiException(ErrorCode.FORBIDDEN, "your role sees " + o.base.res().canonical() + " in aggregate only: pivot it by "
                        + "hierarchy levels, not by " + d + ", which would name positions or trades");
            }
            if (svc.access().masked(o.p, d)) {
                throw new DrishtiException(ErrorCode.PIVOT_MASKED, d + " is masked for your role: the VaR source does not pivot by it");
            }
            throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, d + " is not a dimension of the VaR source on " + o.date() + "; its dimensions: "
                    + String.join(", ", allowed.keySet()));
        }
    }

    @Override
    public Partials evaluate(Viewer v, AggregationPlan plan) {
        long t0 = System.nanoTime();
        VarPivotLens o = open(v, plan.date(), plan.options());
        check(o, plan);
        OrdinalSet members = o.base.set();
        OrdinalSet kept = filtered(o, plan);
        List<Cell> cells = new ArrayList<>();
        for (int si = 0; si < plan.sets().size(); si++) {
            CellSet set = plan.sets().get(si);
            Map<List<Integer>, VarPivotCodes.Runs> groups = group(o, set, kept);
            for (Map.Entry<List<Integer>, VarPivotCodes.Runs> e : groups.entrySet()) {
                Map<String, String> coords = new TreeMap<>();
                for (int i = 0; i < set.grouping().size(); i++) {
                    String d = set.grouping().get(i);
                    int c = e.getKey().get(i);
                    coords.put(d, c < 0 ? CellKey.BLANK : codes.codes(o.book, o.bookKey(), d).values().get(c));
                }
                OrdinalSet s = e.getValue().build();
                cells.add(new Cell(si, set.key(coords), s, scope(o, plan, set, coords), coords));
            }
        }
        Set<String> fns = new LinkedHashSet<>();
        plan.functions().forEach(f -> fns.add(f.fn().name()));
        boolean vectors = fns.stream().anyMatch(VECTOR::contains);
        boolean total = fns.contains("comp_var") || fns.contains("comp_es") || fns.contains("incr_var");
        List<double[]> got = List.of();
        double[] totalOneDay = null;
        int asked = 0;
        if (vectors) {
            List<OrdinalSet> ask = new ArrayList<>();
            for (Cell c : cells) {
                if (!c.members().equals(members)) {
                    ask.add(c.members());
                }
            }
            if (total && !kept.equals(members)) {
                ask.add(kept);
            }
            asked = ask.size();
            List<double[]> sums = ask.isEmpty() ? List.of() : o.book.vectors(ask, svc.sums());   // one batch: one call per remote shard
            List<double[]> mapped = new ArrayList<>(cells.size());
            int k = 0;
            for (Cell c : cells) {
                mapped.add(c.members().equals(members) ? o.base.oneDay() : sums.get(k++));
            }
            got = mapped;
            if (total) {
                totalOneDay = kept.equals(members) ? o.base.oneDay() : sums.get(k);
            }
        }
        double[] totalLensed = totalOneDay == null ? null : o.lensed(totalOneDay);
        VarCalc.Measures totalM = totalOneDay == null ? null : o.measuresOf(totalOneDay, totalLensed);
        List<TreeMap<CellKey, Partials.Entry>> bySet = new ArrayList<>();
        plan.sets().forEach(s -> bySet.add(new TreeMap<>()));
        for (int ci = 0; ci < cells.size(); ci++) {
            Cell c = cells.get(ci);
            double[] oneDay = vectors ? got.get(ci) : null;
            double[] lensed = oneDay == null ? null : o.lensed(oneDay);
            VarCalc.Measures m = oneDay == null ? null : o.measuresOf(oneDay, lensed);
            Object[] states = new Object[plan.functions().size()];
            Map<Integer, String> notes = new LinkedHashMap<>();
            for (int f = 0; f < states.length; f++) {
                Object x = figure(plan.functions().get(f).fn().name(), o, c, lensed, m, totalLensed, totalM);
                if (x instanceof String why) {
                    states[f] = new Final(null);
                    notes.put(f, why);
                } else {
                    states[f] = new Final((Double) x);
                }
            }
            bySet.get(c.set()).put(c.key(), new Partials.Entry(c.members().size(), states, c.scope(), notes));
        }
        List<Partials.SetResult> out = new ArrayList<>();
        for (int si = 0; si < plan.sets().size(); si++) {
            out.add(new Partials.SetResult(plan.sets().get(si), bySet.get(si)));
        }
        List<String> stmts = new ArrayList<>();
        stmts.add(cells.size() + " cell(s) of " + plan.sets().size() + " set(s) from the trades' dimension codes; " + (vectors ? asked
                + " vector sum(s) in one batch (VarBook.vectors" + (o.book.sharded() ? ": one call per remote shard" : "") + ")" : "no vector read"));
        cells.stream().map(Cell::scope).filter(s -> s != null).limit(10).forEach(stmts::add);
        return new Partials(out, new Partials.Stats(1, stmts, (System.nanoTime() - t0) / 1_000_000, kept.size()));
    }

    /** A function's figure for a cell (a Double, null when it has none) or the reason it has none (a String). */
    private Object figure(String fn, VarPivotLens o, Cell c, double[] v, VarCalc.Measures m, double[] totalLensed, VarCalc.Measures totalM) {
        return switch (fn) {
            case "var" -> m.var();
            case "es" -> m.es();
            case "pvar" -> parametric.enabled() ? (Object) com.ash.drishti.banking.compute.var.ParametricVar.of(v, o.calc.confidence(), o.calc.esConfidence(),
                    parametric.settings()).var() : "parametric VaR is switched off on this server (drishti.var.parametric.enabled)";
            case "trades" -> (double) c.members().size();
            case "pv" -> {
                double x = o.pv(c.members());
                yield Double.isNaN(x) ? "the date's trades have no PV column" : (Object) x;
            }
            case "comp_var" -> VarMath.component(v, totalM.tail(), totalM.scale());
            case "comp_es" -> VarMath.componentEs(v, totalM.order(), o.calc.esConfidence());
            case "incr_var" -> totalM.var() - o.calc.var(VarMath.minus(totalLensed, v));
            case "limit", "limit_use" -> {
                VarBook.Node n = o.limitNode(c.coords(), c.members(), codes);
                if (n == null) {
                    yield "no limit: the cell is not a node with a VaR limit, whole as you see it";
                }
                if (fn.equals("limit")) {
                    yield n.limit();
                }
                yield o.limitOnBasis(n) ? (Object) (m.var() / n.limit()) : "the limit is set for another horizon or confidence than the view's";
            }
            case "svar" -> o.stressed(c.scope());
            case "dv01", "cs01" -> o.sensitivity(fn, c.members());
            case "sbm" -> o.sbm(c.scope());
            default -> "not a measure of the VaR source";
        };
    }

    @Override
    public Rows drill(Viewer v, AggregationPlan plan, CellKey key, int offset, int limit) {
        VarPivotLens o = open(v, plan.date(), plan.options());
        if (!o.base.view().seesPositions(o.base.set())) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "your role sees " + o.base.res().canonical() + " in aggregate only: a cell's trades are "
                    + "not listed");
        }
        CellSet set = CellSet.of(List.copyOf(key.coords().keySet()), key.coords());
        AggregationPlan p = plan.with(List.of(set), plan.functions());
        check(o, new AggregationPlan(p.source(), p.date(), p.asOf(), p.filters(), dimsOf(key), p.functions(), p.sets(), null, 0, p.money(),
                p.options()));
        OrdinalSet cell = OrdinalSet.empty();
        for (VarPivotCodes.Runs r : group(o, set, filtered(o, plan)).values()) {
            cell = cell.or(r.build());
        }
        List<String> cols = new ArrayList<>(List.of("TRADE_ID", "POSITION_ID"));
        Columns tc = o.book.trades();
        for (String c : List.of("DESK", "TT_BOOK", "PRODUCT_GROUP", "CURRENCY", "COUNTERPARTY")) {
            if (tc.has(c) && !svc.access().masked(o.p, c)) {
                cols.add(c);
            }
        }
        boolean pv = tc.has("PV");
        if (pv) {
            cols.add("PV");
        }
        List<List<Object>> rows = new ArrayList<>();
        int[] at = {0};
        OrdinalSet members = cell;
        members.forEach(x -> {
            if (at[0]++ < offset || rows.size() >= limit) {
                return;
            }
            List<Object> r = new ArrayList<>();
            r.add(o.book.tradeId(x));
            int pi = o.book.positionOf(x);
            r.add(pi < 0 ? null : o.book.positionId(pi));
            for (int i = 2; i < cols.size() - (pv ? 1 : 0); i++) {
                r.add(tc.text(cols.get(i), x));
            }
            if (pv) {
                double d = tc.number("PV", x);
                r.add(Double.isFinite(d) ? d : null);
            }
            rows.add(r);
        });
        return new Rows(cols, rows, members.size(), "the cell's trades as the VaR engine holds them; open the cell's scope with VAR <scope>");
    }

    private static Map<String, com.ash.drishti.kernel.api.pivot.Dim> dimsOf(CellKey key) {
        Map<String, com.ash.drishti.kernel.api.pivot.Dim> m = new LinkedHashMap<>();
        key.coords().keySet().forEach(d -> m.put(d, new com.ash.drishti.kernel.api.pivot.Dim(d, null)));
        return m;
    }

    @Override
    public Optional<String> scopeOf(Viewer v, AggregationPlan plan, CellKey key) {
        VarPivotLens o = open(v, plan.date(), plan.options());
        return Optional.ofNullable(scope(o, plan, CellSet.of(List.copyOf(key.coords().keySet()), Map.of()), key.coords()));
    }

    /** The members and the summed 1-day vector of one cell of a view (the MR lens's detail and history). */
    record CellVector(VarPivotLens lens, OrdinalSet members, double[] oneDay, String scope) {}

    /** One cell by its coordinates, its vector summed (the right rail's tail and histogram). */
    CellVector cell(Viewer v, AggregationPlan plan, Map<String, String> coords) {
        VarPivotLens o = open(v, plan.date(), plan.options());
        CellSet set = CellSet.of(List.copyOf(coords.keySet()), coords);
        check(o, new AggregationPlan(plan.source(), plan.date(), plan.asOf(), plan.filters(), dimsOf(CellKey.of(coords)), plan.functions(),
                List.of(set), null, 0, plan.money(), plan.options()));
        OrdinalSet cell = OrdinalSet.empty();
        for (VarPivotCodes.Runs r : group(o, set, filtered(o, plan)).values()) {
            cell = cell.or(r.build());
        }
        double[] oneDay = cell.equals(o.base.set()) ? o.base.oneDay() : o.book.vector(cell, svc.sums());
        return new CellVector(o, cell, oneDay, scope(o, plan, set, coords));
    }
}
