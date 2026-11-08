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
package com.ash.drishti.bicompute.cluster;

import com.ash.drishti.bicompute.core.AggregationPlan;
import com.ash.drishti.bicompute.core.Partials;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.ScaleOptions;
import com.ash.drishti.bicompute.functions.Hll;
import com.ash.drishti.bicompute.rollup.RollupSource;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Cluster partial aggregation (ARCHITECTURE.md section 26.6): a mergeable plan of a provider that shards is scattered over the live nodes that
 * run the pivot engine; each node evaluates its share (its files, assigned by rendezvous hashing of the file's path so a node's DuckDB and
 * disk cache stay warm for the same files; or, for a source that lists no files, one row-hash shard of N) and answers partial states per
 * cell key; the coordinator merges them in node order, then cell-key order ({@link OrderedMerge}), so the digits are the same on one node
 * and on three. A late node's share is asked of the next node after {@code hedge-after-seconds}; a share with no answer by
 * {@code deadline-seconds} refuses the view in words. Holistic plans (VaR) are not scattered here: VaR has its own sharding.
 */
public final class ClusterRoute implements PivotCore.Hooks {

    /** The cluster as the route sees it (the host's Drishti*net). */
    public interface Peers {
        /** This node's id. */
        String self();

        /** The live nodes running the pivot engine, this one included, sorted by id (the merge order). */
        List<String> nodes();

        /** Asks a node for its share: the request of {@link #request}, as the same person; the node's answer map. */
        Map<String, Object> ask(Viewer v, String node, Map<String, Object> request, long timeoutMs) throws Exception;
    }

    /**
     * {@code drishti.pivot.cluster}.
     *
     * @param enabled scatter plans across the nodes
     * @param hedgeAfterMs a share not answered by then is asked of the next node
     * @param deadlineMs a share not answered by then refuses the view
     * @param minRows views estimated under this many rows stay on this node (scattering costs more than it saves)
     */
    public record Settings(boolean enabled, long hedgeAfterMs, long deadlineMs, long minRows) {
        public static Settings defaults() {
            return new Settings(true, 20_000, 300_000, 5_000_000);
        }
    }

    private final Peers peers;
    private volatile Settings settings;
    private final Map<String, RollupSource> parted;
    private final Executor exec;

    /**
     * @param parted the providers' sources that list their files (provider id to source); a provider not here is scattered by row shard
     * @param exec where shares are asked (virtual threads)
     */
    public ClusterRoute(Peers peers, Settings settings, Map<String, RollupSource> parted, Executor exec) {
        this.peers = peers;
        this.settings = settings == null ? Settings.defaults() : settings;
        this.parted = Map.copyOf(parted);
        this.exec = exec;
    }

    public void settings(Settings s) {
        this.settings = s;
    }

    /** The nodes a scan is shared by now (1 when the route is off or alone). */
    public int nodes() {
        if (!settings.enabled()) {
            return 1;
        }
        try {
            return Math.max(1, peers.nodes().size());
        } catch (RuntimeException e) {
            return 1;
        }
    }

    @Override
    public Optional<PivotCore.Routed> route(Viewer v, PivotProvider p, AggregationPlan plan, ScaleOptions o) {
        if (!settings.enabled() || o.sampled() || plan.shard() != null || !p.capabilities().shards() || !OrderedMerge.mergeable(plan)
                || plan.functions().stream().anyMatch(f -> f.id().startsWith("fn:"))) {          // custom functions are resolved per reader here
            return Optional.empty();
        }
        List<String> nodes = peers.nodes();
        if (nodes.size() < 2) {
            return Optional.empty();
        }
        if (settings.minRows() > 0) {
            long rows = p.estimate(v, plan).rows();
            if (rows >= 0 && rows < settings.minRows()) {
                return Optional.empty();
            }
        }
        RollupSource src = parted.get(p.id());
        List<RollupSource.Part> parts = src == null ? List.of() : src.parts(plan.source(), plan.date(), plan);
        List<PartialGather.Share> shares = new ArrayList<>();
        Map<String, Map<String, Object>> requests = new LinkedHashMap<>();
        String how;
        if (!parts.isEmpty()) {
            Map<String, List<String>> byNode = assign(parts, nodes);
            for (String n : nodes) {
                List<String> mine = byNode.get(n);
                if (mine == null || mine.isEmpty()) {
                    continue;
                }
                String id = n;
                shares.add(new PartialGather.Share(id, List.of(n, next(nodes, n))));
                requests.put(id, request(plan, mine, null));
            }
            how = "computed on " + shares.size() + " nodes (" + String.join(", ", nodes) + "), " + parts.size() + " files assigned by path, "
                    + "partial states merged in node order";
        } else {
            for (int i = 0; i < nodes.size(); i++) {
                String id = "shard " + i + " of " + nodes.size();
                shares.add(new PartialGather.Share(id, List.of(nodes.get(i), nodes.get((i + 1) % nodes.size()))));
                requests.put(id, request(plan, null, new AggregationPlan.Shard(i, nodes.size())));
            }
            how = "computed on " + nodes.size() + " nodes (" + String.join(", ", nodes) + "), one row shard each, partial states merged in node order";
        }
        List<Partials> got = PartialGather.gather(shares, (node, share) -> {
            Map<String, Object> req = requests.get(share.id());
            Partials one = node.equals(peers.self()) ? serve(v, p, src, plan, req) : PartialCodec.partials(plan,
                    peers.ask(v, node, req, settings.deadlineMs()));
            return new ArrayList<>(List.of(one));
        }, PartialGather.partials(), exec, settings.hedgeAfterMs(), settings.deadlineMs());
        return Optional.of(new PivotCore.Routed(OrderedMerge.merge(plan, got), how, true));
    }

    /** What a node is sent: the plan, and its parts or its shard. */
    static Map<String, Object> request(AggregationPlan plan, List<String> parts, AggregationPlan.Shard shard) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan", PartialCodec.plan(shard == null ? plan : plan.on(shard)));
        if (parts != null) {
            m.put("parts", parts);
        }
        return m;
    }

    /** A node's share evaluated here (the coordinator's own share, or a peer's request): over its parts, or its shard. */
    @SuppressWarnings("unchecked")
    static Partials serve(Viewer v, PivotProvider p, RollupSource src, AggregationPlan whole, Map<String, Object> req) {
        AggregationPlan plan = PartialCodec.plan((Map<String, Object>) req.get("plan"), p);
        if (req.get("parts") instanceof List<?> l) {
            if (src == null) {
                throw new IllegalStateException(p.id() + " is not read by parts here");
            }
            return src.evaluateAs(v, plan, PartialCodec.strings(l));
        }
        return p.evaluate(v, plan);
    }

    /**
     * A peer's request answered (the server's {@code POST /api/v1/net/pivot/partials}): the plan parsed on this node's provider of the
     * source, evaluated for this person over the share, the partial states encoded.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> answer(PivotCore core, Map<String, RollupSource> parted, Viewer v, Map<String, Object> req) {
        Map<String, Object> pm = (Map<String, Object>) req.get("plan");
        if (pm == null) {
            throw com.ash.drishti.bicompute.core.PivotView.bad("no plan was sent");
        }
        PivotProvider p = core.provider(String.valueOf(pm.get("source")));
        AggregationPlan plan = PartialCodec.plan(pm, p);
        Partials parts = serve(v, p, parted.get(p.id()), plan, req);
        return PartialCodec.partials(plan, parts);
    }

    /** Files to nodes by rendezvous hashing: each file to the node whose hash of (node, file) is highest; stable as nodes come and go. */
    static Map<String, List<String>> assign(List<RollupSource.Part> parts, List<String> nodes) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (RollupSource.Part part : parts) {
            String best = nodes.stream().max(Comparator.comparingLong((String n) -> Hll.hash(n + "|" + part.id())).thenComparing(n -> n)).orElseThrow();
            out.computeIfAbsent(best, k -> new ArrayList<>()).add(part.id());
        }
        return out;
    }

    private static String next(List<String> nodes, String n) {
        int i = nodes.indexOf(n);
        return nodes.get((i + 1) % nodes.size());
    }
}
