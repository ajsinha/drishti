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
package com.ash.drishti.plugin.rest;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.tls.TlsHttp;
import com.ash.drishti.api.tls.TlsMaterial;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads entities from an HTTP service that answers JSON, one GET per entity.
 *
 * <p>Settings: {@code base-url} (required), {@code path} (default {@code /{kind}/{id}}), {@code kinds}
 * (comma-separated; empty means any), {@code source-name} (default {@code rest}), {@code timeout-ms}
 * (default 2000), {@code generation-header} (a response header holding a monotonic version; default
 * {@code ETag} when numeric, else the fetch time), and {@code header.<Name>} for request headers
 * (for example {@code header.Authorization: Bearer ${SERVICE_TOKEN}}). A 404 means "not held here".
 * The HTTP client is shared and safe to call from many virtual threads.
 *
 * <p><b>TLS.</b> An {@code https://} {@code base-url} is checked against the JVM's authorities, or against the shared
 * {@code tls.*} settings ({@link com.ash.drishti.api.tls.TlsSettings}): a private CA ({@code tls.ca-file}), a truststore, and
 * a client certificate for mutual TLS ({@code tls.cert-file} + {@code tls.key-file}, or {@code tls.keystore}).
 */
public final class RestSourcePlugin implements SourcePlugin {

    private HttpClient http;
    private TlsMaterial tls;
    private SourceContext context;
    private String baseUrl;
    private String path;
    private String sourceName;
    private String generationHeader;
    private Duration timeout;
    private Set<String> kinds = Set.of();
    private final Map<String, String> headers = new LinkedHashMap<>();

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("rest", "1.0", kinds, SourceCapabilities.FETCH_ONLY);
    }

    @Override
    public void start(SourceContext ctx) {
        this.context = ctx;
        this.baseUrl = ctx.setting("base-url", "").replaceAll("/+$", "");
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("rest plugin needs settings.base-url");
        }
        this.path = ctx.setting("path", "/{kind}/{id}");
        this.sourceName = ctx.setting("source-name", "rest");
        this.generationHeader = ctx.setting("generation-header", "ETag");
        this.timeout = Duration.ofMillis(Long.parseLong(ctx.setting("timeout-ms", "2000")));
        String k = ctx.setting("kinds", "");
        this.kinds = k.isBlank() ? Set.of() : Arrays.stream(k.split(",")).map(String::trim).collect(Collectors.toUnmodifiableSet());
        ctx.settings().forEach((key, v) -> {
            if (key.startsWith("header.")) {
                headers.put(key.substring(7), v);
            }
        });
        HttpClient.Builder hb = HttpClient.newBuilder().connectTimeout(timeout);
        this.tls = TlsHttp.material(ctx.settings(), baseUrl, "base-url");      // fails the start, naming the setting and the file
        this.http = (tls == null ? hb : TlsHttp.apply(hb, tls)).build();
    }

    URI uri(EntityRef ref) {
        String p = path.replace("{kind}", enc(ref.kind())).replace("{id}", enc(ref.id()));
        return URI.create(baseUrl + p);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Override
    public Optional<EntityDocument> fetch(EntityRef ref) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(uri(ref)).timeout(timeout).header("Accept", "application/json").GET();
        headers.forEach(req::header);
        HttpResponse<InputStream> res = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = res.body()) {
            if (res.statusCode() == 404) {
                return Optional.empty();
            }
            if (res.statusCode() >= 400) {
                throw new IOException(sourceName + " answered HTTP " + res.statusCode() + " for " + ref);
            }
            long generation = res.headers().firstValue(generationHeader).map(v -> v.replace("\"", ""))
                    .filter(v -> v.matches("\\d+")).map(Long::parseLong).orElse(System.currentTimeMillis());
            return Optional.of(new EntityDocument(ref, context.parseJson(body), new Provenance(sourceName, generation, Instant.now(), false)));
        }
    }

    @Override
    public String health() {
        return http == null ? "DOWN: not started" : tls == null ? "UP" : tls.annotate("UP", Instant.now());
    }
}
