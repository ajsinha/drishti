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
import com.ash.drishti.compute.var.CurrencyTotals;
import com.ash.drishti.compute.var.ScenarioFx;
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
 * The VaR engine in the server (VAR_GUIDE.md): the loaded business dates, scope resolution as the viewer, the scope's vector (planned
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

    /**
     * A scope resolved and computed for one viewer on one date; {@code shards} what its sum asked of a sharded date's shards (null: the
     * date is not sharded, VAR_SHARDING.md).
     */
    record Scoped(VarDate date, VarAccess.View view, ScopeResolver.Resolution res, OrdinalSet set, int hidden, double[] vector,
            VarCalc.Measures measures, String kind, String label, long computeMs, boolean cached, Map<String, Object> shards) {}

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
    /** The dates in other reporting currencies, built from the USD date when first asked: {@code <date>|<CCY>|<version>}. */
    private final Map<String, CompletableFuture<VarDate>> inCurrency = new ConcurrentHashMap<>();
    private final VarCurrencies currencies;
    /**
     * The listed currencies built ahead after a date's USD book loads (VAR_GUIDE.md section 15.11): one at a time, on one low-priority
     * thread, so a USD answer never waits for them; a request in a currency not built yet builds it on first ask, as before.
     */
    private final java.util.concurrent.ExecutorService ahead = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "drishti-var-currencies");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /** The dates (and versions) whose listed currencies were queued, and what building each did ({@code <date>|<CCY>}: a sentence). */
    private final java.util.Set<String> aheadQueued = ConcurrentHashMap.newKeySet();
    private final Map<String, String> aheadState = new java.util.concurrent.ConcurrentSkipListMap<>();
    /** Dates that could not be loaded, with the vector version they were refused at and why. */
    private final Map<LocalDate, String[]> refused = new ConcurrentHashMap<>();
    /** VaR sharding across Drishti*net (VAR_SHARDING.md): a date put together from its shards when on; null in tests that do not set it. */
    private volatile VarShards shards;
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
        this.currencies = new VarCurrencies(loader, props, sums);
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

    /** Sets VaR sharding up (it replaces the whole-date load while switched on): its dates dropped and listed through this service. */
    void shards(VarShards s) {
        this.shards = s;
        if (s != null) {
            s.wire(this::reload, () -> {
                java.util.Set<LocalDate> open = new java.util.TreeSet<>();
                dates.forEach((d, f) -> {
                    if (f.isDone() && !f.isCompletedExceptionally()) {
                        open.add(d);
                    }
                });
                return open;
            });
        }
    }

    VarShards shards() {
        return shards;
    }

    /** True while dates are sharded across Drishti*net (the market-risk pack's sharding block, or drishti.var.sharding). */
    boolean sharded() {
        VarShards s = shards;
        return s != null && s.on();
    }

    /** A date's book in a reporting currency: converted on this server, or (sharded) put together from its shards' books in it. */
    private VarDate convert(VarDate usd, String c) {
        if (!sharded() || !usd.book().morning().sharded()) {
            return currencies.convert(usd, c);
        }
        VarDate out = shards.convert(usd, c);
        return usd.intraday() == null || usd.intraday().isEmpty() ? out : currencies.apply(out, usd, usd.intraday().rows());
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
        VarDate vd = load(asked == null || asked.isAfter(latest) ? latest : asked, 0);
        return MORNING.get() ? vd.morning() : vd;
    }

    /** True on a thread computing the official records: every date read without its intraday layer (VAR_GUIDE.md section 8.4). */
    private static final ThreadLocal<Boolean> MORNING = ThreadLocal.withInitial(() -> false);

    /** Runs {@code work} on the morning runs alone (the official captures: never the intraday layer). */
    <T> T morningOnly(java.util.function.Supplier<T> work) {
        boolean was = MORNING.get();
        MORNING.set(true);
        try {
            return work.get();
        } finally {
            MORNING.set(was);
        }
    }

    /**
     * The date a request reads in a reporting currency (VAR_GUIDE.md section 15.10): USD is the date as loaded; another currency is built
     * from it the first time it is asked and kept with it (dropped when the date is). The answered date's warnings come with it.
     */
    VarDate date(LocalDate asked, String ccy) {
        String c = VarCurrencies.normalise(ccy);
        VarDate usd = date(asked);
        if (c.equals(VarDate.USD)) {
            return usd;
        }
        String key = usd.date() + "|" + c + "|" + usd.version();
        CompletableFuture<VarDate> f = inCurrency.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> convert(usd, c)));
        VarDate vd;
        try {
            vd = f.join();
        } catch (java.util.concurrent.CompletionException e) {
            inCurrency.remove(key, f);                                  // asked again, tried again
            if (e.getCause() instanceof DrishtiException de) {
                throw de;
            }
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the VaR data of " + usd.date() + " in " + c + " could not be built: " + e.getCause());
        }
        for (String w : usd.warnings()) {
            vd = vd.warned(w);
        }
        return MORNING.get() ? vd.morning() : vd;
    }

    /**
     * The date a page's link would read, only when it is loaded already (a trade page never waits for a date to load); when it is not,
     * its load starts in the background and the answer is empty.
     */
    Optional<VarDate> loadedDate(LocalDate asked) {
        LocalDate latest = props.enabled() ? loader.latest() : null;
        if (latest == null) {
            return Optional.empty();
        }
        LocalDate d = asked == null || asked.isAfter(latest) ? latest : asked;
        CompletableFuture<VarDate> f = dates.get(d);
        if (f != null && f.isDone() && !f.isCompletedExceptionally()) {
            return Optional.of(f.join());
        }
        if (f == null) {
            CompletableFuture.runAsync(() -> {
                try {
                    date(d);
                } catch (RuntimeException e) {
                    // the page says the date is not loaded; the explorer says why
                }
            });
        }
        return Optional.empty();
    }

    private VarDate load(LocalDate d, int depth) {
        String[] r = refused.get(d);
        if (r != null && r[0].equals(loader.version(d))) {
            return previous(d, r[1], depth);
        }
        refused.remove(d);
        CompletableFuture<VarDate> f = dates.computeIfAbsent(d, x -> CompletableFuture.supplyAsync(() -> sharded() ? shards.assemble(x) : loader.load(x)));
        try {
            VarDate vd = f.join();
            evictOld(d);
            buildAhead(vd);
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

    // ---------------------------------------------------------------------------------------------------------------- currencies

    /**
     * Queues the listed currencies of a date just loaded (once per date and version), when the date is among the newest
     * {@code currency-retention-dates} and the tiered memory is on (a heap date would hold every currency's vectors on the heap: there
     * they stay on first ask). Then the retention: listed-currency files of older dates are deleted.
     */
    private void buildAhead(VarDate usd) {
        if (!loader.tier().mapped() || props.currencies().size() <= 1 || !aheadQueued.add(usd.date() + "|" + usd.version())) {
            return;
        }
        java.util.Set<LocalDate> keep = newest(props.currencyRetentionDates());
        if (!keep.contains(usd.date())) {
            return;
        }
        for (String c : props.currencies()) {
            if (!c.equals(VarDate.USD)) {
                aheadState.putIfAbsent(usd.date() + "|" + c, "queued");
            }
        }
        ahead.submit(() -> {
            for (String c : props.currencies()) {
                if (!c.equals(VarDate.USD) && dates.containsKey(usd.date())) {
                    buildOne(usd, c);
                }
            }
            int pruned = loader.tier().pruneCurrencies(keep, props.currencies());
            if (pruned > 0) {
                org.slf4j.LoggerFactory.getLogger(VarService.class).info("VaR: {} hot files of listed currencies outside the newest {} dates deleted",
                        pruned, keep.size());
            }
        });
    }

    /** One listed currency of a date, built unless a request built (or is building) it; a failure is said in the status, never thrown. */
    private void buildOne(VarDate usd, String c) {
        String key = usd.date() + "|" + c + "|" + usd.version();
        String state = usd.date() + "|" + c;
        CompletableFuture<VarDate> mine = new CompletableFuture<>();
        CompletableFuture<VarDate> there = inCurrency.putIfAbsent(key, mine);
        if (there != null) {
            aheadState.put(state, "built on first ask");
            return;
        }
        long t0 = System.nanoTime();
        aheadState.put(state, "building");
        try {
            VarDate vd = convert(usd, c);
            mine.complete(vd);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            long bytes = vd.book().hot() == null ? 0 : sizeOf(vd.book().hot().file());
            aheadState.put(state, "built ahead in " + ms + " ms, " + bytes + " bytes");
            org.slf4j.LoggerFactory.getLogger(VarService.class).info("VaR: {} in {} built ahead in {} ms ({} bytes on disk)", usd.date(), c, ms, bytes);
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            inCurrency.remove(key, mine);                               // a request tries again on first ask
            aheadState.put(state, "not built: " + e.getMessage());
        }
    }

    private static long sizeOf(java.nio.file.Path f) {
        try {
            return java.nio.file.Files.size(f);
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    /** The newest {@code n} business dates with trades' scenario P&amp;L. */
    private java.util.Set<LocalDate> newest(int n) {
        java.util.Set<LocalDate> out = new java.util.TreeSet<>();
        LocalDate d = loader.latest();
        while (d != null && out.size() < n) {
            out.add(d);
            d = loader.previous(d);
        }
        return out;
    }

    /** What building the listed currencies ahead did, per date and currency, for the status answer. */
    Map<String, String> builtAhead() {
        return Map.copyOf(aheadState);
    }

    /** True when a date's book in a currency is ready (built ahead or on an earlier ask): a request in it does not wait. */
    boolean ready(VarDate usd, String ccy) {
        if (VarDate.USD.equals(ccy)) {
            return true;
        }
        CompletableFuture<VarDate> f = inCurrency.get(usd.date() + "|" + ccy + "|" + usd.version());
        return f != null && f.isDone() && !f.isCompletedExceptionally();
    }

    /** The date's FX against USD for its scenarios (supplied or derived, VAR_GUIDE.md section 15.9). */
    ScenarioFx fx(VarDate usd) {
        return loader.fx(usd.date(), usd.book().scenarioDates());
    }

    /**
     * A set's per-trade-currency totals (VAR_GUIDE.md section 15.11) on a USD date: a node's stored with the date when the set is the node
     * whole, else summed from the set's trades.
     */
    CurrencyTotals.Sums totals(VarDate usd, OrdinalSet set, String node) {
        CurrencyTotals t = usd.book().currencyTotals();
        VarBook.Node n = node == null ? null : usd.book().node(node);
        if (t != null && n != null && set.equals(n.members()) && t.get(node) != null) {
            return t.get(node);
        }
        return CurrencyTotals.of(usd.book(), set, fx(usd), sums);
    }

    /**
     * The vector of a set in a reporting currency other than USD, from the per-trade-currency totals of the USD book (exact, and cheap for a
     * node); the converted book's own sum when the date's FX lacks a currency the totals need.
     */
    private double[] inCurrencyVector(VarDate d, OrdinalSet set, ScopeResolver.Resolution res) {
        VarDate usd = load(d.date(), 0);
        usd = MORNING.get() ? usd.morning() : usd;
        if (!usd.date().equals(d.date())) {
            return vector(d, set, res);
        }
        double[] v = totals(usd, set, res.node()).in(fx(usd), d.currency());
        return v != null ? v : vector(d, set, res);
    }

    // ---------------------------------------------------------------------------------------------------------------- intraday

    private final Map<LocalDate, java.util.concurrent.locks.ReentrantLock> intradayLocks = new ConcurrentHashMap<>();

    /** Intraday updates refused before they reached the lake (the PV topic's guards): listed with the date's layer. */
    void intradayRejected(LocalDate d, List<String> why) {
        CompletableFuture<VarDate> f = dates.get(d);
        if (f == null || !f.isDone() || f.isCompletedExceptionally() || why.isEmpty()) {
            return;
        }
        java.util.concurrent.locks.ReentrantLock lock = intradayLocks.computeIfAbsent(d, x -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();                                                     // no blocking under synchronized (virtual threads)
        try {
            VarDate vd = dates.get(d).join();
            VarIntradayRows rows = vd.intraday() == null ? new VarIntradayRows().morning(null, vd.intake().batchIds()) : vd.intraday().copy();
            rows.rejected(why);
            dates.put(d, CompletableFuture.completedFuture(vd.withBook(vd.book(), rows)));
        } finally {
            lock.unlock();
        }
    }

    /** True when a loaded date has intraday batches (applied or met). */
    boolean hasIntraday(LocalDate d) {
        CompletableFuture<VarDate> f = dates.get(d);
        return f != null && f.isDone() && !f.isCompletedExceptionally() && f.join().intraday() != null && !f.join().intraday().batches().isEmpty();
    }

    /** What an intraday signal did: {@code applied}, {@code none} (nothing new), {@code reload} (a full run: the date reloaded), {@code not loaded}. */
    record IntradayResult(String status, int updates, int trades, java.util.List<String> batches, String note) {}

    /**
     * The data-load signal of a trades' input (VAR_GUIDE.md section 8.4): on a loaded date, its new intraday batches are applied on the
     * book in place (each updated trade's difference into its book and nodes, its reporting currencies, the totals; nothing else
     * recomputed); a full run (no new intraday batch, another morning batch, another morning {@code COMPUTED_AT}, an intraday batch
     * gone) reloads the date as before. A date not loaded is left to load with its updates.
     */
    IntradayResult intraday(LocalDate d) {
        CompletableFuture<VarDate> f = dates.get(d);
        if (f == null || !f.isDone() || f.isCompletedExceptionally()) {
            return new IntradayResult("not loaded", 0, 0, java.util.List.of(), "the date loads with its updates when first asked");
        }
        java.util.concurrent.locks.ReentrantLock lock = intradayLocks.computeIfAbsent(d, x -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock();                                                     // no blocking under synchronized (virtual threads)
        try {
            VarDate usd = dates.get(d).join();
            VarIntradayRows state = usd.intraday() == null ? new VarIntradayRows().morning(null, usd.intake().batchIds()) : usd.intraday();
            VarIntake read = loader.readIntraday(d, usd.book().morning(), state.parts(), usd.intake());
            java.util.Set<String> morning = new java.util.TreeSet<>(read.summary().batchIds());
            // new batch parts (a batch's scenario PVs and P&L vectors may land as two loads, each its own signal)
            java.util.Set<String> fresh0 = new java.util.TreeSet<>(read.intradayParts());
            fresh0.removeAll(state.parts());
            // no new intraday batch: the signal is a full run (a restatement with the same batch id included), as before 2.9.4
            boolean restated = fresh0.isEmpty() || !morning.equals(new java.util.TreeSet<>(usd.intake().batchIds()))
                    || state.morningAt() != null && read.morningAt() != null && !state.morningAt().equals(read.morningAt())
                    || !read.intradayParts().containsAll(state.parts());
            if (restated) {
                reload(d);
                return new IntradayResult("reload", 0, 0, java.util.List.of(), "a full run of the date: reloaded, its intraday layer dropped");
            }
            VarIntradayRows next = state.copy();
            if (next.morningAt() == null) {
                next.morning(read.morningAt(), usd.intake().batchIds());
            }
            next.met(read.intradayBatches());
            next.metParts(read.intradayParts());
            next.rejected(read.intradayRejections());
            java.util.List<VarIntradayRows.Row> fresh = new java.util.ArrayList<>();
            for (VarIntradayRows.Row r : read.intraday().rows()) {
                if (next.offer(r)) {
                    fresh.add(r);
                }
            }
            java.util.List<String> batches = fresh.stream().map(VarIntradayRows.Row::batchId).distinct().toList();
            if (fresh.isEmpty()) {
                if (!next.parts().equals(state.parts())) {
                    dates.put(d, CompletableFuture.completedFuture(usd.withBook(usd.book(), next)));
                }
                return new IntradayResult("none", 0, 0, batches, "no update newer than what is applied");
            }
            VarBook nb = loader.apply(d, usd.book(), fresh, usd.book().scenarioDates(), usd.intake());
            VarDate nvd = usd.withBook(nb, next);
            dates.put(d, CompletableFuture.completedFuture(nvd));
            for (String k : new ArrayList<>(inCurrency.keySet())) {          // the reporting currencies follow: only those trades reconverted
                if (k.startsWith(d + "|")) {
                    CompletableFuture<VarDate> cf = inCurrency.get(k);
                    if (cf != null && cf.isDone() && !cf.isCompletedExceptionally()) {
                        VarDate cur = cf.join();
                        inCurrency.put(k, CompletableFuture.completedFuture(currencies.apply(cur, nvd, fresh)));
                    }
                }
            }
            cache.asMap().keySet().removeIf(k -> k.startsWith(d + "|"));
            audit(VarOfficialStore.OWNER, "var-intraday", d.toString(), "batches=" + batches + " updates=" + fresh.size());
            return new IntradayResult("applied", fresh.size(), nb.intraday().info().trades(), batches, null);
        } finally {
            lock.unlock();
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
                retireCurrencies(d);
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
            f.thenAccept(vd -> loader.tier().retire(vd.book().morning(), stale));      // the book the tier opened (a layer shares its file)
        }
    }

    /** The date's books in other currencies leave memory with it (or with a new version of its inputs). */
    private void retireCurrencies(LocalDate d) {
        for (String k : new ArrayList<>(inCurrency.keySet())) {
            if (d == null || k.startsWith(d + "|")) {
                retire(inCurrency.remove(k), false);
            }
        }
    }

    /** Drops every loaded date (an administrator's reload). */
    void reload() {
        aheadQueued.clear();
        new ArrayList<>(dates.keySet()).forEach(d -> retire(dates.remove(d), false));
        retireCurrencies(null);
        refused.clear();
        cache.invalidateAll();
    }

    /**
     * Drops one date (the data-load signal of an input of that date): its next request loads it again. Its hot file goes with it once
     * unused when the inputs changed (a restatement); unchanged inputs open the same file again.
     */
    void reload(LocalDate d) {
        if (shards != null) {
            shards.forget(d);
        }
        aheadQueued.removeIf(k -> k.startsWith(d + "|"));
        retire(dates.remove(d), false);
        retireCurrencies(d);
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
                m.put("inputsBySystem", vd.intake().inputs() == null ? Map.of() : vd.intake().inputs());   // per source system: form, grain (15.13)
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
                m.put("intraday", VarViews.intradayNote(vd));
                out.add(m);
            }
        });
        inCurrency.forEach((k, f) -> {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                VarDate vd = f.join();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("businessDate", vd.date().toString());
                m.put("currency", vd.currency());
                m.put("trades", vd.book().tradeCount());
                m.put("tier", vd.book().tier());
                if (vd.book().hot() != null) {
                    m.put("hotFile", loader.tier().root().relativize(vd.book().hot().file()).toString().replace('\\', '/'));
                }
                m.put("loadMs", vd.loadMs());
                m.put("fx", vd.fx());
                out.add(m);
            }
        });
        refused.forEach((d, r) -> out.add(Map.of("businessDate", d.toString(), "refused", r[1])));
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------------- scopes

    /** The configured hierarchies' scope words (2.9.5): word to hierarchy id; none when the configuration cannot be read. */
    Map<String, String> words() {
        try {
            return loader.hierarchies().words();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** The hierarchies configured now (the setting, a pack's block, or the default). */
    Hierarchies hierarchies() {
        return loader.hierarchies();
    }

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
            Map<?, ?> cells = m.get("cells") instanceof Map<?, ?> cc ? cc : Map.of();
            if (books instanceof List<?> l) {
                for (Object book : l) {
                    if (byBook.get(String.valueOf(book)) instanceof Map<?, ?> positions) {
                        positions.values().forEach(ts -> {
                            if (ts instanceof List<?> tl) {
                                tl.forEach(t -> out.add(String.valueOf(t)));
                            }
                        });
                    } else if (cells.get(String.valueOf(book)) instanceof Map<?, ?> cell && cell.get("positions") instanceof List<?> ps
                            && byBook.get(String.valueOf(cell.get("book"))) instanceof Map<?, ?> positions) {   // a cell (2.9.5): its positions
                        for (Object pid : ps) {
                            if (positions.get(String.valueOf(pid)) instanceof List<?> tl) {
                                tl.forEach(t -> out.add(String.valueOf(t)));
                            }
                        }
                    }
                }
            }
            return (List<String>) out;
        }).orElse(List.of());
    }

    /** Resolves and computes a scope as the viewer (cached); a null viewer is the server (every node at positions level). In USD. */
    Scoped scoped(Principal p, String scope, LocalDate asked) {
        return scoped(p, scope, asked, VarDate.USD);
    }

    /** The same in a reporting currency ({@code ccy}: null or blank is USD). */
    Scoped scoped(Principal p, String scope, LocalDate asked, String ccy) {
        long t0 = System.nanoTime();
        VarDate d = date(asked, ccy);
        VarAccess.View view = access.view(p, d);
        ScopeResolver.Resolution res;
        ScopeExpr parsed;
        try {
            Map<String, String> words = words();
            parsed = ScopeExpr.parse(scope, words.keySet());
            res = new ScopeResolver(d.book(), definitions(p, d), words).resolve(scope);
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
        String key = d.date() + "|" + d.currency() + "|" + d.version() + "|i" + d.intradayStamp() + "|" + view.signature() + "|" + res.canonical() + "|" + res.set().digest();
        Scoped hit = cache.getIfPresent(key);
        if (hit != null) {
            return new Scoped(d, hit.view(), hit.res(), hit.set(), hit.hidden(), hit.vector(), hit.measures(), hit.kind(), hit.label(),
                    (System.nanoTime() - t0) / 1_000_000, true, hit.shards());
        }
        OrdinalSet set = view.full() ? res.set() : res.set().and(view.visible());
        int hidden = res.set().size() - set.size();
        VarDate dd = d;
        VarShardRows.Stats st = d.book().morning().sharded() ? new VarShardRows.Stats() : null;
        double[] v = st == null ? (VarDate.USD.equals(d.currency()) ? vector(d, set, res) : inCurrencyVector(d, set, res))
                : VarShardRows.counting(st, () -> VarDate.USD.equals(dd.currency()) ? vector(dd, set, res) : inCurrencyVector(dd, set, res));
        VarCalc.Measures m = calc.measures(v);
        String kind = kindOf(res, parsed);
        Scoped s = new Scoped(d, view, res, set, hidden, v, m, kind, label(d, res, parsed, kind, p), (System.nanoTime() - t0) / 1_000_000, false,
                st == null ? null : shardNote(d, st));
        cache.put(key, s);
        return s;
    }

    /** What a sharded answer asked of the shards (its provenance): the date's map, the shards asked and who answered. */
    private Map<String, Object> shardNote(VarDate d, VarShardRows.Stats st) {
        Map<String, Object> m = new LinkedHashMap<>(st.describe());
        if (d.book().morning().store() instanceof com.ash.drishti.compute.vector.ShardedVectors sv) {
            m.put("shards", sv.parts().size());
            m.put("heldHere", sv.parts().stream().filter(com.ash.drishti.compute.vector.ShardedVectors.Part::held).count());
        }
        m.put("node", shards == null ? "" : shards.peers().self());
        if (st.shards.isEmpty()) {
            m.put("note", "a node's figures: its aggregate, held on every member");
        }
        return m;
    }

    /** A stored portfolio of type BASKET (named positions and trades): probing it is naming them (VAR_GUIDE.md section 5.3). */
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

    /** One audit entry per answer delivered (VAR_GUIDE.md section 9): who, what scope, which date and view, the result, the time. */
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
        if (shards != null) {
            shards.close();
        }
        ahead.shutdownNow();
        loader.tier().close();
        sums.close();
    }
}
