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
 * header, added by the caller. Implements {@link GossipTransport} for the membership.
 *
 * <p>Two lanes (DRISHTI_NET.md section 5, "gossip off the busy path", 2.6.7), each with one JDK client per scheme and TLS settings
 * (mutual TLS from {@code node.yaml}'s {@code tls.*} through the shared {@code TlsHttp}), reused and thread-safe:
 * <ul>
 * <li><b>control</b> ({@link #control}): gossip exchanges, direct and indirect probes and digest pulls. HTTP/1.1, a short connect timeout,
 * its own executor of two platform threads, its own connections, to the member's gossip port when it advertises one (falling back to its
 * server port when that port refuses while the server port answers). Never shares a connection, a thread or a timeout with data.</li>
 * <li><b>data</b> ({@link #send}, {@link #open}): forwarded views, peer fetches, scatter-gather, history, state sync, the live relay.
 * HTTP/2 by default (routing.yaml {@code peer-protocol}): many requests multiplexed over one connection per peer, {@code h2c} by the
 * upgrade on plain HTTP and ALPN on TLS; a peer that does not speak HTTP/2 is answered in HTTP/1.1 on the same call.</li>
 * </ul>
 */
public final class PeerHttp implements GossipTransport {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PeerHttp.class);
    /** How long a gossip port that refused while the member's server port answered is not tried again (a firewall, a NAT). */
    static final Duration GOSSIP_PORT_RETRY = Duration.ofMinutes(5);

    /** A whole answer: status, headers (lower-case names), body and the HTTP version it came in. */
    public record Answer(int status, Map<String, List<String>> headers, byte[] body, HttpClient.Version version) {

        public Answer(int status, Map<String, List<String>> headers, byte[] body) {
            this(status, headers, body, HttpClient.Version.HTTP_1_1);
        }

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
    private final Map<String, HttpClient> controlClients = new ConcurrentHashMap<>();
    /** The control lane's own threads: never the common pool, the virtual-thread scheduler or the data clients' executor. */
    private final java.util.concurrent.ExecutorService controlIo = java.util.concurrent.Executors.newFixedThreadPool(2,
            Thread.ofPlatform().daemon().name("drishti-net-control-http-", 0).factory());
    /** Gossip addresses that refused while the member's server port answered, until when they are not tried. */
    private final Map<String, Long> gossipPortUnreachable = new ConcurrentHashMap<>();
    private volatile Supplier<Boolean> http2 = () -> true;
    private volatile Function<String, String> gossipAddress = url -> url;
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

    /** Whether data goes over HTTP/2 (routing.yaml {@code peer-protocol: http2}, read on every call). */
    public PeerHttp http2(Supplier<Boolean> on) {
        this.http2 = on == null ? () -> true : on;
        return this;
    }

    /** A member's gossip address from its server address ({@code url} itself for a member without a gossip port, or a seed). */
    public PeerHttp gossipAddress(Function<String, String> f) {
        this.gossipAddress = f == null ? url -> url : f;
        return this;
    }

    /** Forgets the clients (node.yaml's TLS changed): the next call builds them again. */
    public void reset() {
        clients.clear();
        controlClients.clear();
        gossipPortUnreachable.clear();
        dataPeers.clear();
    }

    /** Stops the control lane's threads (the server is stopping). */
    public void close() {
        controlIo.shutdownNow();
    }

    private static String scheme(String baseUrl) {
        return baseUrl.regionMatches(true, 0, "https://", 0, 8) ? "https" : "http";
    }

    /** The data lane's client for a peer: HTTP/2 or HTTP/1.1 as routing.yaml says, the JDK's own executor. */
    private HttpClient client(String baseUrl) {
        Map<String, String> tls = tlsFor.apply(baseUrl);
        HttpClient.Version v = Boolean.TRUE.equals(http2.get()) ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1;
        String cacheKey = scheme(baseUrl) + "|" + v + "|" + new java.util.TreeMap<>(tls);
        return clients.computeIfAbsent(cacheKey, k -> build(baseUrl, tls, v, Duration.ofSeconds(2), null));
    }

    /** The control lane's client: HTTP/1.1, its own connections and threads, a connect timeout of half the gossip timeout (at most 2 s). */
    private HttpClient controlClient(String baseUrl) {
        Map<String, String> tls = tlsFor.apply(baseUrl);
        String cacheKey = scheme(baseUrl) + "|" + new java.util.TreeMap<>(tls);
        long half = Math.max(250, Math.min(2_000, gossipTimeout.get().toMillis() / 2));
        return controlClients.computeIfAbsent(cacheKey, k -> build(baseUrl, tls, HttpClient.Version.HTTP_1_1, Duration.ofMillis(half), controlIo));
    }

    private static HttpClient build(String baseUrl, Map<String, String> tls, HttpClient.Version v, Duration connect,
            java.util.concurrent.Executor executor) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(connect).followRedirects(HttpClient.Redirect.NEVER).version(v);
        if (executor != null) {
            b.executor(executor);
        }
        var material = com.ash.drishti.api.tls.TlsHttp.material(tls, baseUrl, "the peer's url");
        if (material != null) {
            com.ash.drishti.api.tls.TlsHttp.apply(b, material);
        }
        return b.build();
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
        HttpClient c = client(baseUrl);
        prime(c, baseUrl);
        HttpResponse<byte[]> r = c.send(request(baseUrl, method, pathAndQuery, headers, body, timeout), HttpResponse.BodyHandlers.ofByteArray());
        return new Answer(r.statusCode(), lower(r.headers().map()), r.body(), r.version());
    }

    /** A data connection to a peer: whether one was made lately, and the lock the first call after a quiet spell takes. */
    private static final class DataPeer {
        final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        volatile long usedAt;
        volatile HttpClient.Version version;
    }

    private final Map<String, DataPeer> dataPeers = new ConcurrentHashMap<>();
    /** A peer quiet this long may have closed its connection (Tomcat's HTTP/2 keep-alive is 60 s here): the next call primes it again. */
    static final long PRIME_AFTER_IDLE_MS = 30_000;

    /**
     * With HTTP/2, the first call to a peer (and the first after a quiet spell) is a signed {@code GET /api/v1/net/ping} made alone, while
     * the calls behind it wait: the {@code h2c} upgrade (or the TLS handshake) happens once and every call after it shares that one
     * connection. Without it, a burst of first calls opens a connection each, every one upgraded (20 to 40 in the benchmark). A peer that
     * does not speak HTTP/2 answers in HTTP/1.1 and is called so from then on.
     */
    private void prime(HttpClient c, String baseUrl) throws InterruptedException {
        if (c.version() != HttpClient.Version.HTTP_2) {
            return;
        }
        DataPeer p = dataPeers.computeIfAbsent(baseUrl, k -> new DataPeer());
        long now = System.currentTimeMillis();
        if (p.version != null && now - p.usedAt < PRIME_AFTER_IDLE_MS) {
            p.usedAt = now;
            return;
        }
        p.lock.lockInterruptibly();
        try {
            if (p.version != null && System.currentTimeMillis() - p.usedAt < PRIME_AFTER_IDLE_MS) {
                return;                                     // primed by the call this one waited for
            }
            try {
                HttpResponse<Void> r = c.send(request(baseUrl, "GET", "/api/v1/net/ping", Map.of(), null, Duration.ofSeconds(2)),
                        HttpResponse.BodyHandlers.discarding());
                if (p.version != r.version()) {
                    LOG.debug("net: data to {} goes over {}", baseUrl, r.version());
                }
                p.version = r.version();
            } catch (IOException e) {
                p.version = null;                           // the real call says what is wrong
            }
            p.usedAt = System.currentTimeMillis();
        } finally {
            p.lock.unlock();
        }
    }

    /** The HTTP version data to a peer goes over (known after the first call), or null. */
    public HttpClient.Version dataVersion(String baseUrl) {
        DataPeer p = dataPeers.get(baseUrl);
        return p == null ? null : p.version;
    }

    /**
     * A call on the control lane (gossip, probes, digests): to the member's gossip address when it has one, else (and when that address
     * refuses the connection while the server port answers: a firewall) to {@code baseUrl}. Never on the data lane's connections.
     */
    public Answer control(String baseUrl, String method, String pathAndQuery, Map<String, String> headers, byte[] body, Duration timeout)
            throws IOException, InterruptedException {
        String g = gossipAddress.apply(baseUrl);
        if (g != null && !g.isEmpty() && !g.equals(baseUrl)) {
            Long until = gossipPortUnreachable.get(g);
            if (until == null || until < System.currentTimeMillis()) {
                try {
                    return controlSend(g, method, pathAndQuery, headers, body, timeout);
                } catch (java.net.ConnectException | java.net.http.HttpConnectTimeoutException e) {
                    Answer a = controlSend(baseUrl, method, pathAndQuery, headers, body, timeout);   // throws when the node is down: no mark
                    if (gossipPortUnreachable.put(g, System.currentTimeMillis() + GOSSIP_PORT_RETRY.toMillis()) == null) {
                        LOG.warn("net: the gossip port {} cannot be reached ({}) but {} answers: gossip goes to the server port for {} min; open the "
                                + "gossip port between the nodes, or set gossip-port in that node's node.yaml", g, e.getClass().getSimpleName(),
                                baseUrl, GOSSIP_PORT_RETRY.toMinutes());
                    }
                    return a;
                }
            }
        }
        return controlSend(baseUrl, method, pathAndQuery, headers, body, timeout);
    }

    private Answer controlSend(String url, String method, String pathAndQuery, Map<String, String> headers, byte[] body, Duration timeout)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> r = controlClient(url).send(request(url, method, pathAndQuery, headers, body, timeout),
                HttpResponse.BodyHandlers.ofByteArray());
        return new Answer(r.statusCode(), lower(r.headers().map()), r.body(), r.version());
    }

    /** Sends a signed call and gives the answer as a stream (a live relay, a large forwarded answer); the caller closes it. */
    public HttpResponse<InputStream> open(String baseUrl, String method, String pathAndQuery, Map<String, String> headers, byte[] body, Duration timeout)
            throws IOException, InterruptedException {
        HttpClient c = client(baseUrl);
        prime(c, baseUrl);
        return c.send(request(baseUrl, method, pathAndQuery, headers, body, timeout), HttpResponse.BodyHandlers.ofInputStream());
    }

    static Map<String, List<String>> lower(Map<String, List<String>> in) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k.toLowerCase(java.util.Locale.ROOT), v));
        return out;
    }

    // -- gossip -------------------------------------------------------------------------------------------------------

    @Override
    public List<Member> exchange(String url, List<Member> mine) throws Exception {
        Answer a = control(url, "POST", "/api/v1/net/gossip", Map.of("Content-Type", "application/json"),
                json.writeValueAsBytes(new Table(mine)), gossipTimeout.get());
        if (a.status() != 200) {
            throw new IOException("gossip refused by " + url + ": HTTP " + a.status() + " " + brief(a.text()));
        }
        return json.readValue(a.body(), Table.class).members();
    }

    @Override
    public boolean ping(String url) {
        try {
            return control(url, "GET", "/api/v1/net/ping", Map.of(), null, gossipTimeout.get()).status() == 200;
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
            Answer a = control(via, "GET", "/api/v1/net/ping?target=" + URLEncoder.encode(target, StandardCharsets.UTF_8), Map.of(), null,
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
