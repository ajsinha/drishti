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
package com.ash.drishti.server.collab.bridge;

import com.ash.drishti.server.collab.mail.OutboxChannel;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

/**
 * Posts one request to a bridge. Redirects are never followed (a redirect could send the post, and the URL's secret, elsewhere). The
 * answer's body is never read into a message or a log: only the status. Every failure is described without the URL (it is a secret
 * for Teams and Slack) and without the endpoint's own words.
 */
public final class BridgeClient {

    private final HttpClient http;
    private final Duration timeout;

    public BridgeClient(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
    }

    /**
     * Posts and returns the status when it is a success (2xx).
     *
     * @throws OutboxChannel.Permanent when the endpoint refused for good (4xx other than 408 and 429, or a redirect)
     * @throws IllegalStateException when trying again may help (a timeout, a connection failure, 5xx, 408, 429)
     */
    public int post(BridgeRegistry.Bridge bridge, BridgeFormat.Request request) {
        HttpRequest.Builder b = HttpRequest.newBuilder(bridge.url()).timeout(timeout).POST(HttpRequest.BodyPublishers.ofByteArray(request.body()));
        for (Map.Entry<String, String> h : request.headers().entrySet()) {
            b.header(h.getKey(), h.getValue());
        }
        HttpResponse<Void> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.discarding());
        } catch (HttpTimeoutException e) {
            throw new IllegalStateException("the endpoint did not answer in time");
        } catch (IOException e) {
            throw new IllegalStateException("the endpoint could not be reached (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while posting");
        }
        int s = r.statusCode();
        if (s >= 200 && s < 300) {
            return s;
        }
        if (s >= 300 && s < 400) {
            throw new OutboxChannel.Permanent("the endpoint answered HTTP " + s + " (a redirect); redirects are not followed: use the final URL");
        }
        if (s == 408 || s == 429 || s >= 500) {
            throw new IllegalStateException("the endpoint answered HTTP " + s);
        }
        throw new OutboxChannel.Permanent("the endpoint refused the post: HTTP " + s);
    }
}
