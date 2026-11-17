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
package com.ash.drishti.server.api;

import com.ash.drishti.kernel.api.module.RiskBridge;
import com.ash.drishti.kernel.api.services.Kernel;
import com.ash.drishti.kernel.api.services.Person;
import com.ash.drishti.server.edition.EditionContext;
import com.ash.drishti.server.kernelapi.RequestPerson;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * TODO(I13): the temporary bridge of the trading landings' {@code /risk/*} calls ({@link RiskBridge}), the one call left on this server's
 * own API: the person's call through the same doors, until those six calls are typed operations of the banking module. Deleted with the
 * last of them (and the guard's one exception with it).
 */
@Component
public class RiskLoopbackBridge implements RiskBridge {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);      // an engine's first answer of a business date loads its inputs

    private final Environment env;
    private final ObjectProvider<TokenVerifier> tokens;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public RiskLoopbackBridge(Environment env, ObjectProvider<TokenVerifier> tokens) {
        this.env = env;
        this.tokens = tokens;
    }

    @Override
    public Map<String, Object> get(Person person, String path, Map<String, ?> params) {
        return send(person, HttpRequest.newBuilder(uri(path, params)).GET());
    }

    @Override
    public Map<String, Object> post(Person person, String path, Object body, Map<String, ?> params) {
        try {
            return send(person, HttpRequest.newBuilder(uri(path, params)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body == null ? Map.of() : body))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("the body does not write as JSON: " + e.getOriginalMessage(), e);
        }
    }

    private String base() {
        String port = env.getProperty("local.server.port", env.getProperty("server.port", "8480"));
        boolean tls = env.getProperty("server.ssl.enabled", Boolean.class, false);
        String path = env.getProperty("server.servlet.context-path", "");
        return (tls ? "https" : "http") + "://127.0.0.1:" + port + (path.endsWith("/") ? path.substring(0, path.length() - 1) : path) + "/api/v1";
    }

    private URI uri(String path, Map<String, ?> params) {
        StringBuilder q = new StringBuilder();
        if (params != null) {
            params.forEach((k, v) -> {
                for (Object one : v instanceof List<?> l ? l : List.of(v == null ? "" : v)) {
                    q.append(q.isEmpty() ? '?' : '&').append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                            .append(URLEncoder.encode(String.valueOf(one), StandardCharsets.UTF_8));
                }
            });
        }
        return URI.create(base() + path + q);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(Person person, HttpRequest.Builder b) {
        b.timeout(TIMEOUT).header("Accept", "application/json").header("X-Drishti-User", person.user());
        TokenVerifier verifier = tokens.getIfAvailable();
        if (verifier != null && env.getProperty("drishti.security.enabled", Boolean.class, false)) {
            b.header("Authorization", "Bearer " + verifier.mint(person.user(), person.roles(), 60));
        }
        if (!person.lens().isEmpty()) {
            b.header(EditionContext.HEADER, person.lens());
        }
        if (person instanceof RequestPerson rp && rp.dateBox() != null) {
            b.header(AsOfResolver.HEADER, rp.dateBox());
        }
        if (person.asOf().knownAt() != null) {
            b.header(AsOfResolver.KNOWN_AT_HEADER, person.asOf().knownAt().toString());
        }
        HttpResponse<byte[]> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new Kernel.Refused(503, "DRS-5003", "this server did not answer its own call: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Kernel.Refused(503, "DRS-5003", "interrupted");
        }
        Object body;
        try {
            body = r.body().length == 0 ? Map.of() : json.readValue(r.body(), Object.class);
        } catch (IOException e) {
            String text = new String(r.body(), StandardCharsets.UTF_8);
            body = Map.of("detail", text.substring(0, Math.min(200, text.length())));
        }
        if (r.statusCode() >= 400) {
            Map<String, Object> m = body instanceof Map<?, ?> x ? (Map<String, Object>) x : Map.of();
            throw new Kernel.Refused(r.statusCode(), m.get("code") == null ? "HTTP-" + r.statusCode() : String.valueOf(m.get("code")),
                    m.get("detail") == null ? String.valueOf(r.statusCode()) : String.valueOf(m.get("detail")));
        }
        return body instanceof Map<?, ?> x ? (Map<String, Object>) x : body == null ? Map.of() : Map.of("value", body);
    }
}
