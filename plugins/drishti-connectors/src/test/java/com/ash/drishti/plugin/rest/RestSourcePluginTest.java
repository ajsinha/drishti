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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.common.JsonCodec;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

class RestSourcePluginTest {

    @Test
    void fetchesJsonWithHeadersAndGeneration() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", ex -> {
            String p = ex.getRequestURI().getRawPath();
            byte[] body;
            if (p.equals("/api/trade/T%201") && "Bearer x".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                body = "{\"tradeId\":\"T 1\",\"mtm\":5}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("ETag", "\"77\"");
                ex.sendResponseHeaders(200, body.length);
            } else if (p.contains("BOOM")) {
                body = new byte[0];
                ex.sendResponseHeaders(500, -1);
            } else {
                body = new byte[0];
                ex.sendResponseHeaders(404, -1);
            }
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            JsonCodec codec = new JsonCodec();
            RestSourcePlugin p = new RestSourcePlugin();
            p.start(new SourceContext() {
                public Map<String, String> settings() {
                    return Map.of("base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/api/", "header.Authorization", "Bearer x",
                            "source-name", "trade-store");
                }

                public DataNode parseJson(InputStream in) throws IOException {
                    return codec.read(in);
                }

                public ScheduledExecutorService scheduler() {
                    return Executors.newSingleThreadScheduledExecutor();
                }
            });
            var d = p.fetch(EntityRef.of("trade", "T 1")).orElseThrow();
            assertThat(d.data().get("mtm").asDouble()).isEqualTo(5);
            assertThat(d.provenance().generation()).isEqualTo(77);
            assertThat(d.provenance().source()).isEqualTo("trade-store");
            assertThat(p.fetch(EntityRef.of("trade", "NOPE"))).isEmpty();
            assertThatThrownBy(() -> p.fetch(EntityRef.of("trade", "BOOM"))).hasMessageContaining("HTTP 500");
        } finally {
            server.stop(0);
        }
    }
}
