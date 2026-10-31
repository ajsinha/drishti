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
import com.ash.drishti.compute.var.CurrencyBook;
import com.ash.drishti.compute.var.ScenarioFx;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.vector.ParallelSums;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shard books this member holds (VAR_SHARDING.md section 5): each one shard of a date's map, built from the lake for the shard's key
 * range only (never copied from a peer) into its own hot file under {@code <hot>/<date>-shard-<i>/} and mapped, in USD; a reporting
 * currency's shard book built from it when first asked ({@code <date>-<CCY>-shard-<i>/}). A shard book is the morning run; the day's
 * updates are applied by each member on the date it puts together. Each is known by its date, currency, shard map, index and the digest of
 * the inputs its USD book was built from (its data version): two versions of one shard may be held at once while the members move from the
 * older to the newer. A book is built once however many requests ask for it at the same time; a failed build is tried again by the next.
 */
final class VarShardBooks {

    /** One shard book: its date, currency, the digest of the date's shard map, its index in it, and its USD book's inputs digest. */
    record Key(LocalDate date, String ccy, String map, int index, String digest) implements Comparable<Key> {
        @Override
        public int compareTo(Key o) {
            int c = date.compareTo(o.date);
            c = c != 0 ? c : ccy.compareTo(o.ccy);
            c = c != 0 ? c : map.compareTo(o.map);
            c = c != 0 ? c : Integer.compare(index, o.index);
            return c != 0 ? c : digest.compareTo(o.digest);
        }

        /** The same shard and data in USD. */
        Key usd() {
            return new Key(date, VarDate.USD, map, index, digest);
        }

        /** The same book in another currency. */
        Key in(String currency) {
            return new Key(date, currency, map, index, digest);
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final VarLoader loader;
    private final VarProperties props;
    private final ParallelSums sums;
    private final Map<Key, CompletableFuture<VarBook>> books = new ConcurrentHashMap<>();
    private final Map<Key, Long> used = new ConcurrentHashMap<>();
    private final Map<Key, Long> builtAt = new ConcurrentHashMap<>();

    /** Shard books load on a pool of their own ({@code drishti.var.load-threads} workers, 2.9.7): never the common pool. */
    private final java.util.concurrent.ForkJoinPool loads;

    VarShardBooks(VarLoader loader, VarProperties props, ParallelSums sums) {
        this.loader = loader;
        this.props = props;
        this.sums = sums;
        this.loads = com.ash.drishti.server.compute.LoadPools.named("drishti-var-shard-load", props.loadThreads());
    }

    /**
     * The shard's book in {@code ccy} from the data the lake holds now ({@code digest}: the shard's inputs digest now), built or opened from
     * its hot file when not held yet; the caller waits for it.
     */
    VarBook book(VarShardScope scope, String ccy, String digest) {
        Key k = new Key(scope.map().date(), ccy, scope.map().digest(), scope.index(), digest);
        used.put(k, System.currentTimeMillis());
        CompletableFuture<VarBook> f = books.computeIfAbsent(k, x -> CompletableFuture.supplyAsync(() -> VarDate.USD.equals(ccy)
                ? loader.loadShard(scope.map().date(), scope).book() : convert(book(scope, VarDate.USD, digest), ccy, scope), loads));
        try {
            VarBook b = f.join();
            builtAt.putIfAbsent(k, System.currentTimeMillis());
            return b;
        } catch (CompletionException e) {
            books.remove(k, f);                                       // a failed build is tried again by the next request
            if (e.getCause() instanceof DrishtiException de) {
                throw de;
            }
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "shard " + scope.shard().id() + " (" + scope.shard().range() + ") of "
                    + scope.map().date() + " could not be built: " + e.getCause());
        }
    }

    /** The book when this member holds it built already (never builds). */
    Optional<VarBook> held(Key k) {
        CompletableFuture<VarBook> f = books.get(k);
        if (f == null || !f.isDone() || f.isCompletedExceptionally()) {
            return Optional.empty();
        }
        used.put(k, System.currentTimeMillis());
        return Optional.of(f.join());
    }

    /** True when the book is built here. */
    boolean ready(Key k) {
        CompletableFuture<VarBook> f = books.get(k);
        return f != null && f.isDone() && !f.isCompletedExceptionally();
    }

    /** The USD books built here, as this member announces them: date to map to index to the newest data's digest. */
    Map<LocalDate, Map<String, Map<Integer, String>>> readyUsd() {
        Map<LocalDate, Map<String, Map<Integer, String>>> out = new TreeMap<>();
        Map<String, Long> newest = new java.util.HashMap<>();
        for (Key k : keys()) {
            if (!k.ccy().equals(VarDate.USD) || !ready(k)) {
                continue;
            }
            String slot = k.date() + "|" + k.map() + "|" + k.index();
            long at = builtAt.getOrDefault(k, 0L);
            if (newest.containsKey(slot) && newest.get(slot) > at) {
                continue;
            }
            newest.put(slot, at);
            out.computeIfAbsent(k.date(), x -> new TreeMap<>()).computeIfAbsent(k.map(), x -> new TreeMap<>()).put(k.index(),
                    k.digest().substring(0, Math.min(16, k.digest().length())));
        }
        return out;
    }

    /** Every book key held (built or building), in order. */
    List<Key> keys() {
        return new ArrayList<>(new TreeSet<>(books.keySet()));
    }

    /** When a book was last asked for here (built, opened, read by a sum here or for a peer), epoch milliseconds; 0 when never. */
    long lastUsed(Key k) {
        return Math.max(used.getOrDefault(k, 0L), builtAt.getOrDefault(k, 0L));
    }

    /** When a book was built here (epoch milliseconds), 0 when not built. */
    long builtAt(Key k) {
        return builtAt.getOrDefault(k, 0L);
    }

    /**
     * Gives up a book (handed over to other owners, or superseded): unmapped once the requests reading it are done; {@code delete} its hot
     * file too, else the file stays for the next request to map again without a lake read. A USD book's currencies go with it.
     */
    void release(Key k, boolean delete) {
        CompletableFuture<VarBook> f = books.remove(k);
        used.remove(k);
        builtAt.remove(k);
        if (f != null) {
            f.thenAccept(b -> loader.tier().retire(b, delete));
        }
        if (VarDate.USD.equals(k.ccy())) {
            for (Key c : keys()) {
                if (!c.ccy().equals(VarDate.USD) && c.usd().equals(k)) {
                    release(c, delete);
                }
            }
        }
    }

    /** The books held, for the status: key, state, trades, bytes, file. */
    List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Key k : keys()) {
            CompletableFuture<VarBook> f = books.get(k);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("businessDate", k.date().toString());
            m.put("currency", k.ccy());
            m.put("shard", "S" + k.index());
            m.put("map", k.map().substring(0, Math.min(16, k.map().length())));
            m.put("data", k.digest().substring(0, Math.min(16, k.digest().length())));
            if (f == null || !f.isDone()) {
                m.put("state", "building");
            } else if (f.isCompletedExceptionally()) {
                m.put("state", "failed");
            } else {
                VarBook b = f.join();
                m.put("state", "ready");
                m.put("trades", b.tradeCount());
                m.put("positions", b.positionCount());
                m.put("vectorBytes", b.vectorBytes());
                if (b.hot() != null) {
                    m.put("hotFile", loader.tier().root().relativize(b.hot().file()).toString().replace('\\', '/'));
                }
            }
            out.add(m);
        }
        return out;
    }

    /** A reporting currency's shard book from the USD one (VAR_GUIDE.md section 15.10, per shard): each trade converted as one server does. */
    private VarBook convert(VarBook usd, String ccy, VarShardScope scope) {
        LocalDate date = scope.map().date();
        ScenarioFx fx = loader.fx(date, usd.scenarioDates());
        ScenarioFx.Rates rep = fx.rates(ccy).orElseThrow(() -> new DrishtiException(ErrorCode.VAR_NO_FX, "no " + ccy + " FX for the scenarios of "
                + date + "; the currencies with FX on the date: " + String.join(", ", fx.currencies())));
        VarTier tier = loader.tier();
        String digest = VarTier.digest(date, usd.hot().digest() + "|ccy=" + ccy + "|fx=" + loader.fxVersions(date), "", "", "");
        Optional<java.nio.file.Path> hot = tier.existing(date, ccy, scope.index(), digest);
        if (hot.isPresent()) {
            try {
                VarBook b = tier.open(hot.get(), System.nanoTime());
                tier.opened(b);
                return b;
            } catch (RuntimeException e) {
                // built again below
            }
        }
        int withoutPv = 0;
        for (int o = 0; o < usd.tradeCount(); o++) {
            withoutPv += Double.isNaN(usd.trades().number("PV", o)) ? 1 : 0;
        }
        VarBook.Builder b = new VarBook.Builder(date, usd.scenarioSet(), usd.scenarioDates(), props.memoryBudgetMb() * 1024 * 1024,
                tier.target(date, ccy, scope.index(), digest));
        b.batchId(batchOf(usd)).range(usd.hot().range());
        try {
            b.meta(JSON.writeValueAsString(Map.of("currency", ccy, "withoutPv", withoutPv)));
            VarBook out = CurrencyBook.convert(usd, rep, b, sums).book();
            tier.opened(out);
            return out;
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            b.discard();
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    private static String batchOf(VarBook usd) {
        try {
            com.fasterxml.jackson.databind.JsonNode ids = JSON.readTree(usd.hot().meta()).path("intake").path("batchIds");
            return ids.isArray() && !ids.isEmpty() ? ids.get(0).asText() : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Unmaps every shard book (shutdown); the files are kept or deleted as the tier says at its own close. */
    void close() {
        for (Key k : keys()) {
            release(k, false);
        }
    }
}
