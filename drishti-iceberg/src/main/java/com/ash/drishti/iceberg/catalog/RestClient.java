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
package com.ash.drishti.iceberg.catalog;

import com.ash.drishti.api.proxy.ProxySettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The HTTP side of the Iceberg REST catalog protocol over the JDK's client (no Hadoop, no Iceberg library): JSON
 * requests with a bearer token, which comes from a fixed {@code token} or from OAuth2 client credentials
 * ({@code credential: <client id>:<secret>}, posted to {@code oauth2-server-uri} or the catalog's
 * {@code /v1/oauth/tokens}, with {@code scope}) and is fetched again a minute before it expires; or, for AWS Glue,
 * every request signed with SigV4 ({@link SigV4}). The proxy settings apply. Thread-safe.
 */
final class RestClient {

    static final ObjectMapper JSON = new ObjectMapper();

    /** An answer: its status, its JSON (null when there is none) and its ETag. */
    record Answer(int status, JsonNode body, String etag) {}

    private final String base;
    private final HttpClient http;
    private final ProxySettings proxy;
    private final String fixedToken;
    private final String credential;
    private final String scope;
    private final String tokenUri;
    private final SigV4 signer;
    private final Duration timeout;
    private final ReentrantLock tokenLock = new ReentrantLock();
    private volatile String token;
    private volatile long tokenExpiresAt;

    RestClient(String uri, Map<String, String> s, Map<String, String> catalogProps) {
        this.base = uri.replaceAll("/+$", "");
        this.proxy = ProxySettings.resolve(s, uri);
        this.http = proxy.apply(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))).build();
        this.fixedToken = blankToNull(s.get("token"));
        this.credential = blankToNull(s.get("credential"));
        this.scope = s.getOrDefault("scope", "catalog");
        this.tokenUri = s.getOrDefault("oauth2-server-uri", "").isBlank() ? base + "/v1/oauth/tokens" : s.get("oauth2-server-uri");
        this.timeout = Duration.ofSeconds(Long.parseLong(catalogProps.getOrDefault("rest.client.timeout-seconds", "60")));
        this.signer = Boolean.parseBoolean(catalogProps.getOrDefault("rest.sigv4-enabled", "false")) ? SigV4.from(catalogProps) : null;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    ProxySettings proxy() {
        return proxy;
    }

    String base() {
        return base;
    }

    /** A path segment, URL-encoded; a namespace's levels joined by the protocol's unit separator. */
    static String segment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    Answer get(String path, Map<String, String> headers) throws IOException {
        return send("GET", path, null, headers);
    }

    Answer head(String path) throws IOException {
        return send("HEAD", path, null, Map.of());
    }

    Answer post(String path, JsonNode body, Map<String, String> headers) throws IOException {
        return send("POST", path, JSON.writeValueAsBytes(body), headers);
    }

    private Answer send(String method, String path, byte[] body, Map<String, String> headers) throws IOException {
        URI u = URI.create(path.startsWith("http") ? path : base + path);
        HttpRequest.Builder b = HttpRequest.newBuilder(u).timeout(timeout).header("Accept", "application/json");
        headers.forEach(b::header);
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (signer != null) {
            signer.sign(b, method, u, body, headers);
        } else {
            String t = bearer();
            if (t != null) {
                b.header("Authorization", "Bearer " + t);
            }
        }
        HttpResponse<byte[]> r;
        try {
            r = proxy.send(http, b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted: " + method + " " + u, e);
        }
        if (r.statusCode() == 401 && credential != null && fixedToken == null) {
            tokenExpiresAt = 0;                                // the token was revoked or expired early: one more try
            return sendOnce(method, u, body, headers);
        }
        return answer(r);
    }

    private Answer sendOnce(String method, URI u, byte[] body, Map<String, String> headers) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(u).timeout(timeout).header("Accept", "application/json");
        headers.forEach(b::header);
        if (body != null) {
            b.header("Content-Type", "application/json");
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        String t = bearer();
        if (t != null) {
            b.header("Authorization", "Bearer " + t);
        }
        try {
            return answer(proxy.send(http, b.build(), HttpResponse.BodyHandlers.ofByteArray()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted: " + method + " " + u, e);
        }
    }

    private static Answer answer(HttpResponse<byte[]> r) throws IOException {
        JsonNode json = null;
        if (r.body() != null && r.body().length > 0) {
            try {
                json = JSON.readTree(r.body());
            } catch (IOException e) {
                json = JSON.getNodeFactory().textNode(new String(r.body(), StandardCharsets.UTF_8));
            }
        }
        return new Answer(r.statusCode(), json, r.headers().firstValue("ETag").orElse(null));
    }

    /** The bearer token: the fixed one, or a client-credentials token fetched when it is missing or about to expire. */
    private String bearer() throws IOException {
        if (fixedToken != null) {
            return fixedToken;
        }
        if (credential == null) {
            return null;
        }
        if (token != null && System.currentTimeMillis() < tokenExpiresAt) {
            return token;
        }
        tokenLock.lock();
        try {
            if (token == null || System.currentTimeMillis() >= tokenExpiresAt) {
                fetchToken();
            }
            return token;
        } finally {
            tokenLock.unlock();
        }
    }

    private void fetchToken() throws IOException {
        int colon = credential.indexOf(':');
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        if (colon >= 0) {
            form.put("client_id", credential.substring(0, colon));
            form.put("client_secret", credential.substring(colon + 1));
        } else {
            form.put("client_secret", credential);
        }
        form.put("scope", scope);
        StringBuilder text = new StringBuilder();
        form.forEach((k, v) -> text.append(text.isEmpty() ? "" : "&").append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUri)).timeout(timeout).header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(text.toString())).build();
        HttpResponse<byte[]> r;
        try {
            r = proxy.send(http, req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching an OAuth2 token from " + tokenUri, e);
        }
        Answer a = answer(r);
        if (a.status() != 200 || a.body() == null || !a.body().hasNonNull("access_token")) {
            throw new IOException("the catalog refused the credential (HTTP " + a.status() + " from " + tokenUri + "): " + error(a));
        }
        token = a.body().get("access_token").asText();
        long expiresIn = a.body().path("expires_in").asLong(3600);
        tokenExpiresAt = System.currentTimeMillis() + Math.max(10, expiresIn - 60) * 1000L;
    }

    /** The catalog's error message ({@code {"error": {"message", "type", "code"}}}) or the body. */
    static String error(Answer a) {
        if (a.body() == null) {
            return "no message";
        }
        JsonNode e = a.body().path("error");
        if (e.isObject()) {
            return e.path("type").asText("") + ": " + e.path("message").asText("");
        }
        if (e.isTextual()) {
            return e.asText() + (a.body().has("error_description") ? ": " + a.body().get("error_description").asText() : "");
        }
        return a.body().toString();
    }

    static ObjectNode object() {
        return JSON.createObjectNode();
    }
}
