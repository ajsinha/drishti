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
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.net.security.NetHeaders;
import com.ash.drishti.net.security.OnBehalfOf;
import com.ash.drishti.net.transport.PeerHttp;
import com.ash.drishti.server.api.AsOfResolver;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * One peer as a source (DRISHTI_NET.md section 7, data forwarding): a read none of this node's sources answered is asked of the peer
 * ({@code GET /api/v1/net/fetch/{kind}/{id}}, signed, for the caller with a 30 s on-behalf-of token, the business date and "known at"
 * of the read), and the peer answers the raw document as that caller may see it (its masks applied). This node then renders it with its
 * own Sutra and applies its own masks again, so the page looks the same whichever node holds the data. Never registered with the
 * source registry: the router asks it only through its fallback, after the local sources.
 */
public final class PeerSource implements SourcePlugin {

    private final String nodeId;
    private final Supplier<String> url;
    private final NetService net;
    private final JsonCodec codec;
    private final ForwardLog log;
    private final PluginManifest manifest;

    PeerSource(String nodeId, Supplier<String> url, NetService net, JsonCodec codec, ForwardLog log) {
        this.nodeId = nodeId;
        this.url = url;
        this.net = net;
        this.codec = codec;
        this.log = log;
        this.manifest = new PluginManifest("net:" + nodeId, "1", Set.of(), new SourceCapabilities(true, false, false, true));
    }

    public String nodeId() {
        return nodeId;
    }

    @Override
    public PluginManifest manifest() {
        return manifest;
    }

    @Override
    public void start(SourceContext context) {
        // nothing to start: the peer is reached over signed HTTP
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws Exception {
        return fetch(ref, AsOf.LATEST);
    }

    /** The headers of a call made for the current caller: the hop, the request id and the on-behalf-of token for this peer. */
    Map<String, String> forCaller(Principal p, String requestId) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put(NetHeaders.HOP, "1");
        h.put(NetHeaders.REQUEST, requestId);
        h.put(NetHeaders.ON_BEHALF_OF, OnBehalfOf.mint(net.key(), p.user(), p.roles(), p.embedApp(), net.nodeId(), requestId, nodeId));
        return h;
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) throws Exception {
        NetCaller.Caller c = NetCaller.current().orElse(null);
        if (c == null || c.principal() == null) {
            return Optional.empty();                          // a background read acts for nobody: a peer is never asked for it
        }
        Map<String, String> headers = forCaller(c.principal(), c.requestId());
        if (asOf != null && !asOf.live() && asOf.businessDate() != null) {
            headers.put(AsOfResolver.HEADER, asOf.businessDate().toString());
        }
        if (asOf != null && asOf.knownAt() != null) {
            headers.put(AsOfResolver.KNOWN_AT_HEADER, asOf.knownAt().toString());
        }
        String path = "/api/v1/net/fetch/" + URLEncoder.encode(ref.kind(), StandardCharsets.UTF_8) + "/~?id=" + URLEncoder.encode(ref.id(), StandardCharsets.UTF_8);
        Duration timeout = net.config().routing().forwardTimeout();
        long t0 = System.nanoTime();
        PeerHttp.Answer a;
        try {
            a = net.http().send(url.get(), "GET", path, headers, null, timeout);
        } catch (IOException e) {
            log.record(nodeId, ref.kind(), "data", e instanceof java.net.http.HttpTimeoutException ? "timeout" : "failed", ms(t0), e.toString());
            throw e;
        }
        log.record(nodeId, ref.kind(), "data", ForwardLog.outcome(a.status()), ms(t0), ref.id());
        if (a.status() == 404) {
            return Optional.empty();
        }
        if (a.status() != 200) {
            throw new IOException("HTTP " + a.status() + " " + PeerHttp.brief(a.text()));
        }
        JsonNode body = net.http().json().readTree(a.body());
        JsonNode pv = body.path("provenance");
        LocalDate date = pv.path("businessDate").isTextual() ? LocalDate.parse(pv.path("businessDate").asText()) : null;
        Instant fetched = pv.path("fetchedAt").isTextual() ? Instant.parse(pv.path("fetchedAt").asText()) : Instant.now();
        Provenance prov = new Provenance(pv.path("source").asText(""), pv.path("generation").asLong(0), fetched, pv.path("live").asBoolean(false), date);
        com.ash.drishti.api.DataNode data = codec.read(body.path("data").toString());
        return Optional.of(new EntityDocument(ref, data, prov));
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @Override
    public String health() {
        return "UP (Drishti*net peer " + nodeId + ")";
    }
}
