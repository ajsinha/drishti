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
package com.ash.drishti.server.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The JDK's HTTP client talking JSON to a model endpoint; subclasses fix the shape of the request and of the answer. The
 * client is built once and shared (it is thread-safe). Nothing here logs a body or a header.
 */
abstract class HttpAskProvider implements AskProvider {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY = 1_000_000;

    protected final AskProperties cfg;
    private final HttpClient client;

    HttpAskProvider(AskProperties cfg) {
        this.cfg = cfg;
        this.client = HttpClient.newBuilder().connectTimeout(cfg.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** The JSON body of the request. */
    abstract JsonNode request(String system, String user, int maxTokens);

    /** The text of the model's answer, from the response body. */
    abstract String text(JsonNode response) throws Failure;

    /** Headers that authenticate (and version) the call. */
    abstract void headers(HttpRequest.Builder b);

    @Override
    public final String complete(String system, String user, int maxTokens, Duration timeout) throws Failure {
        if (cfg.endpoint().isBlank()) {
            throw new Failure("Ask has no endpoint configured (drishti.explain.ask.endpoint)", false, null);
        }
        HttpRequest.Builder b;
        try {
            b = HttpRequest.newBuilder(URI.create(cfg.endpoint())).timeout(timeout).header("Content-Type", "application/json")
                    .header("Accept", "application/json");
        } catch (IllegalArgumentException e) {
            throw new Failure("Ask endpoint is not a valid URL", false, e);
        }
        headers(b);
        try {
            b.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request(system, user, maxTokens)), StandardCharsets.UTF_8));
            HttpResponse<java.io.InputStream> r = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (java.io.InputStream in = r.body()) {
                body = in.readNBytes(MAX_BODY);
            }
            if (r.statusCode() / 100 != 2) {
                throw new Failure("the model endpoint answered HTTP " + r.statusCode(), false, null);
            }
            return text(JSON.readTree(body));
        } catch (java.net.http.HttpTimeoutException | java.net.SocketTimeoutException e) {
            throw new Failure("the model endpoint did not answer within " + timeout.toSeconds() + "s", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure("the model call was interrupted", false, e);
        } catch (IOException e) {
            throw new Failure("the model endpoint could not be reached or answered something unreadable", false, e);
        }
    }
}
