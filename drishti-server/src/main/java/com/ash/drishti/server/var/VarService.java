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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The VaR engine in the server (VAR_ENGINE.md): the loaded business dates, scope resolution as the viewer, the scope's vector (planned
 * over the node aggregates) and its measures, cached per date, data version, viewer and scope. The screens' answers are built from
 * what this returns ({@link VarViews}); baskets and captures are kept by {@link VarSaved}, the official end-of-day records by
 * {@link VarOfficialStore}.
 *
 * <p>A date whose inputs are not one run (DRS-5203) is not loaded: the newest date before it is answered instead, with a warning in
 * every answer's provenance, until the date's vectors change. A null principal is the server itself (the official captures): every
 * node at positions level.
 *
 * <p>Python is not on this path: base VaR is additions over the in-memory vectors and a sort of 509 numbers per answer. The heavy work
 * that does need Python (pricing a what-if over the scenarios, wave C/V4) goes to compute workers, as many as configured; nothing here
 * assumes a single one.
 */
public final class VarService implements AutoCloseable {

    /** A scope resolved and computed for one viewer on one date. */
    record Scoped(VarDate date, VarAccess.View view, ScopeResolver.Resolution res, OrdinalSet set, int hidden, double[] vector,
            VarCalc.Measures measures, String kind, String label, long computeMs, boolean cached) {}

    /** The words of a scope that name positions or trades one by one: only for a viewer who sees them one by one. */
    private static final List<String> NAMING = List.of("POS", "TRD", "CPTY", "WHERE", "BASKET", "CAPTURE");
    private static final int CACHE = 512;
    private final VarProperties props;
    private final VarLoader loader;
    private final VarAccess access;
    private final VarSaved saved;
    private final VarOfficialStore official;
    private final AuditLog audit;
    private final ParallelSums sums;
    private final VarCalc calc;
    private final Map<LocalDate, CompletableFuture<VarDate>> dates = new ConcurrentHashMap<>();
    /** Dates that could not be loaded, with the vector version they were refused at and why. */
    private final Map<LocalDate, String[]> refused = new ConcurrentHashMap<>();
    private final com.github.benmanes.caffeine.cache.Cache<String, Scoped> cache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(CACHE).build();      // the answers last asked for

    VarService(VarProperties props, VarLoader loader, VarAccess access, VarSaved saved, VarOfficialStore official, AuditLog audit, ParallelSums sums) {
        this.props = props;
        this.loader = loader;
        this.access = access;
        this.saved = saved;
        this.official = official;
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

    VarAccess access() {
        return access;
    }

    VarOfficialStore official() {
        return official;
    }

    VarLoader loader() {
        return loader;
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
        return load(asked == null || asked.isAfter(latest) ? latest : asked, 0);
    }

    private VarDate load(LocalDate d, int depth) {
        String[] r = refused.get(d);
        if (r != null && r[0].equals(loader.version(d))) {
            return previous(d, r[1], depth);
        }
        refused.remove(d);
        CompletableFuture<VarDate> f = dates.computeIfAbsent(d, x -> CompletableFuture.supplyAsync(() -> loader.load(x)));
        try {
            VarDate vd = f.join();
            evictOld(d);
            return vd;
        } catch (java.util.concurrent.CompletionException e) {
            dates.remove(d, f);                                        // a failed load is tried again by the next request
            if (e.getCause() instanceof DrishtiException de) {
                if (de.errorCode() == ErrorCode.VAR_INCONSISTENT_DATE) {
                    refused.put(d, new String[] {loader.version(d), de.getMessage()});
                    return previous(d, de.getMessage(), depth);
                }
                throw de;
            }
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the VaR data of " + d + " could not be loaded: " + e.getCause());
        }
    }

    /** The newest date before a refused one, with the reason as a warning; the refusal itself when there is none. */
    private VarDate previous(LocalDate d, String why, int depth) {
        LocalDate p = depth < 5 ? loader.previous(d) : null;
        if (p == null) {
            throw new DrishtiException(ErrorCode.VAR_INCONSISTENT_DATE, why);
        }
        return load(p, depth + 1).warned(why + "; answered from " + p + " instead");
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
                retire(dates.remove(d), false);
                cache.asMap().keySet().removeIf(k -> k.startsWith(d + "|"));
            }
        }
    }

    /**
     * A date leaves memory: a mapped one is unmapped once the requests still reading it are done (drishti.var.tier.close-grace-seconds);
     * its hot file stays for the next request of the date (a history date opens from it) unless it is {@code stale}.
     */
    private void retire(CompletableFuture<VarDate> f, boolean stale) {
        if (f != null) {
            f.thenAccept(vd -> loader.tier().retire(vd.book(), stale));
        }
    }

    /** Drops every loaded date (an administrator's reload). */
    void reload() {
        new ArrayList<>(dates.keySet()).forEach(d -> retire(dates.remove(d), false));
        refused.clear();
        cache.invalidateAll();
    }

    /**
     * Drops one date (the data-load signal of an input of that date): its next request loads it again. Its hot file goes with it once
     * unused when the inputs changed (a restatement); unchanged inputs open the same file again.
     */
    void reload(LocalDate d) {
        retire(dates.remove(d), false);
        refused.remove(d);
        cache.asMap().keySet().removeIf(k -> k.startsWith(d + "|"));
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
                m.put("vectorBytes", vd.book().vectorBytes());
                m.put("tier", vd.book().tier());
                m.put("aggregateBytes", vd.book().aggregateBytes());
                if (vd.book().hot() != null) {
                    m.put("hotFile", loader.tier().root().relativize(vd.book().hot().file()).toString().replace('\\', '/'));
                }
                m.put("loadMs", vd.loadMs());
                m.put("version", vd.version());
                m.put("rejected", vd.intake().rejected());
                m.put("excluded", vd.intake().excluded());
                m.put("batchIds", vd.intake().batchIds());
                out.add(m);
            }
        });
        refused.forEach((d, r) -> out.add(Map.of("businessDate", d.toString(), "refused", r[1])));
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
                return p == null ? Optional.empty() : saved.basket(p, name).map(b -> String.valueOf(b.get("scope")));
            }

            @Override
            public Optional<String> capture(String id) {
                return p == null ? Optional.empty() : saved.capture(p, id).map(c -> {
                    List<String> members = members(c);
                    return members.isEmpty() ? "TRD:NONE" : "TRD:" + String.join(",", members);
                });
            }
        };
    }

    /** A capture's member trades: listed in it, or (an official capture) its node's trades in the membership recorded with it. */
    @SuppressWarnings("unchecked")
    List<String> members(Map<String, Object> capture) {
        if (capture.get("members") instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        Object key = capture.get("membership");
        Object node = capture.get("node");
        if (key == null || node == null) {
            return List.of();
        }
        return official.membership(String.valueOf(key)).map(m -> {
            List<String> out = new ArrayList<>();
            Object books = m.get("nodes") instanceof Map<?, ?> n ? n.get(String.valueOf(node)) : null;
            Map<?, ?> byBook = m.get("books") instanceof Map<?, ?> bb ? bb : Map.of();
            if (books instanceof List<?> l) {
                for (Object book : l) {
                    if (byBook.get(String.valueOf(book)) instanceof Map<?, ?> positions) {
                        positions.values().forEach(ts -> {
                            if (ts instanceof List<?> tl) {
                                tl.forEach(t -> out.add(String.valueOf(t)));
                            }
                        });
                    }
                }
            }
            return (List<String>) out;
        }).orElse(List.of());
    }

    /** Resolves and computes a scope as the viewer (cached); a null viewer is the server (every node at positions level). */
    Scoped scoped(Principal p, String scope, LocalDate asked) {
        long t0 = System.nanoTime();
        VarDate d = date(asked);
        VarAccess.View view = access.view(p, d);
        ScopeResolver.Resolution res;
        ScopeExpr parsed;
        try {
            parsed = ScopeExpr.parse(scope);
            res = new ScopeResolver(d.book(), definitions(p, d)).resolve(scope);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        if (parsed.refs().stream().anyMatch(r -> r.word().equals("CPTY")) && access.masked(p, "COUNTERPARTY")) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the counterparty is masked for your role: a scope may not select trades by it");
        }
        boolean namesTrades = parsed.refs().stream().anyMatch(r -> NAMING.contains(r.word()) || r.word().equals("PORT") && basket(d, r.arg()));
        if (namesTrades && !view.seesPositions(res.set())) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "a scope of positions, trades, a counterparty, a rule or a basket portfolio may only "
                    + "name what you see position by position; your role sees some of these only in aggregate");
        }
        String key = d.date() + "|" + d.version() + "|" + view.signature() + "|" + res.canonical() + "|" + res.set().digest();
        Scoped hit = cache.getIfPresent(key);
        if (hit != null) {
            return new Scoped(d, hit.view(), hit.res(), hit.set(), hit.hidden(), hit.vector(), hit.measures(), hit.kind(), hit.label(),
                    (System.nanoTime() - t0) / 1_000_000, true);
        }
        OrdinalSet set = view.full() ? res.set() : res.set().and(view.visible());
        int hidden = res.set().size() - set.size();
        double[] v = vector(d, set, res);
        VarCalc.Measures m = calc.measures(v);
        String kind = kindOf(res, parsed);
        Scoped s = new Scoped(d, view, res, set, hidden, v, m, kind, label(d, res, parsed, kind, p), (System.nanoTime() - t0) / 1_000_000, false);
        cache.put(key, s);
        return s;
    }

    /** A stored portfolio of type BASKET (named positions and trades): probing it is naming them (VAR.md section 8.3). */
    private static boolean basket(VarDate d, String id) {
        VarDate.Portfolio pf = d.portfolios().get(id);
        return pf != null && "BASKET".equals(pf.type());
    }

    /** The vector of a set: a whole node is its aggregate; anything else the planner's sum. */
    double[] vector(VarDate d, OrdinalSet set, ScopeResolver.Resolution res) {
        if (res.node() != null && set.equals(d.book().node(res.node()).members())) {
            return d.book().aggregate(res.node());
        }
        return d.book().vector(set, sums);
    }

    private static String kindOf(ScopeResolver.Resolution res, ScopeExpr e) {
        if (res.node() != null) {
            return "node";
        }
        if (res.position() >= 0) {
            return "position";
        }
        if (res.trade() >= 0) {
            return "trade";
        }
        if (e instanceof ScopeExpr.Ref r) {
            return switch (r.word()) {
                case "LE" -> "legal-entity";
                case "PORT" -> "portfolio";
                case "BASKET" -> "basket";
                case "CAPTURE" -> "capture";
                case "WHERE" -> "rule";
                case "CPTY" -> "counterparty";
                default -> "adhoc";
            };
        }
        return "adhoc";
    }

    private String label(VarDate d, ScopeResolver.Resolution res, ScopeExpr e, String kind, Principal p) {
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
                ScopeExpr.Ref r = (ScopeExpr.Ref) e;
                VarDate.Portfolio pf = d.portfolios().get(r.arg());
                yield pf == null || pf.name() == null ? r.arg() : pf.name();
            }
            case "basket" -> {
                ScopeExpr.Ref r = (ScopeExpr.Ref) e;
                yield p == null ? r.arg() : saved.basket(p, r.arg()).map(m -> String.valueOf(m.get("name"))).orElse(r.arg());
            }
            case "counterparty" -> access.masked(p, "COUNTERPARTY") ? com.ash.drishti.api.DataNode.MASK : ((ScopeExpr.Ref) e).arg();
            default -> res.canonical();
        };
    }

    /** One audit entry per answer delivered (VAR_ENGINE.md section 12.2): who, what scope, which date and view, the result, the time. */
    void audit(Principal p, String action, Scoped s, String extra) {
        if (audit == null) {
            return;
        }
        try {
            audit.record(p == null ? VarOfficialStore.OWNER : p.user(), action, s.res().canonical(), String.format(java.util.Locale.ROOT,
                    "date=%s version=%s view=%s var=%.0f es=%.0f trades=%d hidden=%d members=%s ms=%d%s", s.date().date(), s.date().version(),
                    s.view().full() ? "all" : "restricted", s.measures().var(), s.measures().es(), s.set().size(), s.hidden(), s.set().digest(),
                    s.computeMs(), extra == null ? "" : " " + extra));
        } catch (RuntimeException e) {
            // the audit trail is best effort for reads; a failure never hides the answer
        }
    }

    /** An audit entry that is not an answer: a basket saved or deleted, a reload, an official capture. */
    void audit(String user, String action, String target, String detail) {
        if (audit == null) {
            return;
        }
        try {
            audit.record(user, action, target, detail);
        } catch (RuntimeException e) {
            // best effort, as above
        }
    }

    @Override
    public void close() {
        loader.tier().close();
        sums.close();
    }
}
