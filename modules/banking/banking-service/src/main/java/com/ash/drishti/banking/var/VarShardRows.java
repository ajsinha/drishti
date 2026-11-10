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
import com.ash.drishti.banking.compute.var.ShardAssembly;
import com.ash.drishti.compute.shard.ShardMap;
import com.ash.drishti.banking.compute.var.ShardSkeleton;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ShardedVectors;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.net.NetCaller;
import com.ash.drishti.kernel.api.security.Principal;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * A sharded date's rows on other members (DATA_ARCHITECTURE.md section 13): the coordinator's side of a partial sum and of a skeleton. A shard's
 * part of a set goes to one member, chosen in this order: its owners that have announced it ready (first owner first), then any other
 * member that announced it ready (an old owner during a handover), then its owners not ready yet (they build it from the lake when
 * asked, when the lake still holds that data). Readiness is per data version: a member counts only when it announced the shard at the data
 * the date was put together from. The answer must be labelled with the shard, the date's shard map and the shard's data version; one
 * labelled otherwise, or refused as stale, sends the request to the next member. When none answers, the scope's figures are refused
 * ({@code DRS-5215}), never given from the other shards alone. A partial is asked as the caller (their on-behalf-of token), so the owner
 * applies their rights too.
 */
final class VarShardRows {

    /** What one answer asked of the shards, for its provenance. */
    static final class Stats {
        final AtomicInteger remote = new AtomicInteger();
        final AtomicInteger local = new AtomicInteger();
        final AtomicInteger replicas = new AtomicInteger();
        final Set<String> nodes = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Set<Integer> shards = java.util.concurrent.ConcurrentHashMap.newKeySet();

        Map<String, Object> describe() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shardsAsked", shards.size());
            m.put("answeredHere", local.get());
            m.put("answeredByOwners", remote.get());
            m.put("answeredByReplicas", replicas.get());
            m.put("nodes", nodes.stream().sorted().toList());
            return m;
        }
    }

    /**
     * The asking thread's caller and counters, carried to the threads that ask the owners, and the one deadline ({@link System#nanoTime})
     * every call made for it keeps: {@code drishti.var.sharding.call-timeout-seconds} from when it was taken.
     */
    record Ctx(Principal principal, Stats stats, long deadline) {}

    private static final ThreadLocal<Stats> STATS = new ThreadLocal<>();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final ExecutorService ask = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("drishti-var-shard-", 0).factory());
    private final VarShards shards;

    VarShardRows(VarShards shards) {
        this.shards = shards;
    }

    /** Runs {@code work} counting what it asks of the shards into {@code s}. */
    static <T> T counting(Stats s, Supplier<T> work) {
        Stats before = STATS.get();
        STATS.set(s);
        try {
            return work.get();
        } finally {
            STATS.set(before);
        }
    }

    ExecutorService ask() {
        return ask;
    }

    /** The rows of a sharded date's shards held elsewhere, in {@code ccy}: each shard's digest as the date was put together from it. */
    ShardedVectors.Remote of(LocalDate date, String ccy, ShardMap map, String[] digests) {
        return new ShardedVectors.Remote() {
            @Override
            public double[] sum(int shard, OrdinalSet rows, Object context) {
                return VarShardRows.this.sum(date, ccy, map, shard, digests[shard], rows, context instanceof Ctx c ? c : null);
            }

            @Override
            public List<double[]> sumAll(int shard, List<OrdinalSet> sets, Object context) {
                return VarShardRows.this.sumAll(date, ccy, map, shard, digests[shard], sets, context instanceof Ctx c ? c : null);
            }

            @Override
            public void held(int shard, Object context) {
                if (context instanceof Ctx c && c.stats() != null) {
                    c.stats().shards.add(shard);
                    count(c.stats(), shards.peers().self(), false, true);
                }
            }

            @Override
            public Object context() {
                return new Ctx(NetCaller.current().map(NetCaller.Caller::principal).orElse(null), STATS.get(), deadline());
            }
        };
    }

    /** The members to ask for a shard at data {@code digest}, in order (see the class). */
    List<String> candidates(LocalDate date, ShardMap map, int index, String digest) {
        List<String> owners = shards.owners(map.shard(index));
        Map<String, com.ash.drishti.server.shard.ShardPeers.Peer> byNode = new TreeMap<>();
        shards.members().forEach(p -> byNode.put(p.node(), p));
        Set<String> out = new LinkedHashSet<>();
        for (String n : owners) {
            com.ash.drishti.server.shard.ShardPeers.Peer p = byNode.get(n);
            if (p != null && p.routable() && p.state().ready(date, map.digest(), index, digest)) {
                out.add(n);
            }
        }
        byNode.values().stream().filter(p -> p.routable() && p.state().ready(date, map.digest(), index, digest)).map(com.ash.drishti.server.shard.ShardPeers.Peer::node)
                .forEach(out::add);
        for (String n : owners) {
            com.ash.drishti.server.shard.ShardPeers.Peer p = byNode.get(n);
            if (p != null && p.eligible()) {
                out.add(n);
            }
        }
        return new ArrayList<>(out);
    }

    private double[] sum(LocalDate date, String ccy, ShardMap map, int index, String digest, OrdinalSet rows, Ctx ctx) {
        return sumAll(date, ccy, map, index, digest, List.of(rows), ctx).get(0);
    }

    /** A deadline {@code call-timeout-seconds} from now. */
    private long deadline() {
        return System.nanoTime() + Duration.ofSeconds(shards.own().callTimeoutSeconds()).toNanos();
    }

    /**
     * The sums of shard {@code index}'s row sets {@code sets}, in order, from one member: here when it holds the shard, else in one call
     * ({@code /partial} for one set, {@code /partials} for more). Each member in turn gets a share of the time left before the context's
     * deadline (the first owner half when a replica follows), so a stalled owner leaves time for its replica and a shard nobody answers
     * is refused ({@code DRS-5215}) inside the deadline, never after the caller has given up.
     */
    private List<double[]> sumAll(LocalDate date, String ccy, ShardMap map, int index, String digest, List<OrdinalSet> sets, Ctx ctx) {
        Stats st = ctx == null ? null : ctx.stats();
        if (st != null) {
            st.shards.add(index);
        }
        VarShardBooks.Key k = new VarShardBooks.Key(date, ccy, map.digest(), index, digest);
        java.util.Optional<VarBook> here = shards.books().held(k);
        if (here.isPresent()) {
            count(st, shards.peers().self(), false, true);
            return sumHere(here.get(), sets);
        }
        List<String> order = candidates(date, map, index, digest);
        List<String> owners = shards.owners(map.shard(index));
        List<String> why = new ArrayList<>();
        int stale = 0;
        boolean one = sets.size() == 1;
        byte[] body = one ? request(date, ccy, map, index, digest, sets.get(0)) : request(date, ccy, map, index, digest, sets);
        long deadline = ctx == null ? deadline() : ctx.deadline();
        int remotes = (int) order.stream().filter(n -> !n.equals(shards.peers().self())).count();
        for (String node : order) {
            boolean replica = owners.isEmpty() || !owners.get(0).equals(node);
            if (node.equals(shards.peers().self())) {
                try {
                    VarBook b = shards.served(date, ccy, index, map.digest(), digest);
                    count(st, node, replica, true);
                    return sumHere(b, sets);
                } catch (DrishtiException e) {
                    stale += e.errorCode() == ErrorCode.VAR_SHARD_STALE ? 1 : 0;
                    why.add(node + ": " + e.getMessage());
                    continue;
                }
            }
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                why.add(node + ": not asked, the call's " + shards.own().callTimeoutSeconds() + " s were spent");
                remotes--;
                continue;
            }
            Duration timeout = Duration.ofNanos(Math.max(left / Math.max(1, remotes), Math.min(left, MIN_CALL_NANOS)));
            remotes--;
            try {
                PeerHttp.Answer a = shards.peers().call(node, "POST", one ? "/api/v1/risk/var/partial" : "/api/v1/risk/var/partials", body,
                        ctx == null ? null : ctx.principal(), timeout);
                if (a.status() == 200) {
                    List<double[]> v = one ? List.of(answer(a.body(), map, index, digest, sets.get(0).size()))
                            : answers(a.body(), map, index, digest, sets);
                    count(st, node, replica, false);
                    return v;
                }
                String detail = PeerHttp.brief(a.text());
                stale += a.status() == 409 ? 1 : 0;
                why.add(node + ": HTTP " + a.status() + " " + detail);
            } catch (IOException e) {
                why.add(node + ": cannot be reached (" + e.getMessage() + ")");
            } catch (IllegalStateException e) {
                why.add(node + ": " + e.getMessage());
            }
        }
        throw refused(date, map, index, why, stale);
    }

    /** The least time a member is given while the deadline allows it: two seconds. */
    private static final long MIN_CALL_NANOS = Duration.ofSeconds(2).toNanos();

    private List<double[]> sumHere(VarBook b, List<OrdinalSet> sets) {
        List<double[]> out = new ArrayList<>(sets.size());
        for (OrdinalSet r : sets) {
            out.add(shards.sums().sum(b.store(), r));
        }
        return out;
    }

    private DrishtiException refused(LocalDate date, ShardMap map, int index, List<String> why, int stale) {
        ShardMap.Shard s = map.shard(index);
        if (why.isEmpty()) {
            why.add("no member that may own shards is alive");
        }
        if (stale > 0 && stale == why.size()) {
            shards.dropLater(date);                                    // this member's date is the stale one: put together again
            return new DrishtiException(ErrorCode.VAR_SHARD_STALE, "shard " + s.id() + " (" + s.range() + ") of " + date + " is held at another "
                    + "data version on every owner (" + String.join("; ", why) + "); this server reads the date again: ask again in a moment");
        }
        return new DrishtiException(ErrorCode.VAR_SHARD_UNAVAILABLE, "shard " + s.id() + " (" + s.range() + ") of " + date + " has no live owner "
                + "that holds it (" + String.join("; ", why) + "): no partial VaR is given; it answers again once an owner is back or the "
                + "shard has new owners (drishti.var.sharding.stable-seconds)");
    }

    private static void count(Stats st, String node, boolean replica, boolean local) {
        if (st == null) {
            return;
        }
        st.nodes.add(node);
        (local ? st.local : st.remote).incrementAndGet();
        if (replica) {
            st.replicas.incrementAndGet();
        }
    }

    static String digestOf(VarBook b) {
        return b.hot() == null ? "" : b.hot().digest();
    }

    // ------------------------------------------------------------------------------------------------------- the wire format

    /** A partial's request: the date, currency, shard, map and shard digests, and the rows as a bitmap's words (little-endian, base64). */
    static byte[] request(LocalDate date, String ccy, ShardMap map, int index, String digest, OrdinalSet rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date.toString());
        m.put("ccy", ccy);
        m.put("shard", index);
        m.put("map", map.digest());
        m.put("digest", digest);
        long[] w = rows.words();
        ByteBuffer b = ByteBuffer.allocate(w.length * Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.asLongBuffer().put(w);
        m.put("rows", Base64.getEncoder().encodeToString(b.array()));
        try {
            return JSON.writeValueAsBytes(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A batch of partials' request ({@code /partials}): as {@link #request} with {@code runs}, each set as its runs. */
    static byte[] request(LocalDate date, String ccy, ShardMap map, int index, String digest, List<OrdinalSet> sets) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date.toString());
        m.put("ccy", ccy);
        m.put("shard", index);
        m.put("map", map.digest());
        m.put("digest", digest);
        m.put("runs", sets.stream().map(VarShardRows::runs).toList());
        try {
            return JSON.writeValueAsBytes(m);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A set as its runs {@code [from, to)}: int32 pairs, little-endian, base64 (a position's run is 8 bytes, not a bitmap's words). */
    static String runs(OrdinalSet rows) {
        List<int[]> rs = new ArrayList<>();
        rows.forEachRun((a, b) -> rs.add(new int[] {a, b}));
        ByteBuffer b = ByteBuffer.allocate(rs.size() * 2 * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        rs.forEach(r -> b.putInt(r[0]).putInt(r[1]));
        return Base64.getEncoder().encodeToString(b.array());
    }

    /**
     * The rows of a batch's set, from its runs: the same bitmap {@code /partial}'s {@link #rowsOf} reads (so each sum is the one {@code
     * /partial} gives). Runs out of order, empty or beyond {@code limit} rows are refused.
     */
    static OrdinalSet rowsOfRuns(String base64, int limit) {
        ByteBuffer b = ByteBuffer.wrap(Base64.getDecoder().decode(base64)).order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() % (2 * Integer.BYTES) != 0) {
            throw new IllegalArgumentException("a set's runs are int32 pairs");
        }
        int n = b.remaining() / (2 * Integer.BYTES);
        int[] runs = new int[2 * n];
        int last = 0;
        for (int i = 0; i < n; i++) {
            int from = b.getInt();
            int to = b.getInt();
            if (from < last || to <= from || to > limit) {
                throw new IllegalArgumentException("run [" + from + ", " + to + ") is out of order, empty or beyond the shard's " + limit + " rows");
            }
            runs[2 * i] = from;
            runs[2 * i + 1] = to;
            last = to;
        }
        long[] w = new long[(last + 63) / 64];
        for (int i = 0; i < n; i++) {
            for (int o = runs[2 * i]; o < runs[2 * i + 1]; o++) {
                w[o >>> 6] |= 1L << (o & 63);
            }
        }
        return OrdinalSet.ofWords(w);
    }

    private static String float64(double[] sum) {
        ByteBuffer b = ByteBuffer.allocate(sum.length * Double.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.asDoubleBuffer().put(sum);
        return Base64.getEncoder().encodeToString(b.array());
    }

    private static double[] float64(String base64) {
        ByteBuffer b = ByteBuffer.wrap(Base64.getDecoder().decode(base64)).order(ByteOrder.LITTLE_ENDIAN);
        double[] v = new double[b.remaining() / Double.BYTES];
        b.asDoubleBuffer().get(v);
        return v;
    }

    /** An owner's answer to a batch: the label, each set's row count and its partial sum (float64, little-endian, base64), in order. */
    static Map<String, Object> answers(int index, String id, String map, String digest, String node, List<OrdinalSet> sets, List<double[]> sums) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shard", index);
        m.put("id", id);
        m.put("map", map);
        m.put("digest", digest);
        m.put("node", node);
        m.put("rows", sets.stream().map(OrdinalSet::size).toList());
        m.put("sums", sums.stream().map(VarShardRows::float64).toList());
        return m;
    }

    /** Reads an owner's answer to a batch, refusing one labelled for another shard, map or data version, or another count or row list. */
    static List<double[]> answers(byte[] body, ShardMap map, int index, String digest, List<OrdinalSet> sets) {
        com.fasterxml.jackson.databind.JsonNode n;
        try {
            n = JSON.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("the answer is not a batch of partial sums: " + e.getMessage());
        }
        com.fasterxml.jackson.databind.JsonNode rows = n.path("rows");
        com.fasterxml.jackson.databind.JsonNode sums = n.path("sums");
        boolean rowsMatch = rows.isArray() && rows.size() == sets.size();
        for (int i = 0; rowsMatch && i < sets.size(); i++) {
            rowsMatch = rows.get(i).asInt(-1) == sets.get(i).size();
        }
        if (n.path("shard").asInt(-1) != index || !map.digest().equals(n.path("map").asText()) || !digest.equals(n.path("digest").asText())
                || !rowsMatch || !sums.isArray() || sums.size() != sets.size()) {
            throw new IllegalStateException("the partial sums are labelled S" + n.path("shard").asText() + " of map "
                    + n.path("map").asText().substring(0, Math.min(16, n.path("map").asText().length())) + " at data version "
                    + n.path("digest").asText().substring(0, Math.min(16, n.path("digest").asText().length())) + " for " + sums.size()
                    + " sets; S" + index + " for " + sets.size() + " sets was asked: refused");
        }
        List<double[]> out = new ArrayList<>(sets.size());
        for (com.fasterxml.jackson.databind.JsonNode v : sums) {
            out.add(float64(v.asText()));
        }
        return out;
    }

    /** The rows of a partial's request. */
    static OrdinalSet rowsOf(String base64) {
        ByteBuffer b = ByteBuffer.wrap(Base64.getDecoder().decode(base64)).order(ByteOrder.LITTLE_ENDIAN);
        long[] w = new long[b.remaining() / Long.BYTES];
        b.asLongBuffer().get(w);
        return OrdinalSet.ofWords(w);
    }

    /** An owner's answer: the label and the partial sum (float64, little-endian, base64). */
    static Map<String, Object> answer(int index, String id, String map, String digest, String node, int rows, double[] sum) {
        ByteBuffer b = ByteBuffer.allocate(sum.length * Double.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        b.asDoubleBuffer().put(sum);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shard", index);
        m.put("id", id);
        m.put("map", map);
        m.put("digest", digest);
        m.put("node", node);
        m.put("rows", rows);
        m.put("sum", Base64.getEncoder().encodeToString(b.array()));
        return m;
    }

    /** Reads an owner's answer, refusing one labelled for another shard, map or data version, or for another number of rows. */
    static double[] answer(byte[] body, ShardMap map, int index, String digest, int rows) {
        com.fasterxml.jackson.databind.JsonNode n;
        try {
            n = JSON.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("the answer is not a partial sum: " + e.getMessage());
        }
        if (n.path("shard").asInt(-1) != index || !map.digest().equals(n.path("map").asText()) || !digest.equals(n.path("digest").asText())
                || n.path("rows").asInt(-1) != rows) {
            throw new IllegalStateException("the partial sum is labelled S" + n.path("shard").asText() + " of map "
                    + n.path("map").asText().substring(0, Math.min(16, n.path("map").asText().length())) + " at data version "
                    + n.path("digest").asText().substring(0, Math.min(16, n.path("digest").asText().length())) + " for " + n.path("rows").asText()
                    + " rows; S" + index + " for " + rows + " rows was asked: refused");
        }
        ByteBuffer b = ByteBuffer.wrap(Base64.getDecoder().decode(n.path("sum").asText())).order(ByteOrder.LITTLE_ENDIAN);
        double[] v = new double[b.remaining() / Double.BYTES];
        b.asDoubleBuffer().get(v);
        return v;
    }

    // ------------------------------------------------------------------------------------------------------- skeletons

    /**
     * A shard this member does not hold, as a part of its date at data {@code digest}: its skeleton, fetched from the first member in order
     * that sends it (an owner builds it when the lake holds that data and it holds no copy yet).
     */
    ShardAssembly.Part skeleton(VarShardScope scope, String ccy, String digest) {
        LocalDate date = scope.map().date();
        int index = scope.index();
        List<String> why = new ArrayList<>();
        int stale = 0;
        String q = "/api/v1/risk/var/shards/skeleton?date=" + date + "&ccy=" + ccy + "&shard=" + index + "&map=" + scope.map().digest()
                + "&digest=" + digest;
        for (String node : candidates(date, scope.map(), index, digest)) {
            if (node.equals(shards.peers().self())) {
                if (shards.leaving(new VarShardBooks.Key(date, ccy, scope.map().digest(), index, digest))) {
                    why.add(node + ": hands the shard over");                // a replacement is put together without its copy here
                    continue;
                }
                try {
                    return ShardAssembly.Part.of(index, shards.served(date, ccy, index, scope.map().digest(), digest));
                } catch (DrishtiException e) {
                    stale += e.errorCode() == ErrorCode.VAR_SHARD_STALE ? 1 : 0;
                    why.add(node + ": " + e.getMessage());
                    continue;
                }
            }
            Path tmp = null;
            try {
                var res = shards.peers().open(node, q, Duration.ofSeconds(shards.own().skeletonTimeoutSeconds()));
                try (InputStream in = res.body()) {
                    if (res.statusCode() != 200) {
                        stale += res.statusCode() == 409 ? 1 : 0;
                        why.add(node + ": HTTP " + res.statusCode() + " " + PeerHttp.brief(new String(in.readNBytes(2000), java.nio.charset.StandardCharsets.UTF_8)));
                        continue;
                    }
                    Path dir = Files.createDirectories(shards.loader().tier().folder(date, ccy, index));
                    tmp = com.ash.drishti.common.TempFiles.createFile(dir, "skeleton-", ".part");
                    Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                ShardAssembly.Part p = ShardSkeleton.read(tmp, index);
                if (p.range() == null || !p.range().contains(scope.map().digest()) || !digest.equals(VarShardScope.data(p.range(), p.digest()))) {
                    why.add(node + ": sent the skeleton of another shard map or data version");
                    continue;
                }
                return p;
            } catch (IOException | RuntimeException e) {
                why.add(node + ": " + e.getMessage());
            } finally {
                if (tmp != null) {
                    try {
                        Files.deleteIfExists(tmp);
                    } catch (IOException e) {
                        // a .part file: deleted at the next start
                    }
                }
            }
        }
        throw refused(date, scope.map(), index, why, stale);
    }
}
