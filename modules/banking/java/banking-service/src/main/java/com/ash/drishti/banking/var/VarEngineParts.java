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

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.banking.compute.var.ScopeResolver;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.kernel.api.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R2 consumers (R2_CUT_PLAN Appendix A, {@code var.consumers.route}): the parts of any scope by trade or by position measured by the
 * market-risk engine, for the position and portfolio pages ({@link VarFigures}). The host picks and labels the parts exactly as the
 * in-tree contributions do (the same parts, the same {@code max-parts} cut by gross size, the same attributes and masking, no vector
 * read); each part is a row set of the engine's index of the date; the engine's {@code scope-contributions} measures every part against
 * the whole scope's rows. The scope's P&amp;L series is the engine's {@code scope-path} over the scope's rows. Answered in USD, as the
 * in-tree page reads them.
 */
final class VarEngineParts {

    private final VarService svc;
    private final VarEnginePath engine;

    VarEngineParts(VarService svc, VarEnginePath engine) {
        this.svc = svc;
        this.engine = engine;
    }

    /**
     * The rows of a scope's parts ({@code by}: {@code trade} or {@code position}) as the in-tree contributions answer them, largest
     * component first: {@code key}, {@code scope}, {@code label}, {@code kind}, the standalone, component and incremental figures,
     * {@code pct}, {@code marginalPer1m}, the part's attributes (masked as in-tree) and {@code level}. Refused as in-tree for a viewer who
     * sees the scope in aggregate only.
     */
    List<Map<String, Object>> rows(Principal p, String scope, LocalDate date, String by) {
        VarService.Scoped s = svc.scoped(p, scope, date, VarDate.USD);
        VarBook b = s.date().book();
        if (!s.view().seesPositions(s.set())) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "your role sees this scope in aggregate only: no positions or trades");
        }
        EngineScopeBook book = engine.index(p, date, VarDate.USD);
        ScopeResolver.Resolution res = engine.resolve(p, book, scope, date);
        int max = svc.props().maxParts();
        VarViews.Sizer z = new VarViews.Sizer(b);
        List<VarViews.Part> parts = new ArrayList<>();
        List<Integer> all = new ArrayList<>();
        if (by.equals("position")) {
            List<int[]> runs = VarPartTotals.positionRuns(b, s.set());
            for (int[] r : VarViews.largest(runs, max, x -> z.position(s.set(), x[0]))) {
                VarViews.Part pt = VarViews.position(b, s.set(), r[0], false);
                int e = book.positionIndexAnyCase(pt.key());
                if (e < 0) {
                    throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + VarEnginePath.ENGINE + " has no position " + pt.key()
                            + " on " + date);
                }
                book.forEachPositionRun(e, (from, to) -> {
                    for (int o = from; o < to; o++) {
                        if (res.set().contains(o)) {
                            all.add(o);
                        }
                    }
                });
                all.add(-1);
                parts.add(pt);
            }
        } else if (by.equals("trade")) {
            List<Integer> os = new ArrayList<>();
            s.set().forEach(os::add);
            for (int o : VarViews.largest(os, max, z::trade)) {
                VarViews.Part pt = VarViews.trade(b, o, false);
                int e = book.tradeOrdinalAnyCase(pt.key());
                if (e < 0 || !res.set().contains(e)) {
                    throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + VarEnginePath.ENGINE + " has no trade " + pt.key()
                            + " in " + res.canonical() + " on " + date);
                }
                all.add(e);
                all.add(-1);
                parts.add(pt);
            }
        } else {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the engine's parts of a page are by trade or by position, not " + by);
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = VarEnginePath.params(engine.dateWindow(), VarDate.USD);
        params.put("rows", VarEnginePath.ordinals(res.set()));
        params.put("node", res.node() == null ? "" : res.node());
        params.put("parts", all);
        Map<String, Object> a = engine.ask(p, List.of("scope-contributions"), params, Map.of("date", date.toString()));
        Map<Integer, Map<String, Object>> byPart = new LinkedHashMap<>();
        for (Map<String, Object> r : VarEnginePath.rows(a, "scope-contributions")) {
            byPart.put(((Number) r.get("PART")).intValue(), r);
        }
        if (byPart.size() != parts.size()) {
            throw new DrishtiException(ErrorCode.ENGINE_BLOCKED, "the engine " + VarEnginePath.ENGINE + " measured " + byPart.size() + " of the "
                    + parts.size() + " parts of " + res.canonical() + " on " + date + VarEnginePath.missing(a));
        }
        List<Map<String, Object>> rows = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            VarViews.Part pt = parts.get(i);
            Map<String, Object> c = byPart.get(i);
            double comp = VarEnginePath.num(c.get("COMPONENT_VAR"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", pt.key());
            m.put("scope", pt.scope());
            m.put("label", pt.label());
            m.put("kind", pt.kind());
            m.put("standaloneVar", VarViews.round(VarEnginePath.num(c.get("STANDALONE_VAR"))));
            m.put("standaloneEs", VarViews.round(VarEnginePath.num(c.get("STANDALONE_ES"))));
            m.put("componentVar", VarViews.round(comp));
            m.put("componentEs", VarViews.round(VarEnginePath.num(c.get("COMPONENT_ES"))));
            m.put("pct", VarEnginePath.num(c.get("PCT")));
            m.put("incrementalVar", VarViews.round(VarEnginePath.num(c.get("INCREMENTAL_VAR"))));
            double marginal = Double.isNaN(pt.size()) || pt.size() == 0 ? Double.NaN : comp / Math.abs(pt.size()) * 1_000_000;
            m.put("marginalPer1m", Double.isNaN(marginal) ? null : VarViews.round(marginal));
            pt.attrs().forEach((k, v) -> m.put(k, v != null && svc.access().masked(p, k) ? com.ash.drishti.api.DataNode.MASK : v));
            if (!pt.attrs().containsKey("level")) {
                m.put("level", pt.kind());
            }
            rows.add(m);
        }
        rows.sort(Comparator.comparingDouble(r -> -((Number) r.get("componentVar")).doubleValue()));
        return rows;
    }

    /** The engine's {@code scope-path} row of a scope in a currency (null: USD): {@code PNL} (exact), {@code K}, {@code ES_COUNT}, {@code VAR}, {@code ES}. */
    Map<String, Object> path(Principal p, String scope, LocalDate date, String ccy) {
        String currency = VarCurrencies.normalise(ccy);   // none named: the reporting currency, as in-tree
        EngineScopeBook book = engine.index(p, date, currency);
        ScopeResolver.Resolution res = engine.resolve(p, book, scope, date);
        Map<String, Object> params = VarEnginePath.params(engine.dateWindow(), currency);
        params.put("rows", VarEnginePath.ordinals(res.set()));
        params.put("node", res.node() == null ? "" : res.node());
        params.put("bins", svc.props().histogramBins());
        Map<String, Object> a = engine.ask(p, List.of("scope-path"), params, Map.of("date", date.toString()));
        List<Map<String, Object>> r = VarEnginePath.rows(a, "scope-path");
        if (r.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + VarEnginePath.ENGINE + " answered no path for "
                    + res.canonical() + " on " + date + VarEnginePath.missing(a));
        }
        return r.get(0);
    }

    /** The scope's P&amp;L over the date's scenarios, whole units (the in-tree path's {@code series}), from the engine's {@code scope-path}. */
    List<Long> series(Principal p, String scope, LocalDate date) {
        EngineScopeBook book = engine.index(p, date, VarDate.USD);
        ScopeResolver.Resolution res = engine.resolve(p, book, scope, date);
        Map<String, Object> params = VarEnginePath.params(engine.dateWindow(), VarDate.USD);
        params.put("rows", VarEnginePath.ordinals(res.set()));
        params.put("node", res.node() == null ? "" : res.node());
        params.put("bins", svc.props().histogramBins());
        Map<String, Object> a = engine.ask(p, List.of("scope-path"), params, Map.of("date", date.toString()));
        List<Map<String, Object>> r = VarEnginePath.rows(a, "scope-path");
        if (r.isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the engine " + VarEnginePath.ENGINE + " answered no path for "
                    + res.canonical() + " on " + date + VarEnginePath.missing(a));
        }
        List<Long> out = new ArrayList<>();
        for (double v : VarEnginePath.doubles(r.get(0).get("PNL"))) {
            out.add(Math.round(v));
        }
        return out;
    }
}
