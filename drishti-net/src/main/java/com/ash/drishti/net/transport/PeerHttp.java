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
package com.ash.drishti.net.transport;

import com.ash.drishti.net.member.GossipTransport;
import com.ash.drishti.net.member.Member;
import com.ash.drishti.net.security.ClusterKey;
import com.ash.drishti.net.security.NetHeaders;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Signed HTTP between nodes. Every call carries this node's id, the time and the cluster key's signature over method, path and
 * query, time, node and body ({@link ClusterKey}); calls that act for a user also carry an on-behalf-of token and the hop
 * header, added by the caller. One JDK client per scheme and TLS settings (mutual TLS from {@code node.yaml}'s {@code tls.*}
 * through the shared {@code TlsHttp}), reused and thread-safe. Implements {@link GossipTransport} for the membership.
 */
public final class PeerHttp implements GossipTransport {

    /** A whole answer: status, headers (lower-case names) and body. */
    public record Answer(int status, Map<String, List<String>> headers, byte[] body) {
        public String header(String name) {
            List<String> v = headers.get(name.toLowerCase(java.util.Locale.ROOT));
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Table(List<Member> members) {}

    private final Supplier<ClusterKey> key;
    private final Supplier<String> self;
    private final Supplier<Duration> gossipTimeout;
    private final Function<String, Map<String, String>> tlsFor;
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * @param key the cluster key in force (it changes with node.yaml)
     * @param self this node's id
     * @param gossipTimeout how long a gossip exchange or probe may take
     * @param tlsFor a peer url's {@code tls.*} settings (node.yaml's, with the peer file's overrides), keys with the {@code tls.} prefix
     */
    public PeerHttp(Supplier<ClusterKey> key, Supplier<String> self, Supplier<Duration> gossipTimeout, Function<String, Map<String, String>> tlsFor) {
        this.key = key;
        this.self = self;
        this.gossipTimeout = gossipTimeout;
        this.tlsFor = tlsFor;
    }

    public ObjectMapper json() {
        return json;
    }

    /** Forgets the clients (node.yaml's TLS changed): the next call builds them again. */
    public void reset() {
        clients.clear();
    }

    private HttpClient client(String baseUrl) {
        Map<String, String> tls = tlsFor.apply(baseUrl);
        String cacheKey = (baseUrl.regionMatches(true, 0, "https://", 0, 8) ? "https" : "http") + "|" + new java.util.TreeMap<>(tls);
        return clients.computeIfAbsent(cacheKey, k -> {
            HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
                    .version(HttpClient.Version.HTTP_1_1);
            var material = com.ash.drishti.api.tls.TlsHttp.material(tls, baseUrl, "the peer's url");
            if (material != null) {
                com.ash.drishti.api.tls.TlsHttp.apply(b, material);
            }
            return b.build();
        });
    }

    /** A request to a peer, signed; {@code headers} are added as given (an on-behalf-of token, the hop, as-of headers). */
    public HttpRequest request(String baseUrl, String method, String pathAndQuery, Map<String, String> headers, byte[] body, Duration timeout) {
        byte[] b = body == null ? new byte[0] : body;
        long now = key.get().clock().millis();
        String node = self.get();
        HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).timeout(timeout)
                .header(NetHeaders.NODE, node).header(NetHeaders.TIME, Long.toString(now))
                .header(NetHeaders.SIGNATURE, key.get().sign(method, pathAndQuery, now, node, ClusterKey.bodyHash(b)));
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (v != null) {
                    r.header(k, v);
                }
            });
        }
        HttpRequest.BodyPublisher p = b.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(b);
        return r.method(method.toUpperCase(java.util.Locale.ROOT), p).build();
    }

    /** Sends a signed call and reads the whole answer. */
    public Answer send(String baseUrl, String method, String pathAndQuery, Map<String, String> headers, byte[] body, Duration timeout)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> r = client(baseUrl).send(request(baseUrl, method, pathAndQuery, headers, body, timeout), HttpResponse.BodyHandlers.ofByteArray());
        return new Answer(r.statusCode(), lower(r.headers().map()), r.body());
    }

    /** Sends a signed call and gives the answer as a stream (a live relay, a large forwarded answer); the caller closes it. */
    public HttpResponse<InputStream> open(String baseUrl, String method, String pathAndQuery, Map<String, String> headers, byte[] body, Duration timeout)
            throws IOException, InterruptedException {
        return client(baseUrl).send(request(baseUrl, method, pathAndQuery, headers, body, timeout), HttpResponse.BodyHandlers.ofInputStream());
    }

    static Map<String, List<String>> lower(Map<String, List<String>> in) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT), v));
        return out;
    }

    // -- gossip -------------------------------------------------------------------------------------------------------

    @Override
    public List<Member> exchange(String url, List<Member> mine) throws Exception {
        Answer a = send(url, "POST", "/api/v1/net/gossip", Map.of("Content-Type", "application/json"),
                json.writeValueAsBytes(new Table(mine)), gossipTimeout.get());
        if (a.status() != 200) {
            throw new IOException("gossip refused by " + url + ": HTTP " + a.status() + " " + brief(a.text()));
        }
        return json.readValue(a.body(), Table.class).members();
    }

    @Override
    public boolean ping(String url) {
        try {
            return send(url, "GET", "/api/v1/net/ping", Map.of(), null, gossipTimeout.get()).status() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean pingVia(String via, String target) {
        try {
            Answer a = send(via, "GET", "/api/v1/net/ping?target=" + URLEncoder.encode(target, StandardCharsets.UTF_8), Map.of(), null,
                    gossipTimeout.get().multipliedBy(2));
            return a.status() == 200 && json.readTree(a.body()).path("answered").asBoolean(false);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The first 200 characters of an answer, for messages. */
    public static String brief(String text) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }
}
