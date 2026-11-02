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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.cube.CubeProperties;
import com.ash.drishti.server.frtb.FrtbService;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * What only the MR cube has beside the shared core (MR_CUBE_GUIDE.md sections 5 to 8): the time-travel slider's stops (dates and batches),
 * "explain the move" for a cell (the VaR engine's exact steps on the cell's scope, VAR_GUIDE.md section 3.11), the sensitivity ladder against
 * two-sided bucket limits and the factor heat map (sums of the position sensitivities by bucket, in Java), and the what-if side by side per
 * row (the Java what-if on each row's scope). Every figure is the server's; the console only draws them.
 */
public final class VarCubeExtras {

    /** A bucket at this share of its limit is "near" (amber); over 100% a breach. */
    static final double NEAR = 0.8;
    /** The most of the lake's own dates the slider lists (newest first). */
    static final int HELD_DATES = 20;
    private final VarService svc;
    private final VarExplain explain;
    private final VarWhatIf whatIf;
    private final CubeProperties.Mr props;
    private final Supplier<FrtbService> frtb;

    VarCubeExtras(VarService svc, VarExplain explain, CubeProperties.Mr props, Supplier<FrtbService> frtb) {
        this.svc = svc;
        this.explain = explain;
        this.whatIf = new VarWhatIf(svc);
        this.props = props;
        this.frtb = frtb == null ? () -> null : frtb;
    }

    /**
     * The slider's stops: every business date with an official record or open on this server, newest last, each with its batches (the
     * morning's full runs, then the intraday ones in order) when it is open. Raw lake versions are not stops (decision P3).
     */
    public Map<String, Object> versions(Principal p) {
        Set<LocalDate> dates = new TreeSet<>(svc.official().dates());
        dates.addAll(svc.openDates());
        LocalDate d0 = svc.loader().latest();                             // the dates the lake holds, newest first, without opening them
        for (int i = 0; d0 != null && i < HELD_DATES; i++) {
            dates.add(d0);
            d0 = svc.loader().previous(d0);
        }
        Map<LocalDate, Map<String, Object>> open = new HashMap<>();
        for (Map<String, Object> m : svc.loaded()) {
            if (m.get("currency") == null && m.get("businessDate") != null && m.get("refused") == null) {
                open.put(LocalDate.parse(String.valueOf(m.get("businessDate"))), m);
            }
        }
        Set<LocalDate> official = svc.official().dates();
        List<Map<String, Object>> stops = new ArrayList<>();
        for (LocalDate d : dates) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("date", d.toString());
            s.put("official", official.contains(d));
            s.put("held", true);
            Map<String, Object> o = open.get(d);
            s.put("open", o != null);
            if (o != null) {
                s.put("dataVersion", o.get("version"));
                s.put("batches", o.get("batchIds"));
                s.put("intraday", o.get("intraday"));
                s.put("trades", o.get("trades"));
            }
            stops.add(s);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dates", stops);
        out.put("newest", stops.isEmpty() ? null : stops.get(stops.size() - 1).get("date"));
        out.put("note", "the stops are business dates (with an official record, or open here) and, within an open date, its batches in order; "
                + "a date not open is opened when it is chosen");
        return out;
    }

    /**
     * Why a cell moved between two dates: the VaR engine's explanation of the cell's scope (RO1: the exact steps that add up, each with its
     * top contributors), plus the factor overlay when it can be made.
     */
    public Map<String, Object> explain(Principal p, String scope, LocalDate from, LocalDate to, Double confidence) {
        if (scope == null || scope.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "explain names the cell's scope: scope=DESK:DESK-RATES, (BANK) & (WHERE:...)");
        }
        Map<String, Object> out = new LinkedHashMap<>(explain.explain(p, scope.strip(), from, to, confidence).answer());
        out.put("factorOverlay", Map.of("available", false, "note", "the factor overlay (Σ sensitivity × the factor's move between the two "
                + "dates) needs each factor's move by date, which the lake does not hold yet; the exact steps above add up on their own"));
        return out;
    }

    /** The what-if of the same hypothetical trades on each scope (a view's rows and its total), before, after and incremental. */
    public Map<String, Object> whatIf(Principal p, List<String> scopes, List<String> labels, Object items, LocalDate date, String ccy) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < scopes.size(); i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("label", labels.get(i));
            r.put("scope", scopes.get(i));
            if (scopes.get(i) == null) {
                r.put("note", "no scope text names this row");
                rows.add(r);
                continue;
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("scope", scopes.get(i));
            body.put("items", items);
            if (ccy != null) {
                body.put("ccy", ccy);
            }
            try {
                Map<String, Object> a = whatIf.whatIf(p, body, date);
                r.put("before", ((Map<?, ?>) a.get("before")).get("var"));
                r.put("after", ((Map<?, ?>) a.get("after")).get("var"));
                r.put("incremental", ((Map<?, ?>) a.get("change")).get("var"));
                r.put("items", a.get("items"));
            } catch (DrishtiException e) {
                r.put("note", e.getMessage());
            }
            rows.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("engine", "the Java what-if (VarWhatIfMath) on each row's scope: a booked trade's vector copied and scaled, added");
        return out;
    }

    /** A tenor ({@code 3M}, {@code 10Y}, {@code 2W}, {@code 1D}) in years; NaN when it is not one. */
    static double years(String tenor) {
        if (tenor == null || tenor.length() < 2) {
            return Double.NaN;
        }
        String t = tenor.strip().toUpperCase(Locale.ROOT);
        try {
            double n = Double.parseDouble(t.substring(0, t.length() - 1));
            return switch (t.charAt(t.length() - 1)) {
                case 'Y' -> n;
                case 'M' -> n / 12.0;
                case 'W' -> n * 7 / 365.0;
                case 'D' -> n / 365.0;
                default -> Double.NaN;
            };
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** The grid's buckets as {@code [from, to)} in years, checked: no overlap and no gap (a grid that has one is refused in words). */
    Map<String, double[]> grid(String name) {
        Map<String, String> g = props.grids().get(name == null ? "" : name.strip().toUpperCase(Locale.ROOT));
        if (g == null) {
            throw new DrishtiException(ErrorCode.CUBE_BAD_VIEW, "no sensitivity grid " + name + "; the grids: " + props.grids().keySet()
                    + " (drishti.cube.mr.grids)");
        }
        Map<String, double[]> out = new LinkedHashMap<>();
        double at = 0;
        for (Map.Entry<String, String> e : g.entrySet()) {
            String[] ft = e.getValue().split("-", -1);
            double from = Double.parseDouble(ft[0].strip());
            double to = ft.length < 2 || ft[1].isBlank() ? Double.POSITIVE_INFINITY : Double.parseDouble(ft[1].strip());
            if (Math.abs(from - at) > 1e-9 || to <= from) {
                throw new DrishtiException(ErrorCode.CUBE_BAD_VIEW, "the grid " + name + " has a gap or an overlap at " + e.getKey()
                        + " (" + e.getValue() + "): each bucket starts where the last ended");
            }
            out.put(e.getKey(), new double[] {from, to});
            at = to;
        }
        return out;
    }

    /**
     * The sensitivity ladder of a scope: GIRR delta (DV01) by curve currency and the grid's tenor buckets, net and gross, each against its
     * two-sided limit ({@code use = max(net / limit, -net / limit)}), "near" from 80% and a breach over 100%; or, with {@code by} two factor
     * parts, the factor heat map (the sums of one class and measure by the two parts).
     */
    public Map<String, Object> sensitivities(Principal p, String scope, LocalDate date, String grid, String by, String riskClass, String measure,
            String ccy) {
        FrtbService f = frtb.get();
        if (f == null) {
            throw new DrishtiException(ErrorCode.CUBE_OFF, "the sensitivities engine is off on this server: the ladder and the heat map need it");
        }
        String cls = riskClass == null || riskClass.isBlank() ? "GIRR" : riskClass.strip().toUpperCase(Locale.ROOT);
        String meas = measure == null || measure.isBlank() ? deltaWord(cls) : measure.strip().toUpperCase(Locale.ROOT);
        FrtbService.Elements el = f.elements(p, scope == null || scope.isBlank() ? "BANK" : scope.strip(), date, ccy);
        boolean heat = by != null && !by.isBlank();
        List<String> keys = heat ? List.of(by.toUpperCase(Locale.ROOT).split("\\s*,\\s*")) : List.of("QUALIFIER", "BUCKET");
        if (keys.size() != 2) {
            throw new DrishtiException(ErrorCode.CUBE_BAD_VIEW, "by names two factor parts, rows then columns: by=QUALIFIER,TENOR");
        }
        Map<String, double[]> buckets = heat ? null : grid(grid == null || grid.isBlank() ? "BANK-IR-TENOR" : grid);
        Map<String, Map<String, double[]>> sums = new TreeMap<>();
        Map<String, Map<String, Set<String>>> positions = new HashMap<>();
        Set<String> columns = new LinkedHashSet<>();
        if (buckets != null) {
            columns.addAll(buckets.keySet());
        }
        int used = 0;
        int noTenor = 0;
        for (FrtbService.Element e : el.elements()) {
            if (e.factor() == null || e.factor().riskClass() == null || !e.factor().riskClass().toUpperCase(Locale.ROOT).startsWith(cls)
                    || !isDelta(meas, e.measure())) {
                continue;
            }
            String row = part(keys.get(0), e);
            String col;
            if (heat) {
                col = part(keys.get(1), e);
            } else {
                col = bucket(buckets, years(e.factor().tenor()));
                if (col == null) {
                    noTenor++;
                    continue;
                }
            }
            used++;
            if (heat) {
                columns.add(col);
            }
            double[] s = sums.computeIfAbsent(row, k -> new LinkedHashMap<>()).computeIfAbsent(col, k -> new double[2]);
            s[0] += e.value();
            s[1] += Math.abs(e.value());
            positions.computeIfAbsent(row, k -> new HashMap<>()).computeIfAbsent(col, k -> new LinkedHashSet<>()).add(e.positionKey());
        }
        List<String> cols = new ArrayList<>(columns);
        if (heat) {
            cols.sort((a, b) -> {
                double ya = years(a);
                double yb = years(b);
                return Double.isNaN(ya) || Double.isNaN(yb) ? a.compareTo(b) : Double.compare(ya, yb);
            });
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        sums.forEach((r, byCol) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", r);
            List<Map<String, Object>> cells = new ArrayList<>();
            double net = 0;
            double gross = 0;
            for (String c : cols) {
                double[] s = byCol.get(c);
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("column", c);
                cell.put("net", s == null ? null : round(s[0]));
                cell.put("gross", s == null ? null : round(s[1]));
                cell.put("positions", s == null ? 0 : positions.get(r).get(c).size());
                if (!heat && s != null) {
                    Double lim = props.ladderLimits().get(r + " " + c);
                    if (lim != null && lim > 0) {
                        double use = Math.max(s[0] / lim, -s[0] / lim);
                        cell.put("limit", lim);
                        cell.put("use", Math.round(use * 1e6) / 1e6);
                        cell.put("status", use > 1 ? "breach" : use >= NEAR ? "near" : "ok");
                    }
                }
                if (s != null) {
                    net += s[0];
                    gross += s[1];
                }
                cells.add(cell);
            }
            row.put("cells", cells);
            row.put("net", round(net));
            row.put("gross", round(gross));
            rows.add(row);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", heat ? "heatmap" : "ladder");
        out.put("scope", scope);
        out.put("canonical", el.canonical());
        out.put("date", String.valueOf(el.date()));
        out.put("currency", el.reporting());
        out.put("riskClass", cls);
        out.put("measure", meas);
        if (!heat) {
            out.put("grid", grid == null || grid.isBlank() ? "BANK-IR-TENOR" : grid.toUpperCase(Locale.ROOT));
            out.put("grids", props.grids().keySet());
            out.put("limits", props.ladderLimits().isEmpty() ? "no ladder limits are configured (drishti.cube.mr.ladder-limits): no limit bars"
                    : props.ladderLimits().size() + " bucket limits");
        }
        out.put("by", keys);
        out.put("columns", cols);
        out.put("rows", rows);
        out.put("elements", used);
        if (noTenor > 0) {
            out.put("withoutTenor", noTenor);
        }
        out.put("records", el.records());
        out.put("batches", el.batches());
        out.put("proxied", el.proxied());
        out.put("refusals", el.refusals());
        return out;
    }

    /** A class's delta as the sensitivities name it: DV01 for interest rates, CS01 for credit spreads, DELTA for the others. */
    static String deltaWord(String cls) {
        return cls.startsWith("GIRR") ? "DV01" : cls.startsWith("CSR") ? "CS01" : "DELTA";
    }

    /** True when an element's measure is the one asked; DV01, CS01 and DELTA name the same first-order sensitivity. */
    static boolean isDelta(String asked, String measure) {
        if (measure == null) {
            return false;
        }
        if (asked.equalsIgnoreCase(measure)) {
            return true;
        }
        List<String> delta = List.of("DELTA", "DV01", "CS01");
        return delta.contains(asked) && delta.contains(measure.toUpperCase(Locale.ROOT));
    }

    private static String bucket(Map<String, double[]> buckets, double y) {
        if (Double.isNaN(y)) {
            return null;
        }
        for (Map.Entry<String, double[]> b : buckets.entrySet()) {
            if (y >= b.getValue()[0] && y < b.getValue()[1]) {
                return b.getKey();
            }
        }
        return null;
    }

    private static String part(String key, FrtbService.Element e) {
        String v = switch (key) {
            case "QUALIFIER", "CURRENCY" -> e.factor().qualifier();
            case "TENOR" -> e.factor().tenor();
            case "CURVE" -> e.factor().curve();
            case "UNDERLYING" -> e.factor().underlying();
            case "BUCKET" -> e.factor().frtbBucket();
            case "RISK_CLASS", "CLASS" -> e.factor().riskClass();
            case "RISK_FACTOR", "FACTOR" -> e.factor().name();
            case "MEASURE" -> e.measure();
            default -> throw new DrishtiException(ErrorCode.CUBE_BAD_VIEW, key + " is not a factor part: QUALIFIER, TENOR, CURVE, UNDERLYING, "
                    + "BUCKET, RISK_CLASS, RISK_FACTOR or MEASURE");
        };
        return v == null || v.isBlank() ? "—" : v;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
