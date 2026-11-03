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
package com.ash.drishti.server.var;

import com.ash.drishti.bicompute.core.AggregationPlan;
import com.ash.drishti.bicompute.core.CellKey;
import com.ash.drishti.bicompute.core.CellSet;
import com.ash.drishti.bicompute.core.Partials;
import com.ash.drishti.bicompute.functions.Final;
import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.Kind;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.var.Columns;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The VaR vector provider of the pivot engine (PIVOT_ENGINE.md section 6.3), source {@code var}: the VaR engine's book of a business date,
 * as the person sees it. Members are the date's trades; a cell key is the view's scope intersected with each coordinate (a position column's
 * value, written in VaR's scope grammar, {@code WHERE:DESK == 'RATES'}); its figures are the VaR engine's own answer for that scope
 * ({@code VaR}, {@code ES} from the summed scenario P&amp;L vector of the cell's own members, the trades' PV and count). So every cell and
 * every total equals {@code GET /api/v1/var/scope} of its scope, and a total is never a sum of cells (holistic). Each cell's scope text is in
 * its provenance, to be asked of the engine itself.
 *
 * <p>The MR cube (after phase P1) ports the paused branch's source onto this provider: hierarchy levels, many sets summed at once on a
 * sharded date, components, stressed VaR and the sensitivities.
 */
public final class VarPivotProvider implements PivotProvider {

    /** The source id. */
    public static final String SOURCE = "var";
    static final List<Native> NATIVES = List.of(
            new Native("var", "VaR", Kind.HOLISTIC, "money", "the cell's own VaR from the summed scenario P&L vector of its trades (the VaR engine's)"),
            new Native("es", "ES", Kind.HOLISTIC, "money", "the cell's own expected shortfall from its summed vector"),
            new Native("pv", "PV", Kind.HOLISTIC, "money", "the sum of the cell's trades' present values"),
            new Native("trades", "Trades", Kind.HOLISTIC, "count", "the trades in the cell"));

    private final VarService svc;
    private final java.util.function.BooleanSupplier on;

    public VarPivotProvider(VarService svc) {
        this(svc, () -> true);
    }

    /** {@code on}: whether the VaR engine runs here (COMPUTE_ENGINES.md: the pivot engine uses var); off, the source is not offered. */
    public VarPivotProvider(VarService svc, java.util.function.BooleanSupplier on) {
        this.svc = svc;
        this.on = on;
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
        return on.getAsBoolean() ? Map.of(SOURCE, "VaR (the VaR engine's trades and scenario vectors)") : Map.of();
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(false, false, false, true);
    }

    @Override
    public Optional<AggFunction<Object>> nativeFunction(String name, List<String> args) {
        for (Native n : NATIVES) {
            if (n.name().equals(name)) {
                return Optional.of(Final.function(name, "pv".equals(name) || "trades".equals(name)));
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

    private static LocalDate day(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.strip());
        } catch (DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.BAD_BUSINESS_DATE, "not a date: '" + date + "' (write 2026-09-30)");
        }
    }

    /** The bank as the person sees it on the date (opens the date when it must). */
    private VarService.Scoped base(Viewer v, String date) {
        return svc.scoped(principal(v), "BANK", day(date));
    }

    /** The position columns a person may pivot by (masked ones left out); none for a role that sees the bank only in aggregate. */
    private List<String> dims(Principal p, VarService.Scoped base) {
        Columns pc = base.date().book().positions();
        if (pc == null || !base.view().seesPositions(base.set())) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String c : pc.textColumns()) {
            if (!svc.access().masked(p, c)) {
                out.add(c);
            }
        }
        return out;
    }

    @Override
    public Catalogue catalogue(Viewer v, String source, String date) {
        Principal p = principal(v);
        VarService.Scoped base = base(v, date);
        List<Field> fields = new ArrayList<>();
        for (String c : dims(p, base)) {
            fields.add(new Field(c, "string", c.toLowerCase(Locale.ROOT).replace('_', ' '), "a column of the VaR engine's positions"));
        }
        List<String> dates = new ArrayList<>(new java.util.TreeSet<>(svc.openDates())).stream().map(LocalDate::toString).toList();
        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("route", "the VaR engine's book of the date: every cell's figures are the engine's answer for the cell's scope");
        notes.put("scenarios", base.date().book().width() + " scenarios, set " + base.date().book().scenarioSet());
        notes.put("hidden", base.hidden() == 0 ? "none" : base.hidden() + " trade(s) not visible to you are left out of every cell");
        if (!base.view().seesPositions(base.set())) {
            notes.put("aggregate", "your role sees the bank in aggregate only: no dimension is offered (hierarchy levels come with the MR cube)");
        }
        return new Catalogue(SOURCE, "VaR", fields, NATIVES, base.date().date().toString(), dates, version(v, source, date), 0, notes);
    }

    @Override
    public String version(Viewer v, String source, String date) {
        VarService.Scoped base = base(v, date);
        VarDate d = base.date();
        return d.date() + "|" + d.currency() + "|" + d.version() + "|i" + d.intradayStamp() + "|" + base.view().signature();
    }

    @Override
    public Estimate estimate(Viewer v, AggregationPlan plan) {
        VarService.Scoped base = base(v, plan.date());
        long trades = base.set().size();
        return new Estimate(trades, trades * base.date().book().width() * 8L, plan.dims().size(), dims(principal(v), base).size(),
                Math.max(0.01, trades * base.date().book().width() / 2e9), "the VaR engine's trades of the date and their scenario vectors (in memory)");
    }

    private static String quote(String v) {
        return "'" + v.replace("'", "''") + "'";
    }

    /** The scope of a cell: the view's scope, its filters, the set's exclusion and the cell's coordinates, in VaR's grammar. */
    static String scope(AggregationPlan plan, CellSet set, Map<String, String> coords) {
        List<String> terms = new ArrayList<>();
        plan.filters().forEach((dim, f) -> {
            if (!f.in().isEmpty()) {
                terms.add("WHERE:" + dim + " in (" + String.join(", ", f.in().stream().map(VarPivotProvider::quote).toList()) + ")");
            }
            if (!f.notIn().isEmpty()) {
                terms.add("WHERE:not " + dim + " in (" + String.join(", ", f.notIn().stream().map(VarPivotProvider::quote).toList()) + ")");
            }
        });
        set.exclude().forEach((dim, vals) -> terms.add("WHERE:not " + dim + " in (" + String.join(", ", vals.stream().map(VarPivotProvider::quote)
                .toList()) + ")"));
        for (Map.Entry<String, String> c : coords.entrySet()) {
            if (CellKey.BLANK.equals(c.getValue())) {
                return null;
            }
            terms.add("WHERE:" + c.getKey() + " == " + quote(c.getValue()));
        }
        if (terms.isEmpty()) {
            return "BANK";
        }
        return terms.size() == 1 ? terms.get(0) : String.join(" & ", terms.stream().map(t -> "(" + t + ")").toList());
    }

    @Override
    public Partials evaluate(Viewer v, AggregationPlan plan) {
        long t0 = System.nanoTime();
        Principal p = principal(v);
        VarService.Scoped base = base(v, plan.date());
        VarBook book = base.date().book();
        Columns pc = book.positions();
        Set<String> allowed = new LinkedHashSet<>(dims(p, base));
        for (String d : plan.dims().keySet()) {
            if (!allowed.contains(d)) {
                throw new DrishtiException(ErrorCode.PIVOT_MASKED, d + " is not a dimension of the VaR source you may use (a position column, "
                        + "not masked, on a scope you see position by position)");
            }
        }
        LocalDate date = base.date().date();
        OrdinalSet members = base.set();
        List<Partials.SetResult> out = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        int calls = 0;
        for (CellSet set : plan.sets()) {
            Set<Map<String, String>> tuples = new LinkedHashSet<>();
            if (set.grouping().isEmpty()) {
                tuples.add(Map.of());
            } else {
                for (int pi = 0; pi < book.positionCount(); pi++) {
                    if (book.positionMemberCountIn(pi, members) == 0) {
                        continue;
                    }
                    Map<String, String> t = new TreeMap<>();
                    boolean keep = true;
                    for (String d : set.grouping()) {
                        String val = pc.text(d, pi);
                        t.put(d, val == null || val.isBlank() ? CellKey.BLANK : val);
                    }
                    for (Map.Entry<String, String> at : set.at().entrySet()) {
                        keep &= at.getValue().equals(t.get(at.getKey()));
                    }
                    for (Map.Entry<String, AggregationPlan.Filter> f : plan.filters().entrySet()) {
                        String val = pc.text(f.getKey(), pi);
                        val = val == null || val.isBlank() ? CellKey.BLANK : val;
                        keep &= (f.getValue().in().isEmpty() || f.getValue().in().contains(val)) && !f.getValue().notIn().contains(val);
                    }
                    for (Map.Entry<String, List<String>> ex : set.exclude().entrySet()) {
                        String val = pc.text(ex.getKey(), pi);
                        keep &= !ex.getValue().contains(val == null || val.isBlank() ? CellKey.BLANK : val);
                    }
                    if (keep) {
                        tuples.add(t);
                    }
                }
            }
            TreeMap<CellKey, Partials.Entry> cells = new TreeMap<>();
            for (Map<String, String> t : tuples) {
                String scope = scope(plan, set, t);
                Object[] states = new Object[plan.functions().size()];
                Map<Integer, String> notes = new LinkedHashMap<>();
                long n = 0;
                if (scope == null) {
                    for (int f = 0; f < states.length; f++) {
                        states[f] = new Final(null);
                        notes.put(f, "no scope text names a blank value: ask the VaR engine of its parent");
                    }
                } else {
                    VarService.Scoped s = svc.scoped(p, scope, date);
                    calls++;
                    asked.add(scope);
                    n = s.set().size();
                    for (int f = 0; f < states.length; f++) {
                        states[f] = new Final(figure(plan.functions().get(f).fn().name(), s, book));
                    }
                }
                cells.put(set.key(t), new Partials.Entry(n, states, scope, notes));
            }
            out.add(new Partials.SetResult(set, cells));
        }
        List<String> stmts = new ArrayList<>();
        stmts.add(calls + " scope(s) asked of the VaR engine, each from its own members' summed vector");
        stmts.addAll(asked.size() > 10 ? asked.subList(0, 10) : asked);
        return new Partials(out, new Partials.Stats(calls, stmts, (System.nanoTime() - t0) / 1_000_000, members.size()));
    }

    private static Double figure(String fn, VarService.Scoped s, VarBook book) {
        return switch (fn) {
            case "var" -> s.measures().var();
            case "es" -> s.measures().es();
            case "trades" -> (double) s.set().size();
            case "pv" -> {
                double[] sum = {0};
                Columns tc = book.trades();
                if (!tc.has("PV")) {
                    yield null;
                }
                s.set().forEach(o -> {
                    double x = tc.number("PV", o);
                    if (Double.isFinite(x)) {
                        sum[0] += x;
                    }
                });
                yield sum[0];
            }
            default -> null;
        };
    }

    @Override
    public Rows drill(Viewer v, AggregationPlan plan, CellKey key, int offset, int limit) {
        throw new DrishtiException(ErrorCode.BAD_REQUEST, "the VaR source's rows are its trades: open them with VAR <scope> (the cell's scope "
                + "is in its provenance)");
    }

    @Override
    public Optional<String> scopeOf(Viewer v, AggregationPlan plan, CellKey key) {
        return Optional.ofNullable(scope(plan, CellSet.of(List.copyOf(key.coords().keySet()), Map.of()), key.coords()));
    }
}
