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

import com.ash.drishti.kernel.api.module.InlineFigures;
import com.ash.drishti.kernel.api.module.KernelCalls;
import com.ash.drishti.kernel.api.module.PersonCache;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.module.runtime.InlinePivotFigures;
import com.ash.drishti.module.runtime.KernelPersonCache;
import com.ash.drishti.server.edition.EditionContext;
import com.ash.drishti.server.security.Entitlements;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link KernelCalls} over this server's own API on the loopback interface (THIN_CONSOLE_DESIGN.md step S8a): the call carries what the
 * request in hand carried (its {@code Authorization} or {@code X-Drishti-User}, its date box, known-at, lens and language), so it is the
 * same person's call through the same doors (entitlements, masks, row rules, the edition, Drishti*net's routing). Loopback is this
 * process talking to itself, never an address another machine is given.
 */
@Component
public class LoopbackKernelCalls implements KernelCalls {

    /** The headers a call carries on from the request in hand. */
    static final List<String> CARRIED = List.of("Authorization", "X-Drishti-User", AsOfResolver.HEADER, AsOfResolver.KNOWN_AT_HEADER,
            EditionContext.HEADER, "Accept-Language");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);      // an engine's first answer of a business date loads its inputs

    private final Environment env;
    private final Entitlements entitlements;
    private final ObjectProvider<com.ash.drishti.identity.UserService> users;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public LoopbackKernelCalls(Environment env, Entitlements entitlements, ObjectProvider<com.ash.drishti.identity.UserService> users) {
        this.env = env;
        this.entitlements = entitlements;
        this.users = users;
    }

    @Override
    public Caller caller() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a)) {
            throw new IllegalStateException("a caller is the person of a request: there is no request on this thread");
        }
        HttpServletRequest req = a.getRequest();
        Map<String, String> headers = new LinkedHashMap<>();
        for (String h : CARRIED) {
            String v = req.getHeader(h);
            if (v != null && !v.isBlank()) {
                headers.put(h, v);
            }
        }
        Principal p = req.getAttribute(Principal.ATTRIBUTE) instanceof Principal x ? x : Principal.anonymous("anonymous");
        String display = "";
        com.ash.drishti.identity.UserService us = users.getIfAvailable();
        if (us != null) {
            display = us.find(p.user()).map(u -> u.displayName() == null ? "" : u.displayName()).orElse("");
        }
        return new Loop(p.user(), display, entitlements.isAdmin(p), String.join(",", p.roles()), headers);
    }

    @Override
    public Map<String, Object> lens(String name) {
        if (name == null || name.isBlank()) {
            return Map.of();
        }
        Map<String, Object> spec = com.ash.drishti.server.console.ConsoleDistribution.lens(com.ash.drishti.api.Roots.anchored(
                env.getProperty("drishti.lenses-dir", com.ash.drishti.api.Roots.config("lenses"))), name.strip());
        return spec == null ? Map.of() : spec;
    }

    @Override
    public <V> PersonCache<V> cache(int size, long ttlMillis) {
        return new KernelPersonCache<>(size, ttlMillis);
    }

    /** This server's own address on the loopback interface ({@code local.server.port}, its context path; https when it serves TLS). */
    private String base() {
        String port = env.getProperty("local.server.port", env.getProperty("server.port", "8480"));
        boolean tls = env.getProperty("server.ssl.enabled", Boolean.class, false);
        String path = env.getProperty("server.servlet.context-path", "");
        return (tls ? "https" : "http") + "://127.0.0.1:" + port + (path.endsWith("/") ? path.substring(0, path.length() - 1) : path) + "/api/v1";
    }

    private final class Loop implements Caller {

        private final String user;
        private final String display;
        private final boolean admin;
        private final String roles;
        private final Map<String, String> headers;

        Loop(String user, String display, boolean admin, String roles, Map<String, String> headers) {
            this.user = user;
            this.display = display;
            this.admin = admin;
            this.roles = roles;
            this.headers = Map.copyOf(headers);
        }

        @Override
        public String user() {
            return user;
        }

        @Override
        public String display() {
            return display;
        }

        @Override
        public boolean admin() {
            return admin;
        }

        @Override
        public String lens() {
            return headers.getOrDefault(EditionContext.HEADER, "");
        }

        @Override
        public String key() {
            return String.join("\u0001", user, roles, lens(), headers.getOrDefault(AsOfResolver.HEADER, "live"),
                    headers.getOrDefault(AsOfResolver.KNOWN_AT_HEADER, ""));
        }

        @Override
        public Caller onDate(LocalDate day) {
            Map<String, String> h = new LinkedHashMap<>(headers);
            h.put(AsOfResolver.HEADER, day.toString());
            return new Loop(user, display, admin, roles, h);
        }

        @Override
        public Map<String, Object> get(String path, Map<String, ?> params) {
            return send(HttpRequest.newBuilder(uri(path, params)).GET());
        }

        @Override
        public Map<String, Object> post(String path, Object body, Map<String, ?> params) {
            try {
                return send(HttpRequest.newBuilder(uri(path, params)).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body == null ? Map.of() : body))));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("the body does not write as JSON: " + e.getOriginalMessage(), e);
            }
        }

        @Override
        public InlineFigures.Figures figures(List<InlineFigures.Field> fields, List<List<Object>> rows, List<Map<String, Object>> values,
                String by, String across, Map<String, Object> top) {
            return InlinePivotFigures.ask(this, fields, rows, values, by, across, top);
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
        private Map<String, Object> send(HttpRequest.Builder b) {
            b.timeout(TIMEOUT).header("Accept", "application/json");
            headers.forEach(b::header);
            HttpResponse<byte[]> r;
            try {
                r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException e) {
                throw new Refused(503, "DRS-5003", "this server did not answer its own call: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Refused(503, "DRS-5003", "interrupted");
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
                throw new Refused(r.statusCode(), m.get("code") == null ? "HTTP-" + r.statusCode() : String.valueOf(m.get("code")),
                        m.get("detail") == null ? String.valueOf(r.statusCode()) : String.valueOf(m.get("detail")));
            }
            return body instanceof Map<?, ?> x ? (Map<String, Object>) x : body == null ? Map.of() : Map.of("value", body);
        }
    }
}
