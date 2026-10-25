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
import com.ash.drishti.compute.var.ScopeExpr;
import com.ash.drishti.compute.var.ScopeResolver;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.var.VarCalc;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * The VaR engine in the server (VAR_ENGINE.md): the loaded business dates, scope resolution as the viewer, the scope's vector (planned
 * over the node aggregates) and its measures, cached per date, data version, viewer and scope. The screens' answers are built from
 * what this returns ({@link VarViews}); baskets and captures are kept by {@link VarSaved}.
 *
 * <p>Python is not on this path: base VaR is additions over the in-memory vectors and a sort of 509 numbers per answer. The heavy work
 * that does need Python (pricing a what-if over the scenarios, wave C/V4) goes to compute workers, as many as configured; nothing here
 * assumes a single one.
 */
public final class VarService implements AutoCloseable {

    /** A scope resolved and computed for one viewer on one date. */
    record Scoped(VarDate date, VarAccess.View view, ScopeResolver.Resolution res, OrdinalSet set, int hidden, double[] vector,
            VarCalc.Measures measures, String kind, String label, long computeMs, boolean cached) {}

    private static final int CACHE = 512;
    private final VarProperties props;
    private final VarLoader loader;
    private final VarAccess access;
    private final VarSaved saved;
    private final AuditLog audit;
    private final ParallelSums sums;
    private final VarCalc calc;
    private final Map<LocalDate, CompletableFuture<VarDate>> dates = new ConcurrentHashMap<>();
    private final Map<String, Scoped> cache = java.util.Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Scoped> e) {
            return size() > CACHE;
        }
    });

    VarService(VarProperties props, VarLoader loader, VarAccess access, VarSaved saved, AuditLog audit, ParallelSums sums) {
        this.props = props;
        this.loader = loader;
        this.access = access;
        this.saved = saved;
        this.audit = audit;
        this.sums = sums;
        this.calc = props.calc();
    }

    VarProperties props() {
        return props;
    }

    VarCalc calc() {
        return calc;
    }

    VarSaved saved() {
        return saved;
    }

    ParallelSums sums() {
        return sums;
    }

    // ---------------------------------------------------------------------------------------------------------------------- dates

    /** The date a request reads: the one asked when it has vectors, else the newest on or before it. */
    VarDate date(LocalDate asked) {
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the VaR engine is switched off on this server (drishti.var.enabled)");
        }
        LocalDate latest = loader.latest();
        if (latest == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no P&L vectors in any connector (kind " + props.kinds().vector()
                    + "): build the demo lake (drishti.py lake demo) or point the market-risk pack's risk-store at your lake");
        }
        LocalDate d = asked == null || asked.isAfter(latest) ? latest : asked;
        CompletableFuture<VarDate> f = dates.computeIfAbsent(d, x -> CompletableFuture.supplyAsync(() -> loader.load(x)));
        try {
            VarDate vd = f.join();
            evictOld(d);
            return vd;
        } catch (java.util.concurrent.CompletionException e) {
            dates.remove(d, f);                                        // a failed load is tried again by the next request
            if (e.getCause() instanceof DrishtiException de) {
                throw de;
            }
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the VaR data of " + d + " could not be loaded: " + e.getCause());
        }
    }

    /** Keeps the newest {@code hotDates} dates loaded (the one just used always stays). */
    private void evictOld(LocalDate keep) {
        if (dates.size() <= props.hotDates()) {
            return;
        }
        List<LocalDate> ds = new ArrayList<>(dates.keySet());
        ds.sort(java.util.Comparator.reverseOrder());
        for (LocalDate d : ds.subList(Math.min(ds.size(), props.hotDates()), ds.size())) {
            if (!d.equals(keep)) {
                dates.remove(d);
                cache.keySet().removeIf(k -> k.startsWith(d + "|"));
            }
        }
    }

    /** Drops every loaded date (a new load of the lake: the data-load signal, an administrator). */
    void reload() {
        dates.clear();
        cache.clear();
    }

    /** What is loaded, for the provenance box and Admin. */
    List<Map<String, Object>> loaded() {
        List<Map<String, Object>> out = new ArrayList<>();
        dates.forEach((d, f) -> {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                VarDate vd = f.join();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("businessDate", d.toString());
                m.put("trades", vd.book().tradeCount());
                m.put("positions", vd.book().positionCount());
                m.put("nodes", vd.book().nodes().size());
                m.put("vectorBytes", vd.book().store().bytes());
                m.put("loadMs", vd.loadMs());
                m.put("version", vd.version());
                out.add(m);
            }
        });
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------------- scopes

    /** Definitions of stored portfolios, the caller's baskets and captures, for the resolver. */
    ScopeResolver.Definitions definitions(Principal p, VarDate d) {
        return new ScopeResolver.Definitions() {
            @Override
            public Optional<String> portfolio(String id) {
                VarDate.Portfolio pf = d.portfolios().get(id);
                return pf == null ? Optional.empty() : Optional.of(pf.scope());
            }

            @Override
            public Optional<String> basket(String name) {
                return saved.basket(p, name).map(b -> String.valueOf(b.get("scope")));
            }

            @Override
            public Optional<String> capture(String id) {
                return saved.capture(p, id).map(c -> {
                    Object members = c.get("members");
                    return members instanceof List<?> l && !l.isEmpty() ? "TRD:" + String.join(",", l.stream().map(String::valueOf).toList())
                            : "TRD:NONE";
                });
            }
        };
    }

    /** Resolves and computes a scope as the viewer (cached). */
    Scoped scoped(Principal p, String scope, LocalDate asked) {
        long t0 = System.nanoTime();
        VarDate d = date(asked);
        VarAccess.View view = access.view(p, d);
        ScopeResolver.Resolution res;
        try {
            res = new ScopeResolver(d.book(), definitions(p, d)).resolve(scope);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        boolean namesTrades = ScopeExpr.parse(scope).refs().stream().anyMatch(r -> List.of("POS", "TRD", "WHERE", "BASKET", "CAPTURE").contains(r.word()));
        if (namesTrades && !view.seesPositions(res.set())) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "a scope of positions, trades or a rule may only name what you see position by "
                    + "position; your role sees some of these only in aggregate");
        }
        String key = d.date() + "|" + d.version() + "|" + view.signature() + "|" + res.canonical() + "|" + res.set().digest();
        Scoped hit = cache.get(key);
        if (hit != null) {
            return new Scoped(hit.date(), hit.view(), hit.res(), hit.set(), hit.hidden(), hit.vector(), hit.measures(), hit.kind(), hit.label(),
                    (System.nanoTime() - t0) / 1_000_000, true);
        }
        OrdinalSet set = view.full() ? res.set() : res.set().and(view.visible());
        int hidden = res.set().size() - set.size();
        double[] v = vector(d, set, res);
        VarCalc.Measures m = calc.measures(v);
        String kind = kindOf(res, scope);
        Scoped s = new Scoped(d, view, res, set, hidden, v, m, kind, label(d, res, scope, kind, p), (System.nanoTime() - t0) / 1_000_000, false);
        cache.put(key, s);
        return s;
    }

    /** The vector of a set: a whole node is its aggregate; anything else the planner's sum. */
    double[] vector(VarDate d, OrdinalSet set, ScopeResolver.Resolution res) {
        if (res.node() != null && set.equals(d.book().node(res.node()).members())) {
            return d.book().aggregate(res.node());
        }
        return d.book().vector(set, sums);
    }

    private static String kindOf(ScopeResolver.Resolution res, String scope) {
        if (res.node() != null) {
            return "node";
        }
        if (res.position() >= 0) {
            return "position";
        }
        if (res.trade() >= 0) {
            return "trade";
        }
        ScopeExpr e = ScopeExpr.parse(scope);
        if (e instanceof ScopeExpr.Ref r) {
            return switch (r.word()) {
                case "PORT" -> "portfolio";
                case "BASKET" -> "basket";
                case "CAPTURE" -> "capture";
                case "WHERE" -> "rule";
                default -> "adhoc";
            };
        }
        return "adhoc";
    }

    private String label(VarDate d, ScopeResolver.Resolution res, String scope, String kind, Principal p) {
        VarBook b = d.book();
        return switch (kind) {
            case "node" -> b.node(res.node()).name();
            case "position" -> {
                String ins = b.positions().text("INSTRUMENT", res.position());
                yield b.positionId(res.position()) + (ins == null ? "" : " · " + ins);
            }
            case "trade" -> b.tradeId(res.trade()) + " · " + (b.trades().text("PRODUCT_TYPE", res.trade()) == null ? ""
                    : b.trades().text("PRODUCT_TYPE", res.trade()));
            case "portfolio" -> {
                ScopeExpr.Ref r = (ScopeExpr.Ref) ScopeExpr.parse(scope);
                VarDate.Portfolio pf = d.portfolios().get(r.arg());
                yield pf == null || pf.name() == null ? r.arg() : pf.name();
            }
            case "basket" -> {
                ScopeExpr.Ref r = (ScopeExpr.Ref) ScopeExpr.parse(scope);
                yield saved.basket(p, r.arg()).map(m -> String.valueOf(m.get("name"))).orElse(r.arg());
            }
            default -> res.canonical();
        };
    }

    /** One audit entry per answer delivered (VAR_ENGINE.md section 12.2): who, what scope, which date and view, the result, the time. */
    void audit(Principal p, String action, Scoped s, String extra) {
        if (audit == null) {
            return;
        }
        try {
            audit.record(p.user(), action, s.res().canonical(), String.format(java.util.Locale.ROOT,
                    "date=%s version=%s view=%s var=%.0f es=%.0f trades=%d hidden=%d members=%s ms=%d%s", s.date().date(), s.date().version(),
                    s.view().full() ? "all" : "restricted", s.measures().var(), s.measures().es(), s.set().size(), s.hidden(), s.set().digest(),
                    s.computeMs(), extra == null ? "" : " " + extra));
        } catch (RuntimeException e) {
            // the audit trail is best effort for reads; a failure never hides the answer
        }
    }

    @Override
    public void close() {
        sums.close();
    }
}
