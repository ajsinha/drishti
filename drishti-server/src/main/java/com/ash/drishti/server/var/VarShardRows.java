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
import com.ash.drishti.compute.var.ShardAssembly;
import com.ash.drishti.compute.var.ShardMap;
import com.ash.drishti.compute.var.ShardSkeleton;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ShardedVectors;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.net.NetCaller;
import com.ash.drishti.server.security.Principal;
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
 * A sharded date's rows on other members (VAR_SHARDING.md section 6): the coordinator's side of a partial sum and of a skeleton. A shard's
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

    /** The asking thread's caller and counters, carried to the threads that ask the owners. */
    record Ctx(Principal principal, Stats stats) {}

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
            public void held(int shard, Object context) {
                if (context instanceof Ctx c && c.stats() != null) {
                    c.stats().shards.add(shard);
                    count(c.stats(), shards.peers().self(), false, true);
                }
            }

            @Override
            public Object context() {
                return new Ctx(NetCaller.current().map(NetCaller.Caller::principal).orElse(null), STATS.get());
            }
        };
    }

    /** The members to ask for a shard at data {@code digest}, in order (see the class). */
    List<String> candidates(LocalDate date, ShardMap map, int index, String digest) {
        List<String> owners = shards.owners(map.shard(index));
        Map<String, VarShardPeers.Peer> byNode = new TreeMap<>();
        shards.members().forEach(p -> byNode.put(p.node(), p));
        Set<String> out = new LinkedHashSet<>();
        for (String n : owners) {
            VarShardPeers.Peer p = byNode.get(n);
            if (p != null && p.routable() && p.state().ready(date, map.digest(), index, digest)) {
                out.add(n);
            }
        }
        byNode.values().stream().filter(p -> p.routable() && p.state().ready(date, map.digest(), index, digest)).map(VarShardPeers.Peer::node)
                .forEach(out::add);
        for (String n : owners) {
            VarShardPeers.Peer p = byNode.get(n);
            if (p != null && p.eligible()) {
                out.add(n);
            }
        }
        return new ArrayList<>(out);
    }

    private double[] sum(LocalDate date, String ccy, ShardMap map, int index, String digest, OrdinalSet rows, Ctx ctx) {
        Stats st = ctx == null ? null : ctx.stats();
        if (st != null) {
            st.shards.add(index);
        }
        VarShardBooks.Key k = new VarShardBooks.Key(date, ccy, map.digest(), index, digest);
        java.util.Optional<VarBook> here = shards.books().held(k);
        if (here.isPresent()) {
            count(st, shards.peers().self(), false, true);
            return shards.sums().sum(here.get().store(), rows);
        }
        List<String> order = candidates(date, map, index, digest);
        List<String> owners = shards.owners(map.shard(index));
        List<String> why = new ArrayList<>();
        int stale = 0;
        byte[] body = request(date, ccy, map, index, digest, rows);
        Duration timeout = Duration.ofSeconds(shards.own().callTimeoutSeconds());
        for (String node : order) {
            boolean replica = owners.isEmpty() || !owners.get(0).equals(node);
            if (node.equals(shards.peers().self())) {
                try {
                    VarBook b = shards.served(date, ccy, index, map.digest(), digest);
                    count(st, node, replica, true);
                    return shards.sums().sum(b.store(), rows);
                } catch (DrishtiException e) {
                    stale += e.errorCode() == ErrorCode.VAR_SHARD_STALE ? 1 : 0;
                    why.add(node + ": " + e.getMessage());
                    continue;
                }
            }
            try {
                PeerHttp.Answer a = shards.peers().call(node, "POST", "/api/v1/var/partial", body, ctx == null ? null : ctx.principal(), timeout);
                if (a.status() == 200) {
                    double[] v = answer(a.body(), map, index, digest, rows.size());
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
        String q = "/api/v1/var/shards/skeleton?date=" + date + "&ccy=" + ccy + "&shard=" + index + "&map=" + scope.map().digest()
                + "&digest=" + digest;
        for (String node : candidates(date, scope.map(), index, digest)) {
            if (node.equals(shards.peers().self())) {
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
