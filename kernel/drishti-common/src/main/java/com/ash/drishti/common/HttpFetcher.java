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
package com.ash.drishti.common;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One bounded HTTP GET with the shared retry shape: a connection failure, a timeout, a 429 or a 5xx is repeated up to
 * {@code retries} times with {@link RetryPolicy} backoff and jitter; any other 4xx, an oversized body or a body that is not
 * wanted fails at once, because waiting will not change it. The body is read only up to {@code max-bytes}.
 */
public final class HttpFetcher {

    /** What a fetch needs of a connector's settings. */
    public interface Limits {
        int retries();

        RetryPolicy retryPolicy();

        Duration timeout();

        int maxBytes();

        String userAgent();
    }

    /** A failed fetch; {@link #permanent()} says repeating cannot help. */
    public static final class FetchException extends IOException {
        private final boolean permanent;
        private final int status;

        FetchException(String message, boolean permanent, Throwable cause) {
            this(message, permanent, 0, cause);
        }

        FetchException(String message, boolean permanent, int status, Throwable cause) {
            super(message, cause);
            this.permanent = permanent;
            this.status = status;
        }

        /** The HTTP status, or 0 when there was none. */
        public int status() {
            return status;
        }

        public boolean permanent() {
            return permanent;
        }
    }

    private final HttpClient http;
    private final com.ash.drishti.api.proxy.ProxySettings proxy;
    private final Limits settings;
    private final String accept;

    /** @param accept the Accept header of every request */
    public HttpFetcher(HttpClient http, Limits settings, com.ash.drishti.api.proxy.ProxySettings proxy, String accept) {
        this.http = http;
        this.settings = settings;
        this.proxy = proxy;
        this.accept = accept;
    }

    public byte[] get(URI uri) throws IOException, InterruptedException {
        FetchException last = null;
        for (int attempt = 0; attempt <= settings.retries(); attempt++) {
            if (attempt > 0) {
                Thread.sleep(settings.retryPolicy().delay(attempt, false, ThreadLocalRandom.current().nextDouble()));
            }
            try {
                return once(uri);
            } catch (FetchException e) {
                last = e;
                if (e.permanent()) {
                    break;
                }
            }
        }
        throw last;
    }

    private byte[] once(URI uri) throws FetchException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(settings.timeout()).header("User-Agent", settings.userAgent())
                .header("Accept", accept).GET().build();
        long deadline = System.nanoTime() + settings.timeout().toNanos();
        try {
            HttpResponse<InputStream> r = proxy.send(http, req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                int code = r.statusCode();
                if (code / 100 != 2) {
                    boolean again = code == 429 || code / 100 == 5;
                    throw new FetchException("HTTP " + code + " from " + uri.getHost(), !again, code, null);
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    if (out.size() > settings.maxBytes()) {
                        throw new FetchException("the response from " + uri.getHost() + " is larger than max-bytes (" + settings.maxBytes() + ")", true, null);
                    }
                    if (System.nanoTime() > deadline) {
                        throw new FetchException("timed out reading the response from " + uri.getHost(), false, null);
                    }
                }
                return out.toByteArray();
            }
        } catch (java.net.http.HttpTimeoutException e) {
            throw new FetchException("timed out after " + Duration.ofNanos(settings.timeout().toNanos()).toSeconds() + " s waiting for " + uri.getHost(), false, e);
        } catch (IOException e) {
            if (e instanceof FetchException f) {
                throw f;
            }
            throw new FetchException(e.getClass().getSimpleName() + ": " + e.getMessage(), false, e);
        }
    }
}
