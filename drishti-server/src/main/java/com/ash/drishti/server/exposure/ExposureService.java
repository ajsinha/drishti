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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.ccr.ExposureBook;
import com.ash.drishti.compute.ccr.ExposureCalc;
import com.ash.drishti.compute.ccr.ExposureMath;
import com.ash.drishti.compute.ccr.ExposureScopes;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The exposure engine in the server (EXPOSURE_GUIDE.md): the loaded business dates, scope resolution as the viewer, the scope's exposure matrices
 * summed per path across its netting sets and its statistics, cached per date, data version, viewer, scope and parameters. The answers
 * are built from what this returns ({@link ExposureViews}); captures are kept by {@link ExposureCaptures}, the official end-of-day
 * records by {@link ExposureOfficialStore}.
 *
 * <p>A date whose run is not one run (DRS-5441) is not loaded: the newest date before it answers, with a warning and the flag
 * {@code stale-run} in every answer, until the date's paths change; a request with {@code strict=true} gets the refusal instead. A null
 * principal is the server itself (the official captures): everything at trades level, no interactive limit on the scope's size.
 */
final class ExposureService implements AutoCloseable {

    /** What a request asks besides the scope: the PFE confidence, the collateral model, the run, the strictness. */
    record Ask(double q, String collateral, String run, boolean strict) {
        static Ask of(ExposureProperties props, String q, String collateral, String run, Boolean strict) {
            double qq = props.limitConfidence();
            if (q != null && !q.isBlank()) {
                try {
                    qq = Double.parseDouble(q.trim());
                } catch (NumberFormatException e) {
                    throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "q is a confidence between 0.5 and 0.9999, not " + q);
                }
            }
            if (!(qq > 0.5 && qq <= 0.9999)) {
                throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "q is a confidence between 0.5 and 0.9999, not " + qq);
            }
            String c = collateral == null || collateral.isBlank() ? props.collateralModel() : collateral.trim().toLowerCase(Locale.ROOT);
            try {
                ExposureMath.Model.of(c);
            } catch (IllegalArgumentException e) {
                throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, e.getMessage());
            }
            return new Ask(qq, c, run == null ? "" : run.trim(), Boolean.TRUE.equals(strict));
        }
    }

    /** A scope resolved and computed for one viewer on one date. */
    record Scoped(ExposureDate date, ExposureAccess.View view, ExposureScopes.Resolution res, ExposureCalc.Result result, int hiddenTrades,
            int hiddenSets, String kind, String label, Ask ask, long computeMs, boolean cached) {}

    private static final java.util.regex.Pattern SPACED = java.util.regex.Pattern.compile("(?i)^(NSET|CPTY|GROUP|LE|DESK|BOOK|TRD)\\s+([^:]+)$");

    /**
     * A scope as people type it, in the scope grammar: an id of the retired stored exposure profile ({@code EXP-ALDERSHOT-FRA}, EXPOSURE_GUIDE.md
     * section 22) is its netting set ({@code NSET:NS-ALDERSHOT-FRA}); a spaced scope ({@code NSET NS-ALDERSHOT-FRA}) takes its colon.
     */
    static String scopeText(String typed) {
        String t = typed == null ? "" : typed.trim();
        if (t.toUpperCase(Locale.ROOT).startsWith("EXP-") && t.length() > 4 && !t.contains(" ")) {
            return "NSET:NS-" + t.substring(4).toUpperCase(Locale.ROOT);
        }
        java.util.regex.Matcher m = SPACED.matcher(t);
        return m.matches() ? m.group(1).toUpperCase(Locale.ROOT) + ":" + m.group(2).trim().replaceAll("\\s*,\\s*", ",") : t;
    }

    /** The words of a scope that name trades one by one: only for a viewer who sees those netting sets at trades level. */
    private static final List<String> NAMING = List.of("TRD", "DESK", "BOOK", "WHERE");
    private static final int CACHE = 1000;
    private final ExposureProperties props;
    private final ExposureLoader loader;
    private final ExposureAccess access;
    private final ExposureOfficialStore official;
    private final AuditLog audit;
    private final Map<LocalDate, CompletableFuture<ExposureDate>> dates = new ConcurrentHashMap<>();
    private final Map<LocalDate, String[]> refused = new ConcurrentHashMap<>();
    private final com.github.benmanes.caffeine.cache.Cache<String, Scoped> cache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumWeight(64L << 20).weigher(ExposureService::weight).recordStats().build();

    /** An answer's weight (2.9.7: in bytes, was 1,000 of any size): its trades' bitset, its pieces and trades, the statistics as 8 KB. */
    private static int weight(String key, Scoped s) {
        ExposureCalc.Result r = s.result();
        long b = 8192L + 2L * key.length() + (r == null ? 0 : 512L * r.pieces.size() + (r.trades == null ? 0 : 8L * r.trades.words().length));
        return (int) Math.min(Integer.MAX_VALUE, b);
    }
    private final com.github.benmanes.caffeine.cache.Cache<String, Map<String, double[]>> paths =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumWeight(256L << 20)
                    .weigher((String k, Map<String, double[]> v) -> v.values().stream().mapToInt(a -> a.length * 8).sum() + 64).recordStats().build();
    private final com.ash.drishti.api.SingleFlight<String> pathLoads = new com.ash.drishti.api.SingleFlight<>();
    /** The dates' loads (2.9.7): {@code drishti.ccr.exposure.load-threads} workers of their own, never the common pool. */
    private final java.util.concurrent.ForkJoinPool loads;

    ExposureService(ExposureProperties props, ExposureLoader loader, ExposureAccess access, ExposureOfficialStore official, AuditLog audit) {
        this.props = props;
        this.loader = loader;
        this.access = access;
        this.official = official;
        this.audit = audit;
        this.loads = com.ash.drishti.server.compute.LoadPools.named("drishti-exposure-load", props.loadThreads());
        this.watch = new com.ash.drishti.server.compute.VersionWatch("exposure", props.versionCheck(), loader::version);
    }

    /** The version watch (O9, 2.9.7): an open date notices a lake change without a signal ({@code drishti.ccr.exposure.version-check}). */
    private final com.ash.drishti.server.compute.VersionWatch watch;

    com.ash.drishti.server.compute.VersionWatch watch() {
        return watch;
    }

    /** The date loaded again outside every lock and swapped in when the date open is still {@code from}; else the new one is let go. */
    private void swap(LocalDate d, CompletableFuture<ExposureDate> from) {
        ExposureDate fresh = loader.load(d);
        if (dates.replace(d, from, CompletableFuture.completedFuture(fresh))) {
            drop(d);
            retire(from);
        } else {
            loader.tier().retire(fresh.book(), false);
        }
    }

    ExposureProperties props() {
        return props;
    }

    /** The answers cache (Admin, Caches). */
    com.github.benmanes.caffeine.cache.Cache<String, Scoped> answers() {
        return cache;
    }

    /** The trade-paths cache (Admin, Caches). */
    com.github.benmanes.caffeine.cache.Cache<String, Map<String, double[]>> pathsCache() {
        return paths;
    }

    static final int ANSWERS = CACHE;

    /** The business dates open now. */
    java.util.Set<LocalDate> openDates() {
        return java.util.Set.copyOf(dates.keySet());
    }

    ExposureLoader loader() {
        return loader;
    }

    ExposureAccess access() {
        return access;
    }

    ExposureOfficialStore official() {
        return official;
    }

    // ---------------------------------------------------------------------------------------------------------------------- dates

    ExposureDate date(LocalDate asked) {
        return date(asked, false);
    }

    /**
     * The date a page's link would read, only when it is loaded already (a trade page never waits for a run to load); when it is not, its
     * load starts in the background and the answer is empty.
     */
    java.util.Optional<ExposureDate> loadedDate(LocalDate asked) {
        LocalDate latest = props.enabled() ? loader.latest() : null;
        if (latest == null) {
            return java.util.Optional.empty();
        }
        LocalDate d = asked == null || asked.isAfter(latest) ? latest : asked;
        CompletableFuture<ExposureDate> f = dates.get(d);
        if (f != null && f.isDone() && !f.isCompletedExceptionally()) {
            return java.util.Optional.of(f.join());
        }
        if (f == null) {
            Thread.ofVirtual().name("drishti-exposure-open-" + d).start(() -> {   // it waits for the load: never on the load pool itself
                try {
                    date(d);
                } catch (RuntimeException e) {
                    // the page says the run is not loaded; the explorer says why
                }
            });
        }
        return java.util.Optional.empty();
    }

    /** The date a request reads: the one asked when it has paths, else the newest on or before it. */
    ExposureDate date(LocalDate asked, boolean strict) {
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the exposure engine is switched off on this server (drishti.ccr.exposure.enabled)");
        }
        LocalDate latest = loader.latest();
        if (latest == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no exposure paths in any connector (kind " + props.kinds().paths()
                    + "): build the demo lake (drishti.py lake demo) or point the counterparty-risk pack's exposure-store at your lake");
        }
        return load(asked == null || asked.isAfter(latest) ? latest : asked, 0, strict);
    }

    private ExposureDate load(LocalDate d, int depth, boolean strict) {
        String[] r = refused.get(d);
        if (r != null && r[0].equals(loader.version(d))) {
            if (strict) {
                throw new DrishtiException(ErrorCode.EXPOSURE_INCONSISTENT_RUN, r[1]);
            }
            return previous(d, r[1], depth);
        }
        refused.remove(d);
        CompletableFuture<ExposureDate> f = dates.computeIfAbsent(d, x -> CompletableFuture.supplyAsync(() -> loader.load(x), loads));
        try {
            ExposureDate xd = f.join();
            evictOld(d);
            String w = watch.check(d, xd.version(), false, () -> swap(d, f));           // O9: the lake moved on without a signal
            return w == null ? xd : xd.warned(w);
        } catch (java.util.concurrent.CompletionException e) {
            dates.remove(d, f);
            if (e.getCause() instanceof DrishtiException de) {
                if (de.errorCode() == ErrorCode.EXPOSURE_INCONSISTENT_RUN) {
                    refused.put(d, new String[] {loader.version(d), de.getMessage()});
                    if (strict) {
                        throw de;
                    }
                    return previous(d, de.getMessage(), depth);
                }
                throw de;
            }
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the exposure data of " + d + " could not be loaded: " + e.getCause());
        }
    }

    private ExposureDate previous(LocalDate d, String why, int depth) {
        LocalDate p = depth < 5 ? loader.previous(d) : null;
        if (p == null) {
            throw new DrishtiException(ErrorCode.EXPOSURE_INCONSISTENT_RUN, why);
        }
        return load(p, depth + 1, false).warned(why + "; answered from " + p + " instead");
    }

    private void evictOld(LocalDate keep) {
        if (dates.size() <= props.hotDates()) {
            return;
        }
        List<LocalDate> ds = new ArrayList<>(dates.keySet());
        ds.sort(java.util.Comparator.reverseOrder());
        for (LocalDate d : ds.subList(Math.min(ds.size(), props.hotDates()), ds.size())) {
            if (!d.equals(keep)) {
                retire(dates.remove(d));
                drop(d);
                watch.forget(d);
            }
        }
    }

    private void retire(CompletableFuture<ExposureDate> f) {
        if (f != null) {
            f.thenAccept(xd -> loader.tier().retire(xd.book(), false));
        }
    }

    private void drop(LocalDate d) {
        cache.asMap().keySet().removeIf(k -> k.startsWith(d + "|"));
        paths.asMap().keySet().removeIf(k -> k.startsWith(d + "|"));
        access.forget(d);
    }

    /** Drops every loaded date (an administrator's reload). */
    void reload() {
        new ArrayList<>(dates.keySet()).forEach(d -> retire(dates.remove(d)));
        refused.clear();
        cache.invalidateAll();
        paths.invalidateAll();
        access.forget(null);
    }

    /** Drops one date (the data-load signal of an input of that date): its next request loads it again. */
    void reload(LocalDate d) {
        retire(dates.remove(d));
        refused.remove(d);
        drop(d);
    }

    List<Map<String, Object>> loaded() {
        List<Map<String, Object>> out = new ArrayList<>();
        dates.forEach((d, f) -> {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                ExposureDate xd = f.join();
                ExposureBook b = xd.book();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("businessDate", d.toString());
                m.put("runId", b.runId());
                m.put("paths", b.paths());
                m.put("gridDates", b.grid().size());
                m.put("primaryDates", b.grid().primaryCount());
                m.put("nettingSets", b.sets().size());
                m.put("trades", b.trades().size());
                m.put("tier", b.tier());
                m.put("matrixBytes", b.matrixBytes());
                if (b.hot() != null) {
                    m.put("hotFile", loader.tier().root().relativize(b.hot().file()).toString().replace('\\', '/'));
                }
                m.put("loadMs", xd.loadMs());
                m.put("version", xd.version());
                m.put("intake", xd.intake());
                out.add(m);
            }
        });
        refused.forEach((d, r) -> out.add(Map.of("businessDate", d.toString(), "refused", r[1])));
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------------- scopes

    /** The trades' paths of a date, from the lake (cached per date and set of trades). */
    ExposureBook.TradePaths paths(ExposureDate d) {
        return ids -> {
            List<String> sorted = new ArrayList<>(new java.util.TreeSet<>(ids));
            String key = pathsKey(d.date(), d.version(), sorted);
            // the lake read runs outside Caffeine's compute (a synchronous get would pin a virtual thread's carrier on Java 21)
            return pathLoads.get(key, paths::getIfPresent, k -> loader.tradePaths(d, sorted), paths::put);
        };
    }

    /**
     * The trade-paths cache key of a set of trades: the date, its data version, a SHA-256 digest of the sorted ids and their count. A
     * 32-bit hash of the ids (as before 2.9.7) lets two different sets with the same size share an answer.
     */
    static String pathsKey(LocalDate date, String version, List<String> sorted) {
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (String id : sorted) {
            md.update(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            md.update((byte) 0);                                         // no id holds NUL: "A","BC" never digests as "AB","C"
        }
        return date + "|" + version + "|" + java.util.HexFormat.of().formatHex(md.digest()) + "|" + sorted.size();
    }

    ExposureCalc.Params params(Ask a, boolean server) {
        return new ExposureCalc.Params(a.q(), ExposureMath.Model.of(a.collateral()), props.rule(), props.confidences(), props.contributionWindow(),
                server ? 0 : props.maxScopeNettingSets());
    }

    /** Resolves and computes a scope as the viewer (cached); a null viewer is the server (no interactive limit). */
    Scoped scoped(Principal p, String scope, LocalDate asked, Ask ask) {
        long t0 = System.nanoTime();
        ExposureDate d = date(asked, ask.strict());
        if (!ask.run().isEmpty() && !ask.run().equals(d.book().runId())) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, "the run " + ask.run() + " is not loaded for " + d.date() + ": the date's "
                    + "newest complete run " + d.book().runId() + " answers (leave run empty)");
        }
        ExposureAccess.View view = access.view(p, d);
        ExposureScopes.Resolution res;
        try {
            res = new ExposureScopes(d.book(), g -> Optional.ofNullable(d.groups().get(g)), loader.hierarchySpecs()).resolve(scope);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BAD_PARAMETER, e.getMessage());
        }
        noPaths(d, res, scope);
        java.util.Set<String> words = loader.words();
        boolean naming = com.ash.drishti.compute.var.ScopeExpr.parse(scope, words).refs().stream()
                .anyMatch(r -> NAMING.contains(r.word()) || words.contains(r.word()));        // a column hierarchy's node names trades as DESK: does
        if (naming && !view.seesTrades(res.trades().and(view.visible()))) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "a scope of trades, desks, books or a rule may only name the netting sets you see trade "
                    + "by trade; your role sees some of these only in aggregate");
        }
        String key = d.date() + "|" + d.version() + "|" + view.signature() + "|" + access.masked(p, "NAME") + "|" + res.canonical() + "|" + ask.q()
                + "|" + ask.collateral() + "|" + res.trades().digest();
        Scoped hit = cache.getIfPresent(key);
        if (hit != null) {
            return new Scoped(d, hit.view(), hit.res(), hit.result(), hit.hiddenTrades(), hit.hiddenSets(), hit.kind(), hit.label(), ask,
                    (System.nanoTime() - t0) / 1_000_000, true);
        }
        OrdinalSet set = view.full() ? res.trades() : res.trades().and(view.visible());
        int hiddenTrades = res.trades().size() - set.size();
        int[] hiddenSets = {0};
        if (!view.full()) {
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            res.trades().forEach(t -> {
                int s = d.book().trade(t).set();
                if (seen.add(s) && !view.seesSet(s)) {
                    hiddenSets[0]++;
                }
            });
        }
        ExposureCalc.Result r;
        try {
            r = ExposureCalc.evaluate(d.book(), set, res.standalone(), params(ask, p == null), paths(d));
        } catch (ExposureCalc.TooLarge e) {
            throw new DrishtiException(ErrorCode.EXPOSURE_BATCH_JOB, e.getMessage() + ": its official figures are computed by the end-of-day capture "
                    + "(GET /api/v1/exposure/official), or narrow the scope");
        }
        if (!d.warnings().isEmpty() && !r.flags.contains("stale-run")) {
            r.flags.add("stale-run");
        }
        Scoped s = new Scoped(d, view, res, r, hiddenTrades, hiddenSets[0], res.kind(), label(d, res, p), ask, (System.nanoTime() - t0) / 1_000_000,
                false);
        cache.put(key, s);
        return s;
    }

    /** A netting set the reference data knows that has no paths on the date: said so, never answered with stale numbers. */
    private static void noPaths(ExposureDate d, ExposureScopes.Resolution res, String scope) {
        if (!res.trades().isEmpty()) {
            return;
        }
        for (String u : res.unknown()) {
            String id = u.toUpperCase(Locale.ROOT);
            String known = d.known().containsKey(id) ? id : d.known().containsKey("NS-" + id) ? "NS-" + id : null;
            if (known != null) {
                throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no exposure paths for the netting set " + known + " on " + d.date()
                        + " (the run " + d.book().runId() + " does not cover it)");
            }
        }
        if (scope.trim().toUpperCase(Locale.ROOT).startsWith("CPTY:")) {
            String c = scope.trim().substring(5).trim().toUpperCase(Locale.ROOT);
            boolean has = d.known().values().stream().anyMatch(k -> c.equals(k.counterparty()) || ("CP-" + c).equals(k.counterparty()));
            if (has) {
                throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no exposure paths for the counterparty " + c + " on " + d.date()
                        + " (the run " + d.book().runId() + " covers none of its netting sets)");
            }
        }
        if (!res.unknown().isEmpty()) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "the scope " + scope + " names what " + d.date() + " does not hold: "
                    + String.join(", ", res.unknown()));
        }
    }

    /** A counterparty's name for the viewer (its id when the name is masked). */
    String name(ExposureDate d, String counterparty, Principal p) {
        if (counterparty == null) {
            return null;
        }
        String n = d.names().get(counterparty);
        return n == null || access.masked(p, "NAME") ? counterparty : n;
    }

    private String label(ExposureDate d, ExposureScopes.Resolution res, Principal p) {
        String id = res.id();
        return switch (res.kind()) {
            case "netting-set" -> {
                ExposureBook.SetInfo s = d.book().set(d.book().setOrdinal(id));
                yield s.id() + " · " + name(d, s.counterparty(), p);
            }
            case "counterparty" -> name(d, id, p);
            case "group" -> id + " · " + d.groups().getOrDefault(id, List.of()).size() + " counterparties";
            case "bank" -> "Bank";
            case "trade" -> {
                int t = res.trades().isEmpty() ? -1 : res.trades().first();
                yield t < 0 ? res.canonical() : d.book().trade(t).id() + (d.book().trade(t).product() == null ? "" : " · " + d.book().trade(t).product());
            }
            default -> id != null ? id : res.canonical();
        };
    }

    void audit(Principal p, String action, Scoped s, String extra) {
        if (audit == null) {
            return;
        }
        try {
            ExposureMath.Stats st = s.result().stats;
            int qa = st.at(s.ask().q());
            audit.record(p == null ? ExposureOfficialStore.OWNER : p.user(), action, s.res().canonical(), String.format(Locale.ROOT,
                    "date=%s run=%s version=%s view=%s q=%s collateral=%s epe=%.0f peakPfe=%.0f sets=%d trades=%d hiddenSets=%d ms=%d%s", s.date().date(),
                    s.date().book().runId(), s.date().version(), s.view().full() ? "all" : "restricted", s.ask().q(), s.ask().collateral(), st.epe,
                    qa < 0 ? 0 : st.peakPfe[qa], s.result().pieces.size(), s.result().trades.size(), s.hiddenSets(), s.computeMs(),
                    extra == null ? "" : " " + extra));
        } catch (RuntimeException e) {
            // best effort for reads
        }
    }

    void audit(String user, String action, String target, String detail) {
        if (audit == null) {
            return;
        }
        try {
            audit.record(user, action, target, detail);
        } catch (RuntimeException e) {
            // best effort
        }
    }

    @Override
    public void close() {
        loader.tier().close();
    }
}
