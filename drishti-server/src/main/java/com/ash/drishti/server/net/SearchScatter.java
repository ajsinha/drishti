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
package com.ash.drishti.server.net;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.command.Suggestion;
import com.ash.drishti.engine.search.StructuredSearch;
import com.ash.drishti.net.security.NetHeaders;
import com.ash.drishti.net.security.OnBehalfOf;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.api.AsOfResolver;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Scatter-gather (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 8): a search, and the type-ahead, asked of every peer that holds data of the kind and is not a
 * replica of a node already asked, each running it on its own data (one hop: it asks no one else), within the budget of routing.yaml.
 * The answers are merged as one: rows by kind and id (the first answer wins a duplicate, this node's first), re-sorted by the search's
 * order (or by id, as one node sorts), cut to its limit; scanned and matched summed, an entity two nodes matched counted once; a peer that
 * failed or was late named in {@code failed} and the answer {@code partial}, never silently smaller. Who answered what is in {@code nodes},
 * shown to administrators only.
 */
public final class SearchScatter {

    /**
     * The key of the ids of every match in a node's answer (when it lists them): used for the merge, never in an answer to a client.
     */
    public static final String MATCHED_IDS = "matchedIds";

    /** One peer's share: its answer, or why it has none, and how long it took. */
    public record Share(String nodeId, JsonNode answer, String failure, long ms) {}

    private static final com.fasterxml.jackson.databind.ObjectMapper PLAIN = new com.fasterxml.jackson.databind.ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final NetRouting routing;
    private final ForwardLog log;
    private final ExecutorService executor;

    public SearchScatter(NetRouting routing, ForwardLog log, ExecutorService executor) {
        this.routing = routing;
        this.log = log;
        this.executor = executor;
    }

    /** How long a peer's share of a search may take (routing.yaml {@code scatter-budget}). */
    public Duration budget() {
        return routing.net().config().routing().scatterBudget();
    }

    /** How long a peer's share of the type-ahead may take (routing.yaml {@code suggest-budget}). */
    public Duration suggestBudget() {
        return routing.net().config().routing().suggestBudget();
    }

    /** The peers a search of the kind asks for this caller, besides this node: none when forwarding is off or the request was forwarded. */
    public List<String> targets(String kind, Principal p) {
        if (!routing.forwarding() || NetCaller.fromPeer() || p == null) {
            return List.of();
        }
        return routing.tableFor(p).scatterTargets(kind);
    }

    /** Each peer's share of {@code pathAndQuery}, asked in parallel for the caller, cut at {@code budget}. */
    public List<Share> ask(List<String> nodes, String pathAndQuery, AsOf asOf, Principal p, Duration budget, String what, String kind) {
        String requestId = NetCaller.current().map(NetCaller.Caller::requestId).orElse("-");
        List<CompletableFuture<Share>> calls = new ArrayList<>();
        for (String node : nodes) {
            String url = routing.net().node().flatMap(n -> n.table().known(node)).map(k -> k.member().url()).orElse(null);
            if (url == null) {
                continue;
            }
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put(NetHeaders.HOP, "1");
            headers.put(NetHeaders.REQUEST, requestId);
            headers.put(NetHeaders.ON_BEHALF_OF, OnBehalfOf.mint(routing.net().key(), p.user(), p.roles(), p.embedApp(), routing.net().nodeId(), requestId, node));
            if (asOf != null && !asOf.live() && asOf.businessDate() != null) {
                headers.put(AsOfResolver.HEADER, asOf.businessDate().toString());
            }
            if (asOf != null && asOf.knownAt() != null) {
                headers.put(AsOfResolver.KNOWN_AT_HEADER, asOf.knownAt().toString());
            }
            calls.add(CompletableFuture.supplyAsync(() -> {
                long t0 = System.nanoTime();
                try {
                    PeerHttp.Answer a = routing.net().http().send(url, "GET", pathAndQuery, headers, null, budget);
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    log.record(node, kind, what, ForwardLog.outcome(a.status()), ms, pathAndQuery);
                    if (a.status() != 200) {
                        return new Share(node, null, "answered " + a.status() + ": " + PeerHttp.brief(a.text()), ms);
                    }
                    return new Share(node, routing.net().http().json().readTree(a.body()), null, ms);
                } catch (Exception e) {
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    boolean late = e instanceof java.net.http.HttpTimeoutException;
                    log.record(node, kind, what, late ? "timeout" : "failed", ms, pathAndQuery);
                    return new Share(node, null, late ? "did not answer within " + budget.toMillis() + " ms" : "could not be reached (" + e.getClass().getSimpleName() + ")", ms);
                }
            }, executor));
        }
        List<Share> out = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            try {
                out.add(calls.get(i).get(budget.toMillis() + 500, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                out.add(new Share(nodes.get(i), null, "did not answer within " + budget.toMillis() + " ms", budget.toMillis()));
            }
        }
        return out;
    }

    /**
     * A search answered here, completed by the peers (the controller's last step): asked of the scatter targets, merged; the nodes that
     * hold data of the kind but are down are named in {@code failed} (the answer is partial, never silently smaller). Answers {@code local}
     * unchanged when the cluster has nothing to add (off, a forwarded request, no other node holds the kind).
     */
    public Map<String, Object> complete(Map<String, Object> local, String kind, String q, AsOf asOf, Principal p, boolean descending, int limit,
            boolean admin) {
        if (!routing.forwarding() || NetCaller.fromPeer() || p == null) {
            local.remove(MATCHED_IDS);
            return local;
        }
        List<String> peers = targets(kind, p);
        List<String> down = routing.downHolders(kind);
        if (peers.isEmpty() && down.isEmpty()) {
            local.remove(MATCHED_IDS);
            return local;
        }
        List<Share> shares = new ArrayList<>(peers.isEmpty() ? List.of() : ask(peers, sharePath(q), asOf, p, budget(), "search", kind));
        down.forEach(d -> shares.add(new Share(d, null, "not asked: it cannot be reached now", 0)));
        return merge(local, shares, descending, limit, admin);
    }

    /** The path and query of a peer's share of a search. */
    public static String sharePath(String q) {
        return "/api/v1/net/search?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
    }

    /**
     * Merges this node's answer (null when the kind's pack is not here and only peers answered) with the peers' shares. An entity two
     * nodes matched counts once in {@code matched}: exactly when every answer lists its matches' ids ({@link #MATCHED_IDS}), else by the
     * rows two answers both returned; {@code scanned} leaves out the same duplicates (an entity two nodes read is one entity).
     *
     * @param admin whether the caller may see {@code nodes} (which node answered what)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> merge(Map<String, Object> local, List<Share> shares, boolean descending, int limit, boolean admin) {
        Map<String, Object> out;
        List<StructuredSearch.Row> rows = new ArrayList<>();
        Set<EntityRef> seen = new LinkedHashSet<>();
        List<Object> failed = new ArrayList<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        long scanned = 0;
        long matched = 0;
        boolean partial = false;
        Set<String> matchedIds = new java.util.HashSet<>();
        boolean listed = true;                                     // every answer listed its matches' ids
        long dupRows = 0;                                          // rows two answers both returned
        if (local != null) {
            out = new LinkedHashMap<>(local);
            out.remove(MATCHED_IDS);
            for (StructuredSearch.Row r : (List<StructuredSearch.Row>) local.get("rows")) {
                if (seen.add(r.ref())) {
                    rows.add(r);
                } else {
                    dupRows++;
                }
            }
            if (local.get(MATCHED_IDS) instanceof List<?> ids && ids.size() == ((Number) local.getOrDefault("matched", 0)).longValue()) {
                ids.forEach(i -> matchedIds.add(String.valueOf(i)));
            } else {
                listed = false;
            }
            scanned += ((Number) local.getOrDefault("scanned", 0)).longValue();
            matched += ((Number) local.getOrDefault("matched", 0)).longValue();
            partial = Boolean.TRUE.equals(local.get("partial"));
            failed.addAll((List<Object>) local.getOrDefault("failed", List.of()));
            nodes.add(node(routing.net().nodeId(), ((Number) local.getOrDefault("scanned", 0)).longValue(),
                    ((Number) local.getOrDefault("matched", 0)).longValue(), ((Number) local.getOrDefault("elapsedMs", 0)).doubleValue()));
        } else {
            out = null;
        }
        for (Share s : shares) {
            if (s.answer() == null) {
                partial = true;
                failed.add(Map.of("source", "node " + s.nodeId(), "reason", s.failure()));
                nodes.add(Map.of("nodeId", s.nodeId(), "failure", s.failure(), "elapsedMs", s.ms()));
                continue;
            }
            JsonNode a = s.answer();
            if (out == null) {
                out = routing.net().http().json().convertValue(a, new TypeReference<LinkedHashMap<String, Object>>() {});
                out.remove(MATCHED_IDS);
            }
            for (JsonNode r : a.path("rows")) {
                EntityRef ref = EntityRef.of(r.path("ref").path("kind").asText(), r.path("ref").path("id").asText());
                if (seen.add(ref)) {
                    Map<String, Object> values = routing.net().http().json().convertValue(r.path("values"), new TypeReference<LinkedHashMap<String, Object>>() {});
                    rows.add(new StructuredSearch.Row(ref, r.path("title").asText(null), values));
                } else {
                    dupRows++;
                }
            }
            JsonNode ids = a.path(MATCHED_IDS);
            if (ids.isArray() && ids.size() == a.path("matched").asLong(0)) {
                ids.forEach(i -> matchedIds.add(i.asText()));
            } else {
                listed = false;
            }
            scanned += a.path("scanned").asLong(0);
            matched += a.path("matched").asLong(0);
            partial |= a.path("partial").asBoolean(false);
            a.path("failed").forEach(f -> failed.add(Map.of("source", "node " + s.nodeId() + ": " + f.path("source").asText(), "reason", f.path("reason").asText())));
            nodes.add(node(s.nodeId(), a.path("scanned").asLong(0), a.path("matched").asLong(0), s.ms()));
        }
        if (out == null) {
            out = new LinkedHashMap<>();
        }
        String orderBy = out.get("orderBy") instanceof String o && !o.isBlank() ? o : null;
        rows.sort(order(orderBy, descending));
        // an entity two nodes matched once: exactly by the ids when every answer listed them, else by the rows both returned
        long twice = listed ? matched - matchedIds.size() : dupRows;
        out.put("rows", List.copyOf(rows.subList(0, Math.min(rows.size(), Math.max(limit, 0)))));
        out.put("scanned", Math.max(0, scanned - twice));
        out.put("matched", Math.max(0, matched - twice));
        out.put("partial", partial);
        out.put("failed", failed);
        if (!rows.isEmpty()) {
            out.remove("noData");
        }
        if (admin) {
            out.put("nodes", nodes);
        } else {
            out.remove("nodes");
        }
        return out;
    }

    private static Map<String, Object> node(String id, long scanned, long matched, double ms) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", id);
        m.put("scanned", scanned);
        m.put("matched", matched);
        m.put("elapsedMs", ms);
        return m;
    }

    /** As one node orders its rows: by the order-by value (numbers by value, text ignoring case, none last), then by id. */
    static Comparator<StructuredSearch.Row> order(String orderBy, boolean descending) {
        Comparator<StructuredSearch.Row> byId = Comparator.comparing(r -> r.ref().id());
        if (orderBy == null) {
            return byId;
        }
        int sign = descending ? -1 : 1;
        Comparator<StructuredSearch.Row> byKey = (a, b) -> {
            Object x = a.values().get(orderBy);
            Object y = b.values().get(orderBy);
            if (x == null || y == null) {
                return (x == null ? 1 : 0) - (y == null ? 1 : 0);
            }
            return sign * compare(x, y);
        };
        return byKey.thenComparing(byId);
    }

    private static int compare(Object a, Object b) {
        boolean na = a instanceof Number;
        boolean nb = b instanceof Number;
        if (na && nb) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (na != nb) {
            return na ? -1 : 1;
        }
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    }

    // -- type-ahead -----------------------------------------------------------------------------------------------------

    /** Every reachable peer that renders a kind (type-ahead asks them all, within its short budget). */
    public List<String> suggestTargets(Principal p) {
        if (!routing.forwarding() || NetCaller.fromPeer() || p == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        routing.tableFor(p).nodes().stream().filter(n -> !n.self() && !n.digest().kinds().isEmpty()).forEach(n -> out.add(n.id()));
        return out;
    }

    /** This node's suggestions first, then the peers' in turn (one from each), an entity or a mnemonic counted once, at most {@code limit}. */
    public List<Suggestion> mergeSuggestions(List<Suggestion> local, List<Share> shares, int limit) {
        return mergeSuggestions(local, shares, limit, "");
    }

    /**
     * As {@link #mergeSuggestions(List, List, int)}, and an entity whose id is a word of what was typed comes right after the ranges, from
     * whichever node holds it: on a node that does not hold {@code 1000002}, {@code TRD 1000002} lists the peer's {@code 1000002} first,
     * not ten of this node's longer ids that start with it (QA1-204).
     *
     * @param typed what was typed ({@code TRD 1000002})
     */
    public List<Suggestion> mergeSuggestions(List<Suggestion> local, List<Share> shares, int limit, String typed) {
        Map<String, Suggestion> merged = new LinkedHashMap<>();
        local.forEach(s -> merged.putIfAbsent(key(s), s));
        List<List<Suggestion>> theirs = new ArrayList<>();
        for (Share s : shares) {
            if (s.answer() != null && s.answer().isArray()) {
                theirs.add((routing == null ? PLAIN : routing.net().http().json()).convertValue(s.answer(), new TypeReference<List<Suggestion>>() {}));
            }
        }
        int longest = theirs.stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < longest; i++) {
            for (List<Suggestion> l : theirs) {
                if (i < l.size()) {
                    merged.putIfAbsent(key(l.get(i)), l.get(i));
                }
            }
        }
        // the ranges after an id first, as on one node (QA1-05): a peer that holds the entity offers them when this node cannot
        java.util.Set<String> words = new java.util.HashSet<>();
        for (String w : (typed == null ? "" : typed).trim().split("\\s+")) {
            words.add(w.toLowerCase(java.util.Locale.ROOT));
        }
        java.util.function.Predicate<Suggestion> exact = s -> !"range".equals(s.type()) && s.id() != null
                && words.contains(s.id().toLowerCase(java.util.Locale.ROOT));
        List<Suggestion> ordered = new ArrayList<>(merged.values().stream().filter(s -> "range".equals(s.type())).toList());
        merged.values().stream().filter(exact).forEach(ordered::add);
        merged.values().stream().filter(s -> !"range".equals(s.type()) && !exact.test(s)).forEach(ordered::add);
        return ordered.stream().limit(Math.max(1, limit)).toList();
    }

    /** One row per entity, mnemonic or range: the ranges of one entity (30, 3M, YTD, 30 DIFF) differ by what they complete. */
    private static String key(Suggestion s) {
        return s.type() + "\u001f" + s.kind() + "\u001f" + s.id() + "\u001f" + s.mnemonic() + "\u001f"
                + (s.id() == null || "range".equals(s.type()) ? s.complete() : "");
    }
}
