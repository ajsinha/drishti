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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.frtb.FrtbService;
import com.ash.drishti.kernel.api.security.Principal;
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
import java.util.function.Supplier;

/**
 * The MR lens's sensitivity views (BI_GUIDE.md section 29.7; tab C's ladder C2 and tab B's heat map B4): the sums of the position
 * sensitivities of a scope by bucket, in Java. The ladder is GIRR delta (DV01) by curve currency and a grid's tenor buckets
 * ({@code drishti.cube.mr.grids}), net and gross, each against its two-sided limit ({@code drishti.cube.mr.ladder-limits}); the heat map is one
 * class's and measure's sums by two factor parts. The elements are the sensitivities engine's, read as the person sees the scope.
 */
final class MrSensitivities {

    private final MrCubeProperties props;
    private final com.ash.drishti.banking.RiskLimits limits;           // near from drishti.risk.limit-near, a breach over 100%
    private final Supplier<FrtbService> frtb;

    MrSensitivities(MrCubeProperties props, Supplier<FrtbService> frtb, com.ash.drishti.banking.RiskLimits limits) {
        this.props = props;
        this.limits = limits == null ? com.ash.drishti.banking.RiskLimits.defaults() : limits;
        this.frtb = frtb == null ? () -> null : frtb;
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
            throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, "no sensitivity grid " + name + "; the grids: " + props.grids().keySet()
                    + " (drishti.cube.mr.grids)");
        }
        Map<String, double[]> out = new LinkedHashMap<>();
        double at = 0;
        for (Map.Entry<String, String> e : g.entrySet()) {
            String[] ft = e.getValue().split("-", -1);
            double from = Double.parseDouble(ft[0].strip());
            double to = ft.length < 2 || ft[1].isBlank() ? Double.POSITIVE_INFINITY : Double.parseDouble(ft[1].strip());
            if (Math.abs(from - at) > 1e-9 || to <= from) {
                throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, "the grid " + name + " has a gap or an overlap at " + e.getKey()
                        + " (" + e.getValue() + "): each bucket starts where the last ended");
            }
            out.put(e.getKey(), new double[] {from, to});
            at = to;
        }
        return out;
    }

    /**
     * The sensitivity ladder of a scope: GIRR delta (DV01) by curve currency and the grid's tenor buckets, net and gross, each against its
     * two-sided limit ({@code use = max(net / limit, -net / limit)}), within, near (drishti.risk.limit-near) or breach; or, with {@code by} two factor
     * parts, the factor heat map (the sums of one class and measure by the two parts).
     */
    Map<String, Object> answer(Principal p, String scope, LocalDate date, String grid, String by, String riskClass, String measure,
            String ccy) {
        FrtbService f = frtb.get();
        if (f == null) {
            throw new DrishtiException(ErrorCode.ENGINE_OFF, "the sensitivities engine is off on this server: the ladder and the heat map need it");
        }
        String cls = riskClass == null || riskClass.isBlank() ? "GIRR" : riskClass.strip().toUpperCase(Locale.ROOT);
        String meas = measure == null || measure.isBlank() ? MrCubeExtras.deltaWord(cls) : measure.strip().toUpperCase(Locale.ROOT);
        FrtbService.Elements el = f.elements(p, scope == null || scope.isBlank() ? "BANK" : scope.strip(), date, ccy);
        boolean heat = by != null && !by.isBlank();
        List<String> keys = heat ? List.of(by.toUpperCase(Locale.ROOT).split("\\s*,\\s*")) : List.of("QUALIFIER", "BUCKET");
        if (keys.size() != 2) {
            throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, "by names two factor parts, rows then columns: by=QUALIFIER,TENOR");
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
                    || !MrCubeExtras.isDelta(meas, e.measure())) {
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
                        cell.put("status", limits.status(use));
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
            default -> throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, key + " is not a factor part: QUALIFIER, TENOR, CURVE, UNDERLYING, "
                    + "BUCKET, RISK_CLASS, RISK_FACTOR or MEASURE");
        };
        return v == null || v.isBlank() ? "—" : v;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
