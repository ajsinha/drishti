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
import com.ash.drishti.bicompute.functions.Final;
import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.Kind;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * FVA as a measure of the pivot engine (PIVOT_ENGINE.md section 6.1; docs/guides/FVA_GUIDE.md section 6.3), source {@code fva}: the
 * provider the CCR cube reads FVA through (COUNTERPARTY_ENGINES.md decision 15: FVA a measure beside EE and EAD). Members are the netting
 * sets of the business date as the person sees them; the dimensions are {@code NETTING_SET}, {@code COUNTERPARTY}, {@code LEGAL_ENTITY}
 * and {@code GROUP}; the native measures {@code fva()}, {@code fca()}, {@code fba()} and {@code nettingSets()}. FVA is linear in the
 * profile, so a cell's figure is the sum of its own netting sets' FVA from one FVA answer of the bank (the FVA engine's, as the person),
 * which equals the engine's answer for the cell's scope ({@code GET /api/v1/fva/scope}); each cell carries that scope in its provenance.
 * A person who sees some sets only in aggregate gets no dimension.
 */
public final class FvaPivotProvider implements PivotProvider {

    /** The source id. */
    public static final String SOURCE = "fva";
    static final List<String> DIMS = List.of("NETTING_SET", "COUNTERPARTY", "LEGAL_ENTITY", "GROUP");
    static final List<Native> NATIVES = List.of(
            new Native("fva", "FVA", Kind.HOLISTIC, "money", "the cell's FVA: FCA + FBA of its netting sets (the FVA engine's)"),
            new Native("fca", "FCA", Kind.HOLISTIC, "money", "the cell's funding cost"),
            new Native("fba", "FBA", Kind.HOLISTIC, "money", "the cell's funding benefit"),
            new Native("nettingSets", "Netting sets", Kind.HOLISTIC, "count", "the netting sets in the cell"));

    private final FvaService svc;

    FvaPivotProvider(FvaService svc) {
        this.svc = svc;
    }

    @Override
    public String id() {
        return SOURCE;
    }

    @Override
    public boolean serves(String source) {
        return SOURCE.equals(source) && svc.running() && svc.exposure().running();
    }

    @Override
    public Map<String, String> sources(Viewer v) {
        return svc.running() && svc.exposure().running() ? Map.of(SOURCE, "FVA (the FVA engine's netting sets)") : Map.of();
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(false, false, false, true);
    }

    @Override
    public Optional<AggFunction<Object>> nativeFunction(String name, List<String> args) {
        for (Native n : NATIVES) {
            if (n.name().equals(name)) {
                return Optional.of(Final.function(name, true));
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

    /** One netting set as a member: its coordinates and figures. */
    record Member(Map<String, String> coords, double fca, double fba, double fva) {}

    /** The bank's FVA as the person sees it on the date, one member per netting set. */
    private List<Member> members(Viewer v, String date, FvaService.Answer[] out) {
        FvaService.Answer a = svc.answer(principal(v), "BANK", day(date), FvaService.Choices.none(), null, null);
        out[0] = a;
        ExposureDate d = a.scoped().date();
        ExposureBook b = d.book();
        Map<String, String> groupOf = new LinkedHashMap<>();
        d.groups().forEach((g, ms) -> ms.forEach(c -> groupOf.putIfAbsent(c, g)));
        List<Member> out2 = new ArrayList<>();
        for (FvaService.SetFva s : a.sets()) {
            Map<String, String> c = new TreeMap<>();
            ExposureBook.SetInfo si = b.set(b.setOrdinal(s.id()));
            c.put("NETTING_SET", s.id());
            c.put("COUNTERPARTY", blank(s.counterparty()));
            c.put("LEGAL_ENTITY", blank(si.legalEntity()));
            c.put("GROUP", blank(groupOf.get(s.counterparty())));
            out2.add(new Member(c, s.result().fca(), s.result().fba(), s.result().fva()));
        }
        return out2;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? CellKey.BLANK : s;
    }

    @Override
    public Catalogue catalogue(Viewer v, String source, String date) {
        FvaService.Answer[] a = new FvaService.Answer[1];
        members(v, date, a);
        List<Field> fields = new ArrayList<>();
        if (a[0].setsShown()) {
            for (String dname : DIMS) {
                fields.add(new Field(dname, "string", dname.toLowerCase(java.util.Locale.ROOT).replace('_', ' '), "the netting sets' " + dname));
            }
        }
        Map<String, Object> notes = new LinkedHashMap<>();
        notes.put("route", "the FVA engine's answer for the bank: a cell's FVA is the sum of its netting sets' (FVA adds over sets)");
        notes.put("flags", a[0].flags());
        if (!a[0].setsShown()) {
            notes.put("aggregate", "your role sees some netting sets only in aggregate: no dimension is offered");
        }
        String d = a[0].scoped().date().date().toString();
        return new Catalogue(SOURCE, "FVA", fields, NATIVES, d, List.of(d), version(v, source, date), 0, notes);
    }

    @Override
    public String version(Viewer v, String source, String date) {
        FvaService.Answer[] a = new FvaService.Answer[1];
        members(v, date, a);
        ExposureDate d = a[0].scoped().date();
        return d.date() + "|" + d.version() + "|" + a[0].scoped().view().signature() + "|" + a[0].borrow().id();
    }

    @Override
    public Estimate estimate(Viewer v, AggregationPlan plan) {
        FvaService.Answer[] a = new FvaService.Answer[1];
        int n = members(v, plan.date(), a).size();
        return new Estimate(n, -1, plan.dims().size(), DIMS.size(), 0.01, "the FVA engine's netting sets of the date (in memory)");
    }

    @Override
    public Partials evaluate(Viewer v, AggregationPlan plan) {
        long t0 = System.nanoTime();
        FvaService.Answer[] a = new FvaService.Answer[1];
        List<Member> members = members(v, plan.date(), a);
        for (String d : plan.dims().keySet()) {
            if (!DIMS.contains(d) || !a[0].setsShown()) {
                throw new DrishtiException(ErrorCode.PIVOT_MASKED, d + " is not a dimension of the FVA source you may use (" + String.join(", ", DIMS) + ")");
            }
        }
        List<Partials.SetResult> out = new ArrayList<>();
        for (CellSet set : plan.sets()) {
            Map<Map<String, String>, List<Member>> cells = new LinkedHashMap<>();
            for (Member m : members) {
                if (!keep(plan, set, m)) {
                    continue;
                }
                Map<String, String> t = new TreeMap<>();
                for (String d : set.grouping()) {
                    t.put(d, m.coords().get(d));
                }
                cells.computeIfAbsent(t, x -> new ArrayList<>()).add(m);
            }
            if (set.grouping().isEmpty() && cells.isEmpty()) {
                cells.put(Map.of(), List.of());
            }
            TreeMap<CellKey, Partials.Entry> entries = new TreeMap<>();
            for (Map.Entry<Map<String, String>, List<Member>> c : cells.entrySet()) {
                Object[] states = new Object[plan.functions().size()];
                for (int f = 0; f < states.length; f++) {
                    states[f] = new Final(figure(plan.functions().get(f).fn().name(), c.getValue()));
                }
                entries.put(set.key(c.getKey()), new Partials.Entry(c.getValue().size(), states, scope(c.getKey()), Map.of()));
            }
            out.add(new Partials.SetResult(set, entries));
        }
        return new Partials(out, new Partials.Stats(1, List.of("one FVA answer of the bank, its netting sets summed per cell"),
                (System.nanoTime() - t0) / 1_000_000, members.size()));
    }

    private static boolean keep(AggregationPlan plan, CellSet set, Member m) {
        for (Map.Entry<String, String> at : set.at().entrySet()) {
            if (!at.getValue().equals(m.coords().get(at.getKey()))) {
                return false;
            }
        }
        for (Map.Entry<String, AggregationPlan.Filter> f : plan.filters().entrySet()) {
            String val = m.coords().get(f.getKey());
            if (val == null || (!f.getValue().in().isEmpty() && !f.getValue().in().contains(val)) || f.getValue().notIn().contains(val)) {
                return false;
            }
        }
        for (Map.Entry<String, List<String>> ex : set.exclude().entrySet()) {
            if (ex.getValue().contains(m.coords().get(ex.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static Double figure(String fn, List<Member> ms) {
        double s = 0;
        for (Member m : ms) {
            s += switch (fn) {
                case "fca" -> m.fca();
                case "fba" -> m.fba();
                case "fva" -> m.fva();
                case "nettingSets" -> 1;
                default -> Double.NaN;
            };
        }
        return Double.isNaN(s) ? null : s;
    }

    /** The cell's scope in the exposure engine's grammar, when one word names it (else the bank's members filtered: no scope text). */
    static String scope(Map<String, String> coords) {
        if (coords.isEmpty()) {
            return "BANK";
        }
        if (coords.size() == 1) {
            Map.Entry<String, String> e = coords.entrySet().iterator().next();
            if (CellKey.BLANK.equals(e.getValue())) {
                return null;
            }
            return switch (e.getKey()) {
                case "NETTING_SET" -> "NSET:" + e.getValue();
                case "COUNTERPARTY" -> "CPTY:" + e.getValue();
                case "LEGAL_ENTITY" -> "LE:" + e.getValue();
                case "GROUP" -> "GROUP:" + e.getValue();
                default -> null;
            };
        }
        return null;
    }

    @Override
    public Rows drill(Viewer v, AggregationPlan plan, CellKey key, int offset, int limit) {
        throw new DrishtiException(ErrorCode.BAD_REQUEST, "the FVA source's rows are its netting sets: open them with FVA <scope> (the cell's scope is in "
                + "its provenance)");
    }

    @Override
    public Optional<String> scopeOf(Viewer v, AggregationPlan plan, CellKey key) {
        return Optional.ofNullable(scope(key.coords()));
    }
}
