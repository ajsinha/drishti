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
package com.ash.drishti.server.bi.transform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Py nodes called on the console backend (wave B task 4): the server's side of {@code POST /internal/py/transform} and {@code /internal/py/measure}
 * against a stand-in console (the request it sends, the token, the answer it reads, a refusal mapped to its DRS code).
 */
class ConsolePythonRunnerTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path dir;
    HttpServer console;
    final AtomicReference<JsonNode> got = new AtomicReference<>();
    final AtomicReference<String> token = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        console = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        console.createContext("/internal/py/transform", x -> {
            got.set(JSON.readTree(x.getRequestBody()));
            token.set(x.getRequestHeaders().getFirst("X-Drishti-Runner-Token"));
            byte[] parquet = "PAR1-fake".getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/vnd.apache.parquet");
            x.getResponseHeaders().add("X-Drishti-Rows", "3");
            x.getResponseHeaders().add("X-Drishti-Ms", "12");
            x.getResponseHeaders().add("X-Drishti-Columns", "[{\"name\":\"desk\",\"type\":\"string\"},{\"name\":\"vol\",\"type\":\"decimal\"}]");
            x.sendResponseHeaders(200, parquet.length);
            x.getResponseBody().write(parquet);
            x.close();
        });
        console.createContext("/internal/py/measure", x -> {
            JsonNode req = JSON.readTree(x.getRequestBody());
            got.set(req);
            byte[] body = req.path("manifest").path("fail").asBoolean(false)
                    ? "{\"ok\": false, \"code\": \"DRS-9073\", \"status\": 403, \"detail\": \"the code tried the network\"}".getBytes(StandardCharsets.UTF_8)
                    : "{\"ok\": true, \"by\": [\"trades.desk\"], \"rows\": [[\"DESK-A\", 1.5]], \"ms\": 3}".getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        console.start();
    }

    @AfterEach
    void tearDown() {
        console.stop(0);
    }

    ConsolePythonRunner runner() {
        return new ConsolePythonRunner(TransformProperties.defaults(), "http://127.0.0.1:" + console.getAddress().getPort(), "s3cret");
    }

    @Test
    void aTransformIsSentWithItsFramesAndItsParquetAnswerIsKept() throws Exception {
        Path out = dir.resolve("out.parquet");
        TransformRunner.Result r = runner().run(new TransformRunner.Request(Map.of("name", "desk-vol"), List.of(new byte[] {1, 2, 3}), Map.of("bins", 24), out, 1000, 1 << 20));
        assertThat(token.get()).isEqualTo("s3cret");
        assertThat(got.get().path("inputs").get(0).asText()).isEqualTo("AQID");                   // base64 of the Arrow frame
        assertThat(got.get().path("params").path("bins").asInt()).isEqualTo(24);
        assertThat(Files.readString(out)).isEqualTo("PAR1-fake");
        assertThat(r.rows()).isEqualTo(3);
        assertThat(r.columns()).extracting(TransformRunner.Column::name).containsExactly("desk", "vol");
    }

    @Test
    void aMeasureAnswersItsGroupsAndARefusalKeepsItsCode() {
        JsonNode a = runner().measure(Map.of("name", "desk-vol"), Map.of(), List.of("trades.desk"), List.of("trades.desk", "mtm"), List.of(List.of("DESK-A", 1.0)));
        assertThat(a.path("rows").get(0).get(1).asDouble()).isEqualTo(1.5);
        assertThat(got.get().path("group_by").get(0).asText()).isEqualTo("trades.desk");
        assertThatThrownBy(() -> runner().measure(Map.of("fail", true), Map.of(), List.of(), List.of(), List.of())).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("DRS-9073");
        ConsolePythonRunner gone = new ConsolePythonRunner(TransformProperties.defaults(), "http://127.0.0.1:1", "x");
        assertThatThrownBy(() -> gone.measure(Map.of(), Map.of(), List.of(), List.of(), List.of())).hasMessageContaining("DRS-9074").hasMessageContaining("cannot be reached");
    }
}
