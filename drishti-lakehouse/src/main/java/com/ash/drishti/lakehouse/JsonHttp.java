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
package com.ash.drishti.lakehouse;

import com.ash.drishti.api.proxy.ProxySettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * The HTTP calls of the lakehouse catalogs (Unity Catalog's REST API, a Delta Sharing server): JSON in and out, through the connector's
 * outbound proxy ({@code proxy.*} or the site default), with a timeout. A bearer token is added by the caller; it is never logged or
 * put in a message. Thread-safe.
 */
public final class JsonHttp {

    /** One answer: the status, the body (empty when there was none) and the headers. */
    public record Answer(int status, byte[] body, java.net.http.HttpHeaders headers) {

        /** The body as JSON (an empty object when there is no body or it is not JSON). */
        public JsonNode json() {
            try {
                return body.length == 0 ? JSON.createObjectNode() : JSON.readTree(body);
            } catch (IOException e) {
                return JSON.createObjectNode();
            }
        }

        /** The body as text, cut to 300 characters (for messages). */
        public String text() {
            String s = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            return s.length() > 300 ? s.substring(0, 300) + "…" : s;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    public static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final ProxySettings proxy;
    private final Duration timeout;

    /**
     * @param settings the connector settings ({@code proxy.*})
     * @param target the server's address (whether the proxy applies to it)
     * @param timeoutSeconds each call's limit
     */
    public JsonHttp(Map<String, String> settings, String target, long timeoutSeconds) {
        this.proxy = ProxySettings.resolve(settings, target);
        // no redirects followed: a bearer token is never carried to a host it was not given for
        this.http = proxy.apply(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER)).build();
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    /** The client itself (file downloads with their own requests). */
    public HttpClient client() {
        return http;
    }

    public ProxySettings proxy() {
        return proxy;
    }

    /**
     * Sends one request.
     *
     * @param body JSON to send (POST), a form ({@code String} starting with nothing special, sent as given with {@code contentType}), or null
     * @param headers extra headers ({@code Authorization})
     */
    public Answer send(String method, String url, Object body, String contentType, Map<String, String> headers) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("Accept", "application/json");
        headers.forEach(b::header);
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            byte[] bytes = body instanceof String s ? s.getBytes(java.nio.charset.StandardCharsets.UTF_8) : JSON.writeValueAsBytes(body);
            b.header("Content-Type", contentType == null ? "application/json" : contentType).method(method, HttpRequest.BodyPublishers.ofByteArray(bytes));
        }
        try {
            HttpResponse<byte[]> r = proxy.send(http, b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Answer(r.statusCode(), r.body() == null ? new byte[0] : r.body(), r.headers());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted calling " + strip(url), e);
        } catch (IOException e) {
            throw new IOException("cannot reach " + strip(url) + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
    }

    /** A URL without its query (a pre-signed URL's query is a credential). */
    public static String strip(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + "?…";
    }
}
