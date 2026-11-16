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
package com.ash.drishti.ai;

import com.ash.drishti.api.proxy.ProxySettings;
import com.ash.drishti.api.tls.TlsHttp;
import com.ash.drishti.api.tls.TlsMaterial;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The HTTP part the JSON providers share ({@code sajha/ai/llm/http.py}; the console's {@code JsonHttpProvider}): one JDK client per provider with the shared
 * {@code proxy.*} and {@code tls.*} settings (an https target is tunnelled with CONNECT and keeps its TLS), JSON and form calls, retries, and the error mapping.
 * A status becomes an {@link LlmException} without the body (a body can echo a prompt): 429 is {@code RATE_LIMITED}, a timeout is {@code TIMEOUT}, everything else
 * {@code FAILED} with a hint for the usual causes (401/403 key or permission, 404 path or model).
 */
public final class JsonHttp implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY = 8_000_000;

    private final ProviderConfig config;
    private final HttpClient client;
    private final ProxySettings proxy;
    private final TlsMaterial tls;
    private final String base;

    public JsonHttp(ProviderConfig c, String base) {
        this.config = c;
        this.base = base.replaceAll("/+$", "");
        String probe = this.base.isEmpty() ? "http://localhost" : this.base;
        try {
            URI.create(probe);
        } catch (IllegalArgumentException e) {
            throw new LlmException(LlmException.Kind.CONFIG, "base-url is not a valid URL");
        }
        this.proxy = ProxySettings.resolve(c.settings(), probe);
        HttpClient.Builder b = proxy.apply(HttpClient.newBuilder().connectTimeout(c.timeout()).followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1));
        this.tls = TlsHttp.material(c.settings(), probe, "base-url");
        this.client = (tls == null ? b : TlsHttp.apply(b, tls)).build();
    }

    public String base() {
        return base;
    }

    public TlsMaterial tls() {
        return tls;
    }

    public ProxySettings proxy() {
        return proxy;
    }

    /** The headers every call carries: {@code header.<Name>} from the file. */
    public Map<String, String> fileHeaders() {
        Map<String, String> h = new LinkedHashMap<>();
        config.settings().forEach((k, v) -> {
            if (k.startsWith("header.")) {
                h.put(k.substring(7), v);
            }
        });
        return h;
    }

    public String tlsNote() {
        return tls == null ? "" : tls.expiryNote(Instant.now());
    }

    public List<Map<String, String>> tlsCertificates() {
        List<Map<String, String>> out = new ArrayList<>();
        if (tls != null) {
            tls.certificates().forEach(c -> out.add(Map.of("subject", String.valueOf(c.subject()), "source", String.valueOf(c.source()),
                    "notAfter", c.notAfter().toString().substring(0, 10))));
        }
        return out;
    }

    /** A JSON call. {@code body} may be null (GET). */
    public JsonNode call(String method, String url, JsonNode body, Map<String, String> headers, Duration timeout) {
        String payload;
        try {
            payload = body == null ? null : JSON.writeValueAsString(body);
        } catch (IOException e) {
            throw new LlmException(LlmException.Kind.FAILED, "the request could not be written");
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        h.putAll(fileHeaders());
        h.putAll(headers);
        return send(method, url, payload, h, timeout);
    }

    /** A form POST ({@code application/x-www-form-urlencoded}), for token endpoints. */
    public JsonNode postForm(String url, Map<String, String> form, Duration timeout) {
        String payload = form.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        return send("POST", url, payload, Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "application/json"), timeout);
    }

    /** The status as an exception: what the caller shows, without the body. */
    public static LlmException statusError(int code) {
        String hint = switch (code) {
            case 401 -> "authentication failed: check the api key and where it is read from";
            case 403 -> "access denied: the key lacks permission for this model or endpoint";
            case 404 -> "not found: check base-url, the path and the model id";
            case 408 -> "the endpoint timed out";
            case 413 -> "the request is too large";
            case 429 -> "rate limited: slow down, raise the account limit or add retries";
            default -> null;
        };
        return new LlmException(code == 429 ? LlmException.Kind.RATE_LIMITED : LlmException.Kind.FAILED,
                "the model endpoint answered HTTP " + code + (hint == null ? "" : " (" + hint + ")"));
    }

    private JsonNode send(String method, String url, String payload, Map<String, String> headers, Duration timeout) {
        LlmException last = null;
        for (int attempt = 0; attempt <= config.retries(); attempt++) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout != null ? timeout : config.timeout());
                headers.forEach(b::header);
                b.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
                HttpResponse<InputStream> resp = proxy.send(client, b.build(), HttpResponse.BodyHandlers.ofInputStream());
                byte[] bytes;
                try (InputStream in = resp.body()) {
                    bytes = in.readNBytes(MAX_BODY);
                }
                int code = resp.statusCode();
                if (code / 100 != 2) {
                    last = statusError(code);
                    if (code < 500 && code != 429) {
                        throw last;
                    }
                    continue;
                }
                JsonNode j = JSON.readTree(bytes);
                if (j == null || !j.isObject()) {
                    last = new LlmException(LlmException.Kind.FAILED, "the model endpoint answered something unreadable");
                    continue;
                }
                return j;
            } catch (java.net.http.HttpTimeoutException | java.net.SocketTimeoutException e) {
                last = new LlmException(LlmException.Kind.TIMEOUT, "the model endpoint did not answer in time", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException(LlmException.Kind.FAILED, "the model call was interrupted", e);
            } catch (IOException e) {
                last = new LlmException(LlmException.Kind.FAILED, "the model endpoint could not be reached or answered something unreadable", proxy.explain(e));
            } catch (IllegalArgumentException e) {
                throw new LlmException(LlmException.Kind.CONFIG, "base-url is not a valid URL");
            }
        }
        throw last != null ? last : new LlmException(LlmException.Kind.FAILED, "the model call failed");
    }

    @Override
    public void close() {
        // the JDK client holds no resources of its own to release before Java 21; a client built on 21 is closed by the garbage collector
    }
}
