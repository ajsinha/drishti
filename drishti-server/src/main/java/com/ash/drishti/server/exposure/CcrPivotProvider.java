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
package com.ash.drishti.server.exposure;

import com.ash.drishti.bicompute.core.AggregationPlan;
import com.ash.drishti.bicompute.core.CellKey;
import com.ash.drishti.bicompute.core.CellSet;
import com.ash.drishti.bicompute.core.Partials;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.functions.Final;
import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.Kind;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureCalc;
import com.ash.drishti.compute.ccr.ExposureGrid;
import com.ash.drishti.compute.ccr.ExposureMath;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * The CCR cube as a source of the pivot engine (EXPOSURE_GUIDE.md section 30; COUNTERPARTY_ENGINES.md section 6; PIVOT_ENGINE.md section
 * 6.1), source {@code ccr}: the exposure engine's netting sets of the business date as the person sees them, by {@code GROUP},
 * {@code COUNTERPARTY}, {@code LEGAL_ENTITY} and {@code NETTING_SET}, and by the limit board's {@code TENOR_BUCKET} (the primary dates of
 * each bucket). The pivot engine never adds these figures up: every cell is its own netting sets' paths summed by the exposure engine
 * ({@link ExposureService#scoped}, the same code as {@code EXP}), the statistics last, so a counterparty's or the bank's peak PFE is the
 * quantile of its summed paths and never the sum of its members' peaks (COUNTERPARTY_ENGINES.md section 6.2, case X2).
 *
 * <p>Measures: {@code pfe()} the peak PFE at the view's confidence (inside the cell's bucket when it has one), {@code ee()} the peak EE,
 * {@code epe()}, {@code eepe()}, {@code ce()}, {@code limit()} the counterparty's stored credit limit, {@code limit_use()} and
 * {@code headroom()} against it (PFE at the limit's confidence), {@code nettingSets()}; {@code ead()} the SA-CCR engine's EAD and
 * {@code fva()} the FVA engine's (additive: the sum of the cell's sets), offered only while their engines run. The view's options:
 * {@code q} (the PFE confidence) and {@code collateral} ({@code lagged}, {@code current}, {@code none}).
 */
public final class CcrPivotProvider implements PivotProvider {

    /** The source id. */
    public static final String SOURCE = "ccr";
    static final String BUCKET = "TENOR_BUCKET";
    static final List<String> DIMS = List.of("GROUP", "COUNTERPARTY", "LEGAL_ENTITY", "NETTING_SET", BUCKET);
    static final List<String> OPTIONS = List.of("q", "collateral");
    /** The measures that are a whole-life figure: empty in a cell of one tenor bucket, said so in the cell's note. */
    static final List<String> WHOLE_LIFE = List.of("epe", "eepe", "ce", "ead", "fva");

    private static final Native PFE = new Native("pfe", "Peak PFE", Kind.HOLISTIC, "money",
            "the cell's peak PFE at the view's confidence: the quantile of its netting sets' summed paths (inside the bucket's dates)");
    private static final Native EE = new Native("ee", "Peak EE", Kind.HOLISTIC, "money", "the cell's peak expected exposure");
    private static final Native EPE = new Native("epe", "EPE", Kind.HOLISTIC, "money", "the cell's expected positive exposure over the first year");
    private static final Native EEPE = new Native("eepe", "EEPE", Kind.HOLISTIC, "money", "the cell's effective EPE (Basel CRE53)");
    private static final Native CE = new Native("ce", "CE", Kind.HOLISTIC, "money", "the cell's current exposure after collateral");
    private static final Native LIMIT = new Native("limit", "Limit", Kind.HOLISTIC, "money",
            "the counterparty's stored credit limit (credit-limit); a cell of several counterparties has none");
    private static final Native USE = new Native("limit_use", "Use", Kind.HOLISTIC, "pct",
            "peak PFE at the limit's confidence over the counterparty's limit (in the bucket when the cell has one)");
    private static final Native HEADROOM = new Native("headroom", "Headroom", Kind.HOLISTIC, "money",
            "the limit less the peak PFE at the limit's confidence (negative: over the limit)");
    private static final Native SETS = new Native("nettingSets", "Netting sets", Kind.HOLISTIC, "count", "the netting sets in the cell");
    private static final Native EAD = new Native("ead", "EAD SA-CCR", Kind.HOLISTIC, "money", "the SA-CCR engine's EAD: the sum of the cell's netting sets'");
    private static final Native FVA = new Native("fva", "FVA", Kind.HOLISTIC, "money", "the FVA engine's FVA: the sum of the cell's netting sets'");

    private final ExposureService svc;
    private final CcrCubeProperties props;
    private final List<CcrCubeProperties.Bucket> buckets;
    /** Each netting set's SA-CCR EAD on the date as the person sees it (empty when SA-CCR does not run here). */
    private final PerSet ead;
    /** Each netting set's FVA on the date as the person sees it (empty when FVA does not run here). */
    private final PerSet fva;
    private volatile BooleanSupplier running = () -> true;

    /** An additive engine's figure for each netting set of a date, as one person sees them; null when the engine does not run here. */
    @FunctionalInterface
    interface PerSet {
        Map<String, Double> of(Principal p, LocalDate date);
    }

    CcrPivotProvider(ExposureService svc, CcrCubeProperties props, PerSet ead, PerSet fva) {
        this.svc = svc;
        this.props = props;
        this.buckets = props.parsed();
        this.ead = ead == null ? (p, d) -> null : ead;
        this.fva = fva == null ? (p, d) -> null : fva;
    }

    /** The engine registry's word on whether the CCR cube runs here (COMPUTE_ENGINES.md). */
    void running(BooleanSupplier on) {
        this.running = on;
    }

    boolean on() {
        return running.getAsBoolean() && svc.running();
    }

    CcrCubeProperties props() {
        return props;
    }

    List<CcrCubeProperties.Bucket> buckets() {
        return buckets;
    }

    ExposureService exposure() {
        return svc;
    }

    /** Off: nothing of its own is held (the exposure engine's answers stay the exposure engine's). */
    void release() {
        // the cells are the exposure engine's cached answers; the pivot engine's states are released by the pivot engine
    }

    @Override
    public String id() {
        return SOURCE;
    }

    @Override
    public boolean serves(String source) {
        return SOURCE.equals(source) && on();
    }

    @Override
    public Map<String, String> sources(Viewer v) {
        return on() ? Map.of(SOURCE, "CCR cube (the exposure engine's netting sets)") : Map.of();
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(false, false, false, true);
    }

    private List<Native> natives(Principal p, LocalDate d) {
        List<Native> n = new ArrayList<>(List.of(PFE, EE, EPE, EEPE, CE, LIMIT, USE, HEADROOM, SETS));
        if (p == null || ead.of(p, d) != null) {
            n.add(EAD);
        }
        if (p == null || fva.of(p, d) != null) {
            n.add(FVA);
        }
        return n;
    }

    @Override
    public Optional<AggFunction<Object>> nativeFunction(String name, List<String> args) {
        for (Native n : List.of(PFE, EE, EPE, EEPE, CE, LIMIT, USE, HEADROOM, SETS, EAD, FVA)) {
            if (n.name().equals(name)) {
                return Optional.of(Final.function(name, true));
            }
        }
        return Optional.empty();
    }

    static Principal principal(Viewer v) {
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

    /** The view's options as the exposure engine's ask: an unknown name refused in words. */
    ExposureService.Ask ask(Map<String, String> options) {
        Map<String, String> o = options == null ? Map.of() : options;
        for (String k : o.keySet()) {
            if (!OPTIONS.contains(k)) {
                throw PivotView.bad("'" + k + "' is not an option of the CCR cube; its options: q (the PFE confidence, 0.95), collateral (lagged, "
                        + "current, none)");
            }
        }
        return ExposureService.Ask.of(svc.props(), o.get("q"), o.get("collateral"), null, false);
    }

    /** One netting set the person sees, with its coordinates. */
    record Member(String set, Map<String, String> coords) {}

    /** The date opened for the person: its members, and whether the person sees every netting set one by one. */
    record Opened(ExposureDate date, ExposureAccess.View view, List<Member> members, boolean shown) {}

    Opened open(Principal p, String date) {
        ExposureDate d = svc.date(day(date));
        ExposureAccess.View view = svc.access().view(p, d);
        ExposureBook b = d.book();
        Map<String, String> groupOf = new HashMap<>();
        d.groups().forEach((g, ms) -> ms.forEach(c -> groupOf.putIfAbsent(c, g)));
        List<Member> ms = new ArrayList<>();
        for (int s = 0; s < b.sets().size(); s++) {
            if (!view.seesSet(s)) {
                continue;
            }
            ExposureBook.SetInfo si = b.set(s);
            Map<String, String> c = new TreeMap<>();
            c.put("NETTING_SET", si.id());
            c.put("COUNTERPARTY", blank(si.counterparty()));
            c.put("LEGAL_ENTITY", blank(si.legalEntity()));
            c.put("GROUP", blank(groupOf.get(si.counterparty())));
            ms.add(new Member(si.id(), c));
        }
        return new Opened(d, view, ms, view.full());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? CellKey.BLANK : s;
    }

    @Override
    public Catalogue catalogue(Viewer v, String source, String date) {
        Principal p = principal(v);
        Opened o = open(p, date);
        List<Field> fields = new ArrayList<>();
        if (o.shown()) {
            for (String dname : DIMS) {
                String label = dname.equals(BUCKET) ? "tenor bucket" : dname.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
                String note = dname.equals(BUCKET) ? "the limit board's buckets: " + String.join(", ", props.buckets().keySet())
                        : "the netting sets' " + label;
                fields.add(new Field(dname, "string", label, note));
            }
        }
        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("route", "the exposure engine's paths: each cell's netting sets summed path by path, the statistics last (never a sum of peaks)");
        notes.put("run", o.date().book().runId());
        notes.put("defaultView", defaultView());
        if (!o.shown()) {
            notes.put("aggregate", "your role sees some netting sets only in aggregate: only the bank's figures are offered");
        }
        String d = o.date().date().toString();
        return new Catalogue(SOURCE, "CCR cube", fields, natives(p, o.date().date()), d, List.of(d), version(v, source, date), 0, notes);
    }

    /** Landing A (CCR_CUBE_LENS.md section 6.7): the limit board, counterparties by tenor bucket, peak PFE and its use; groups above them. */
    Map<String, Object> defaultView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", SOURCE);
        m.put("rows", List.of("GROUP", "COUNTERPARTY", "NETTING_SET"));
        m.put("columns", List.of(BUCKET));
        m.put("values", List.of(Map.of("measure", "pfe()", "as", "Peak PFE"), Map.of("measure", "limit_use()", "as", "Use")));
        m.put("options", Map.of("q", String.valueOf(svc.props().limitConfidence()), "collateral", svc.props().collateralModel()));
        m.put("expandTo", Map.of("rows", 2, "columns", 1));
        return m;
    }

    @Override
    public String version(Viewer v, String source, String date) {
        Principal p = principal(v);
        Opened o = open(p, date);
        LocalDate d = o.date().date();
        return d + "|" + o.date().version() + "|" + o.view().signature() + "|" + (ead.of(p, d) != null) + "|" + (fva.of(p, d) != null);
    }

    @Override
    public Estimate estimate(Viewer v, AggregationPlan plan) {
        Opened o = open(principal(v), plan.date());
        return new Estimate(o.members().size(), -1, plan.dims().size(), DIMS.size(), 0.05 * plan.sets().size(),
                "the exposure engine's netting sets of the date (in memory): each cell's paths summed once");
    }

    @Override
    public Partials evaluate(Viewer v, AggregationPlan plan) {
        long t0 = System.nanoTime();
        Principal p = principal(v);
        ExposureService.Ask ask = ask(plan.options());
        Opened o = open(p, plan.date());
        for (String d : plan.dims().keySet()) {
            if (!DIMS.contains(d) || !o.shown()) {
                throw new DrishtiException(ErrorCode.PIVOT_MASKED, d + " is not a dimension of the CCR cube you may use (" + String.join(", ", DIMS) + ")");
            }
        }
        LocalDate day = o.date().date();
        Map<String, Double> eadBySet = ead.of(p, day);
        Map<String, Double> fvaBySet = fva.of(p, day);
        Map<String, Cell> computed = new HashMap<>();
        int cells = 0;
        List<Partials.SetResult> out = new ArrayList<>();
        for (CellSet set : plan.sets()) {
            Map<Map<String, String>, Group> groups = new LinkedHashMap<>();
            for (Member m : o.members()) {
                for (CcrCubeProperties.Bucket bk : buckets) {
                    Map<String, String> c = new TreeMap<>(m.coords());
                    c.put(BUCKET, bk.label());
                    if (!keep(plan, set, c)) {
                        continue;
                    }
                    Map<String, String> t = new TreeMap<>();
                    for (String d : set.grouping()) {
                        t.put(d, c.get(d));
                    }
                    Group g = groups.computeIfAbsent(t, x -> new Group(new TreeSet<>(), new TreeSet<>()));
                    g.sets().add(m.set());
                    g.buckets().add(bk.label());
                }
            }
            if (set.grouping().isEmpty() && groups.isEmpty() && !o.shown()) {
                groups.put(Map.of(), new Group(new TreeSet<>(), new TreeSet<>()));
            }
            TreeMap<CellKey, Partials.Entry> entries = new TreeMap<>();
            for (Map.Entry<Map<String, String>, Group> e : groups.entrySet()) {
                if (++cells > props.maxCells()) {
                    throw PivotView.bad("the view asks more than " + props.maxCells() + " cells of the CCR cube (drishti.cube.ccr.max-cells): "
                            + "filter it, or open fewer levels");
                }
                Group g = e.getValue();
                boolean whole = g.buckets().size() == buckets.size();
                Cell cell = computed.computeIfAbsent(String.join(",", g.sets()), k -> cell(p, o, g, ask));
                Object[] states = new Object[plan.functions().size()];
                Map<Integer, String> notes = new TreeMap<>();
                for (int f = 0; f < states.length; f++) {
                    String fn = plan.functions().get(f).fn().name();
                    if (!whole && WHOLE_LIFE.contains(fn)) {
                        notes.put(f, fn + "() is a whole-life figure: not by tenor bucket");
                        states[f] = new Final(null);
                        continue;
                    }
                    Double x = figure(fn, cell, o.date(), g, whole ? null : g.buckets(), eadBySet, fvaBySet, ask);
                    if (x == null && cell.note() != null) {
                        notes.put(f, cell.note());
                    }
                    states[f] = new Final(x);
                }
                String scope = FvaPivotProvider.scope(withoutBucket(e.getKey()));
                entries.put(set.key(e.getKey()), new Partials.Entry(g.sets().size(), states, scope, notes));
            }
            out.add(new Partials.SetResult(set, entries));
        }
        return new Partials(out, new Partials.Stats(computed.size(), List.of("the exposure engine's paths summed per cell (" + computed.size()
                + " scopes), the statistics last"), (System.nanoTime() - t0) / 1_000_000, o.members().size()));
    }

    private static Map<String, String> withoutBucket(Map<String, String> coords) {
        Map<String, String> m = new TreeMap<>(coords);
        m.remove(BUCKET);
        return m;
    }

    /** A cell's netting sets and tenor buckets. */
    record Group(TreeSet<String> sets, TreeSet<String> buckets) {}

    /** A cell's exposure answer (null with a note when it has none: no paths, or too large for an interactive answer). */
    record Cell(ExposureService.Scoped scoped, String note) {}

    private Cell cell(Principal p, Opened o, Group g, ExposureService.Ask ask) {
        String scope = g.sets().isEmpty() ? "BANK" : g.sets().size() == o.members().size() && o.shown() ? "BANK" : "NSET:" + String.join(",", g.sets());
        try {
            return new Cell(svc.scoped(p, scope, o.date().date(), ask), null);
        } catch (DrishtiException e) {
            if (e.errorCode() == ErrorCode.FORBIDDEN) {
                throw e;
            }
            return new Cell(null, e.getMessage());
        }
    }

    private static boolean keep(AggregationPlan plan, CellSet set, Map<String, String> c) {
        for (Map.Entry<String, String> at : set.at().entrySet()) {
            if (!at.getValue().equals(c.get(at.getKey()))) {
                return false;
            }
        }
        for (Map.Entry<String, AggregationPlan.Filter> f : plan.filters().entrySet()) {
            String val = c.get(f.getKey());
            if (val == null || (!f.getValue().in().isEmpty() && !f.getValue().in().contains(val)) || f.getValue().notIn().contains(val)) {
                return false;
            }
        }
        for (Map.Entry<String, List<String>> ex : set.exclude().entrySet()) {
            if (ex.getValue().contains(c.get(ex.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** The primary dates (indices among the primaries) inside the given buckets; null: every date. */
    int[] dates(ExposureGrid grid, java.util.Set<String> in) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < grid.primaryCount(); i++) {
            double t = grid.time(grid.primaryAt(i));
            for (CcrCubeProperties.Bucket b : buckets) {
                if ((in == null || in.contains(b.label())) && b.holds(t)) {
                    idx.add(i);
                    break;
                }
            }
        }
        return idx.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The peak of a profile over the given primary dates (all when null). */
    static double peak(double[] profile, int[] at) {
        double m = 0;
        if (at == null) {
            for (double x : profile) {
                m = Math.max(m, x);
            }
            return m;
        }
        for (int i : at) {
            m = Math.max(m, profile[i]);
        }
        return m;
    }

    /** The counterparty's stored limit when the cell's netting sets all belong to one counterparty, else null. */
    static ExposureDate.Limit limitOf(ExposureDate d, java.util.Set<String> sets) {
        String cp = null;
        ExposureBook b = d.book();
        for (String s : sets) {
            String c = b.set(b.setOrdinal(s)).counterparty();
            if (cp != null && !cp.equals(c)) {
                return null;
            }
            cp = c;
        }
        return cp == null ? null : d.limits().get(cp);
    }

    /** The PFE the limit is set on, over the given dates: at the limit's confidence when computed, else at the view's. */
    double used(ExposureMath.Stats st, int[] at, double q) {
        int a = st.at(svc.props().limitConfidence());
        if (a < 0) {
            a = Math.max(0, st.at(q));
        }
        return peak(st.pfe[a], at);
    }

    private Double figure(String fn, Cell cell, ExposureDate d, Group g, java.util.Set<String> in, Map<String, Double> eadBySet,
            Map<String, Double> fvaBySet, ExposureService.Ask ask) {
        switch (fn) {
            case "nettingSets":
                return (double) g.sets().size();
            case "ead":
                return sum(eadBySet, g.sets());
            case "fva":
                return sum(fvaBySet, g.sets());
            default:
                break;
        }
        if (cell.scoped() == null) {
            return null;
        }
        ExposureMath.Stats st = cell.scoped().result().stats;
        int[] at = in == null ? null : dates(d.book().grid(), in);
        ExposureDate.Limit lim = limitOf(d, g.sets());
        return switch (fn) {
            case "pfe" -> peak(st.pfe[Math.max(0, st.at(ask.q()))], at);
            case "ee" -> peak(st.ee, at);
            case "epe" -> st.epe;
            case "eepe" -> st.eepe;
            case "ce" -> st.ce;
            case "limit" -> lim == null ? null : lim.amount();
            case "limit_use" -> lim == null ? null : used(st, at, ask.q()) / lim.amount();
            case "headroom" -> lim == null ? null : lim.amount() - used(st, at, ask.q());
            default -> null;
        };
    }

    private static Double sum(Map<String, Double> bySet, java.util.Set<String> sets) {
        if (bySet == null) {
            return null;
        }
        double s = 0;
        for (String id : sets) {
            Double x = bySet.get(id);
            s += x == null ? 0 : x;
        }
        return s;
    }

    @Override
    public Rows drill(Viewer v, AggregationPlan plan, CellKey key, int offset, int limit) {
        throw new DrishtiException(ErrorCode.BAD_REQUEST, "the CCR cube's rows are its netting sets: open them with EXP <scope> or the cube's tab B "
                + "(the cell's scope is in its provenance)");
    }

    @Override
    public Optional<String> scopeOf(Viewer v, AggregationPlan plan, CellKey key) {
        return Optional.ofNullable(FvaPivotProvider.scope(withoutBucket(key.coords())));
    }

    /** A per-set figure source over an engine's bank-wide answer, computed once per person and date while the source runs. */
    static PerSet cached(BooleanSupplier on, Function<Map.Entry<Principal, LocalDate>, Map<String, Double>> read) {
        com.github.benmanes.caffeine.cache.Cache<String, Map<String, Double>> c = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(64).expireAfterWrite(java.time.Duration.ofMinutes(10)).build();
        return (p, d) -> {
            if (!on.getAsBoolean()) {
                return null;
            }
            String key = (p == null ? "" : p.user() + p.roles()) + "|" + d;
            return c.get(key, k -> read.apply(Map.entry(p, d)));
        };
    }
}
