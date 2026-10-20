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
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.net.security.OnBehalfOf;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The data endpoints a peer calls for its user (DRISHTI_NET.md section 15): {@code GET /api/v1/net/fetch/{kind}/{id}} answers one entity
 * document as the on-behalf-of user may see it (masked as this node masks for them, read from this node's own sources only), for the
 * peer to render with its own Sutra. Only a node may call them, and only for a user (an on-behalf-of token): the call filter checks both.
 */
@RestController
@RequestMapping("/api/v1/net")
public class NetDataController {

    private final SourceRouter router;
    private final Entitlements entitlements;
    private final JsonCodec codec;
    private final ObjectMapper json = new ObjectMapper();

    private final org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.api.SearchController> searches;
    private final org.springframework.beans.factory.ObjectProvider<com.ash.drishti.engine.live.TopicHub> hub;
    private final java.util.concurrent.ExecutorService executor;
    private final org.springframework.beans.factory.ObjectProvider<StateSync> stateSync;

    public NetDataController(SourceRouter router, Entitlements entitlements, JsonCodec codec,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.api.SearchController> searches,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.engine.live.TopicHub> hub,
            java.util.concurrent.ExecutorService drishtiVirtualExecutor, org.springframework.beans.factory.ObjectProvider<StateSync> stateSync) {
        this.stateSync = stateSync;
        this.router = router;
        this.entitlements = entitlements;
        this.codec = codec;
        this.searches = searches;
        this.hub = hub;
        this.executor = drishtiVirtualExecutor;
    }

    /**
     * The live relay's upstream ({@code GET /api/v1/net/subscribe/{kind}/{id}}): server-sent events, one {@code doc} event per new generation
     * of the entity ({@code {provenance, data, deleted}}, masked for the on-behalf-of user as here), from this node's own topic, until the
     * peer closes it; a comment every heartbeat keeps it open through proxies.
     */
    @GetMapping(path = "/subscribe/{kind}/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribe(@PathVariable String kind, @PathVariable String id,
            @RequestParam(value = "id", required = false) String idParam, @RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @RequestAttribute(value = NetCallFilter.CLAIMS, required = false) Object claims) {
        Principal user = forUser(p, claims);
        entitlements.requireOpen(user, kind);
        EntityRef ref = EntityRef.of(kind, "~".equals(id) && idParam != null ? idParam : id);
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L);
        java.util.concurrent.LinkedBlockingDeque<EntityDocument> box = new java.util.concurrent.LinkedBlockingDeque<>(64);
        com.ash.drishti.api.Subscription sub = hub.getObject().subscribe(ref, d -> {
            if (!box.offerLast(d)) {
                box.pollFirst();                           // a slow peer gets the newest generations, not a growing backlog
                box.offerLast(d);
            }
        });
        Runnable close = sub::close;
        emitter.onCompletion(close);
        emitter.onError(e -> close.run());
        executor.execute(() -> {
            try {
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().comment("open"));   // the peer sees the stream open at once
                while (!Thread.currentThread().isInterrupted()) {
                    EntityDocument d = box.pollFirst(10, java.util.concurrent.TimeUnit.SECONDS);
                    if (d == null) {
                        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().comment("hb"));
                        continue;
                    }
                    emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("doc").data(json(user, d), MediaType.APPLICATION_JSON));
                }
            } catch (Exception e) {
                try {
                    emitter.completeWithError(e);          // the peer went away (or this node stops): nothing more to send
                } catch (IllegalStateException done) {
                    // the request is already complete
                }
            } finally {
                close.run();
            }
        });
        return emitter;
    }

    /** One document as a peer receives it, masked for {@code user} as this node masks. */
    private String json(Principal user, EntityDocument d) throws java.io.IOException {
        Map<String, Object> prov = new LinkedHashMap<>();
        var pv = d.provenance();
        if (pv != null) {
            prov.put("source", pv.source());
            prov.put("generation", pv.generation());
            prov.put("fetchedAt", pv.fetchedAt() == null ? null : pv.fetchedAt().toString());
            prov.put("live", pv.live());
            prov.put("businessDate", pv.businessDate() == null ? null : pv.businessDate().toString());
        }
        String data = d.deleted() ? "null" : codec.toJson(entitlements.redact(user, d.data()));
        return "{\"provenance\":" + json.writeValueAsString(prov) + ",\"deleted\":" + d.deleted() + ",\"data\":" + data + "}";
    }

    /**
     * Users' state from a peer ({@code POST /api/v1/net/state}, body {@code {changes: [...]}}): applied here, the newest write winning. Only a
     * node calls it (the call filter checks the cluster key); it acts for no user.
     */
    @org.springframework.web.bind.annotation.PostMapping("/state")
    public Map<String, Object> state(@org.springframework.web.bind.annotation.RequestBody com.fasterxml.jackson.databind.JsonNode body,
            @RequestAttribute(value = NetCallFilter.PEER, required = false) String peer) throws java.io.IOException {
        if (peer == null) {
            throw new DrishtiException(ErrorCode.NET_REFUSED, "only a node of the cluster sends user state");
        }
        java.util.List<StateSync.Change> changes = java.util.Arrays.asList(json.treeToValue(body.path("changes"), StateSync.Change[].class));
        return Map.of("applied", stateSync.getObject().receive(changes));
    }

    /**
     * This node's share of a scattered search ({@code GET /api/v1/net/search?q=}): the search run on this node's own data for the
     * on-behalf-of user, exactly as {@code GET /api/v1/search} answers it here, and asking no other node (one hop); with the ids of every
     * match ({@code matchedIds}, when there are not too many), so that the asking node counts a match two nodes hold once.
     */
    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String q, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @RequestAttribute(value = NetCallFilter.CLAIMS, required = false) Object claims) {
        return searches.getObject().share(q, asOf, forUser(p, claims));
    }

    static Principal forUser(Principal p, Object claims) {
        if (!(claims instanceof OnBehalfOf.Claims)) {
            throw new DrishtiException(ErrorCode.NET_REFUSED, "this endpoint is called by a node for a user: an on-behalf-of token is needed");
        }
        return p;
    }

    /**
     * This node's share of a grouped page ({@code GET /api/v1/net/group/{kind}/{id}}, DRISHTI_NET.md section 8): {@code {"share": ...}}, the
     * group's page and members as read from this node's own data, masked for the on-behalf-of user as here; {@code {"share": null}} when no
     * member of the group is here or no derived source here serves the kind.
     */
    @GetMapping("/group/{kind}/{id}")
    public ResponseEntity<String> group(@PathVariable String kind, @PathVariable String id, @RequestParam(value = "id", required = false) String idParam,
            AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @RequestAttribute(value = NetCallFilter.CLAIMS, required = false) Object claims) {
        Principal user = forUser(p, claims);
        entitlements.requireOpen(user, kind);
        String entity = "~".equals(id) && idParam != null ? idParam : id;
        String share = router.derivedOf(kind).flatMap(d -> d.plugin().share(EntityRef.of(kind, entity), asOf))
                .map(s -> codec.toJson(entitlements.redact(user, s))).orElse("null");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body("{\"share\":" + share + "}");
    }

    /**
     * This node's days of one entity ({@code GET /api/v1/net/history/{kind}/{id}}, HISTORY_QUERIES.md section 12): the dates asked, read
     * from this node's own sources only (one hop), each document masked for the on-behalf-of user as here:
     * {@code {days: [{date, versions, provenance, deleted, data}], failed: {date: why}}}.
     */
    @GetMapping("/history/{kind}/{id}")
    public ResponseEntity<String> history(@PathVariable String kind, @PathVariable String id, @RequestParam(value = "id", required = false) String idParam,
            @RequestParam String from, @RequestParam String to, @RequestParam(required = false) String dates,
            @RequestParam(required = false) String fields, @RequestParam(defaultValue = "0") int lastDays, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestAttribute(value = NetCallFilter.CLAIMS, required = false) Object claims)
            throws java.io.IOException {
        Principal user = forUser(p, claims);
        entitlements.requireOpen(user, kind);
        String entity = "~".equals(id) && idParam != null ? idParam : id;
        java.util.List<java.time.LocalDate> list = dates == null || dates.isBlank() ? java.util.List.of()
                : java.util.Arrays.stream(dates.split(",")).map(String::trim).filter(x -> !x.isEmpty()).map(java.time.LocalDate::parse).toList();
        java.util.Set<String> wanted = fields == null || fields.isBlank() ? java.util.Set.of()
                : java.util.Set.copyOf(java.util.Arrays.stream(fields.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
        com.ash.drishti.api.HistoryRequest r = new com.ash.drishti.api.HistoryRequest(EntityRef.of(kind, entity), java.time.LocalDate.parse(from),
                java.time.LocalDate.parse(to), list, wanted, asOf.knownAt(), lastDays, null, 0);
        var routed = router.history(r);
        StringBuilder b = new StringBuilder("{\"days\":[");
        boolean first = true;
        for (var d : routed.days()) {
            b.append(first ? "" : ",");
            first = false;
            String doc = json(user, d.document());
            b.append("{\"date\":\"").append(d.date()).append("\",\"versions\":").append(d.versions() == null ? "null" : d.versions().toString())
                    .append(',').append(doc, 1, doc.length());
        }
        b.append("],\"failed\":{");
        first = true;
        for (var f : routed.failures()) {
            b.append(first ? "" : ",").append(json.writeValueAsString(f.date().toString())).append(':').append(json.writeValueAsString(f.reason()));
            first = false;
        }
        b.append("}}");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(b.toString());
    }

    /** {@code id} in the path, or {@code ~} in the path and the id as {@code ?id=} (an id with a slash, as every view endpoint takes it). */
    @GetMapping("/fetch/{kind}/{id}")
    public ResponseEntity<String> fetch(@PathVariable String kind, @PathVariable String id, @RequestParam(value = "id", required = false) String idParam,
            AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @RequestAttribute(value = NetCallFilter.CLAIMS, required = false) Object claims) throws Exception {
        Principal user = forUser(p, claims);
        entitlements.requireOpen(user, kind);
        String entity = "~".equals(id) && idParam != null ? idParam : id;
        EntityDocument d;
        try {
            d = router.fetch(EntityRef.of(kind, entity), asOf).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof DrishtiException de && de.errorCode() == ErrorCode.NET_PEER_FAILED
                    && !(de instanceof com.ash.drishti.engine.source.SourceFailure)) {
                // not held here, and this node thinks a holder is down: to the asking node that is "not here" (404); it asks its own
                // holders and names the one that is down itself (a 502 here would name this node, which is up, as the one that failed)
                throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no source here holds " + kind + "/" + entity);
            }
            throw e.getCause() instanceof DrishtiException de ? de : new DrishtiException(ErrorCode.SOURCE_FAILED, String.valueOf(e.getMessage()));
        }
        Map<String, Object> prov = new LinkedHashMap<>();
        var pv = d.provenance();
        if (pv != null) {
            prov.put("source", pv.source());
            prov.put("generation", pv.generation());
            prov.put("fetchedAt", pv.fetchedAt() == null ? null : pv.fetchedAt().toString());
            prov.put("live", pv.live());
            prov.put("businessDate", pv.businessDate() == null ? null : pv.businessDate().toString());
        }
        // the document as this user may see it here: masked by this node's rules (the asking node masks again by its own)
        String data = codec.toJson(entitlements.redact(user, d.data()));
        String body = "{\"kind\":" + json.writeValueAsString(kind) + ",\"id\":" + json.writeValueAsString(entity) + ",\"provenance\":"
                + json.writeValueAsString(prov) + ",\"data\":" + data + "}";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
