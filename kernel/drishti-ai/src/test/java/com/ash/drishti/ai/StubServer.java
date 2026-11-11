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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** A loopback HTTP server on an ephemeral port that answers with whatever the test says and records what it was sent. */
final class StubServer implements AutoCloseable {

    record Seen(String method, String path, String body, com.sun.net.httpserver.Headers headers) {}

    final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final HttpServer server;

    /** @param handler request body to {status, content-type, body} */
    StubServer(Function<Seen, String[]> handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", x -> {
            Seen s = new Seen(x.getRequestMethod(), x.getRequestURI().getPath(), new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), x.getRequestHeaders());
            seen.add(s);
            String[] r = handler.apply(s);
            reply(x, Integer.parseInt(r[0]), r[1], r[2], r.length > 3 ? r[3] : null);
        });
        server.start();
    }

    private static void reply(HttpExchange x, int status, String type, String body, String session) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", type);
        if (session != null) {
            x.getResponseHeaders().add("Mcp-Session-Id", session);
        }
        x.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            x.getResponseBody().write(b);
        }
        x.close();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
