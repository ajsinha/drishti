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

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-process stand-in for a model endpoint (loopback, an ephemeral port): speaks the OpenAI-compatible and the Anthropic
 * Messages response shapes and records every request, so tests prove what was sent without any real model or network.
 */
public final class FakeModel implements AutoCloseable {

    /** What the endpoint does next. */
    public enum Mode { OK, ERROR, SLOW, GARBAGE }

    public record Seen(String path, String authorization, String apiKey, String version, String body) {}

    private final HttpServer server;
    public final List<Seen> requests = new CopyOnWriteArrayList<>();
    public final AtomicInteger calls = new AtomicInteger();
    public volatile Mode mode = Mode.OK;
    public volatile String reply = "It is a value-at-risk result.";

    public FakeModel() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", ex -> {
            calls.incrementAndGet();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Seen(ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("x-api-key"),
                    ex.getRequestHeaders().getFirst("anthropic-version"), body));
            Mode m = mode;
            if (m == Mode.SLOW) {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            int status = m == Mode.ERROR ? 500 : 200;
            String out = m == Mode.GARBAGE ? "not json at all" : ex.getRequestURI().getPath().contains("messages")
                    ? "{\"content\":[{\"type\":\"text\",\"text\":" + quote(reply) + "}]}"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + quote(reply) + "}}]}";
            byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            try (var os = ex.getResponseBody()) {
                ex.sendResponseHeaders(status, bytes.length);
                os.write(bytes);
            } catch (IOException ignored) {
                // the client gave up (timeout test)
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-model");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    public Seen last() {
        return requests.get(requests.size() - 1);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
