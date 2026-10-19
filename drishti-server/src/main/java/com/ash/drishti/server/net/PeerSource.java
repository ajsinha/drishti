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
import java.io.InputStream;
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
        c.served().data(nodeId);
        return Optional.of(document(ref, net.http().json().readTree(a.body())));
    }

    /**
     * The live relay (DRISHTI_NET.md section 8): one stream from the peer ({@code GET /api/v1/net/subscribe/{kind}/{id}}, server-sent events
     * of the entity's documents as the subscriber may see them there) feeding this node's topic, which every viewer here shares (per role set,
     * see the topic hub's partition). A stream that breaks is opened again after a second, with a fresh on-behalf-of token, until closed.
     */
    @Override
    public com.ash.drishti.api.Subscription subscribe(EntityRef ref, java.util.function.Consumer<EntityDocument> listener) {
        NetCaller.Caller c = NetCaller.current().orElse(null);
        if (c == null || c.principal() == null) {
            return com.ash.drishti.api.Subscription.NONE;
        }
        java.util.concurrent.atomic.AtomicBoolean open = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicReference<InputStream> body = new java.util.concurrent.atomic.AtomicReference<>();
        String path = "/api/v1/net/subscribe/" + URLEncoder.encode(ref.kind(), StandardCharsets.UTF_8) + "/~?id=" + URLEncoder.encode(ref.id(), StandardCharsets.UTF_8);
        Thread relay = Thread.ofVirtual().name("drishti-net-relay-" + nodeId).start(() -> {
            while (open.get()) {
                long t0 = System.nanoTime();
                try {
                    var answer = net.http().open(url.get(), "GET", path, forCaller(c.principal(), c.requestId()), null, Duration.ofSeconds(30));
                    if (answer.statusCode() != 200) {
                        answer.body().close();
                        log.record(nodeId, ref.kind(), "stream", ForwardLog.outcome(answer.statusCode()), ms(t0), ref.id());
                    } else {
                        log.record(nodeId, ref.kind(), "stream", "ok", ms(t0), ref.id());
                        body.set(answer.body());
                        read(answer.body(), ref, listener, open);
                    }
                } catch (IOException e) {
                    if (open.get()) {
                        log.record(nodeId, ref.kind(), "stream", "failed", ms(t0), e.toString());
                    }
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        return () -> {
            open.set(false);
            InputStream in = body.get();
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // closing a stream the peer may have closed already
                }
            }
            relay.interrupt();
        };
    }

    private static final java.util.regex.Pattern DOC_EVENT = java.util.regex.Pattern.compile("^event:\\s*doc\\s*$");

    /** Reads {@code event: doc} events (one JSON document each) until the stream ends or is closed. */
    private void read(InputStream in, EntityRef ref, java.util.function.Consumer<EntityDocument> listener, java.util.concurrent.atomic.AtomicBoolean open)
            throws IOException {
        try (var lines = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while (open.get() && (line = lines.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = DOC_EVENT.matcher(line).matches() ? "doc" : line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                } else if (line.isEmpty()) {
                    if ("doc".equals(event) && data.length() > 0) {
                        listener.accept(document(ref, net.http().json().readTree(data.toString())));
                    }
                    event = null;
                    data.setLength(0);
                }
            }
        }
    }

    /** A document as a peer sends it: {@code {provenance, data, deleted}}. */
    private EntityDocument document(EntityRef ref, JsonNode body) {
        JsonNode pv = body.path("provenance");
        LocalDate date = pv.path("businessDate").isTextual() ? LocalDate.parse(pv.path("businessDate").asText()) : null;
        Instant fetched = pv.path("fetchedAt").isTextual() ? Instant.parse(pv.path("fetchedAt").asText()) : Instant.now();
        Provenance prov = new Provenance(pv.path("source").asText(""), pv.path("generation").asLong(0), fetched, pv.path("live").asBoolean(false), date);
        if (body.path("deleted").asBoolean(false)) {
            return EntityDocument.deleted(ref, prov);
        }
        return new EntityDocument(ref, codec.read(body.path("data").toString()), prov);
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @Override
    public String health() {
        return "UP (Drishti*net peer " + nodeId + ")";
    }
}
