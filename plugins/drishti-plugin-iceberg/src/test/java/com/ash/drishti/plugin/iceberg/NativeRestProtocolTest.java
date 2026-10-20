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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.iceberg.Table;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.catalog.Catalogs;
import com.ash.drishti.iceberg.catalog.CommitConflict;
import com.ash.drishti.iceberg.meta.TableUpdate;
import com.ash.drishti.testkit.FakeProxy;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The REST catalog protocol as drishti-iceberg speaks it, against a catalog stand-in in the test process: the OAuth2
 * client-credentials token sent on every call, {@code /v1/config}'s overrides (the {@code prefix}), table listing,
 * {@code loadTable} with the ETag (an unchanged table answers 304 and is not read again), the table's vended S3
 * credentials, a refused commit (409) as a conflict, and every call through the configured proxy.
 */
class NativeRestProtocolTest {

    HttpServer server;
    final List<String> calls = new CopyOnWriteArrayList<>();
    String metadata;

    void answer(HttpExchange ex, int status, String body, Map<String, String> headers) throws IOException {
        headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            ex.getResponseBody().write(b);
        }
        ex.close();
    }

    @BeforeEach
    void start() throws Exception {
        try (Stream<Path> s = Files.walk(Path.of("src/test/resources/iceberg-fixtures/fx/ref_currencies/metadata"))) {
            metadata = Files.readString(s.filter(p -> p.toString().endsWith(".metadata.json")).sorted().reduce((a, b) -> b).orElseThrow());
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            calls.add(ex.getRequestMethod() + " " + path + " " + auth + " " + ex.getRequestHeaders().getFirst("If-None-Match") + " "
                    + ex.getRequestHeaders().getFirst("X-Iceberg-Access-Delegation"));
            if (path.equals("/v1/oauth/tokens")) {
                String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean ok = form.contains("client_id=drishti") && form.contains("client_secret=s3cret") && form.contains("grant_type=client_credentials");
                answer(ex, ok ? 200 : 401, ok ? "{\"access_token\":\"tok-1\",\"token_type\":\"bearer\",\"expires_in\":3600}" : "{\"error\":\"invalid_client\"}",
                        Map.of());
                return;
            }
            if (!"Bearer tok-1".equals(auth)) {
                answer(ex, 401, "{\"error\":{\"message\":\"no token\",\"type\":\"NotAuthorizedException\",\"code\":401}}", Map.of());
                return;
            }
            switch (ex.getRequestMethod() + " " + path) {
                case "GET /v1/config?warehouse=wh" -> answer(ex, 200, "{\"defaults\":{},\"overrides\":{\"prefix\":\"p1\"}}", Map.of());
                case "GET /v1/p1/namespaces/risk%1Fdesk/tables" -> answer(ex, 200,
                        "{\"identifiers\":[{\"namespace\":[\"risk\",\"desk\"],\"name\":\"currencies\"}]}", Map.of());
                case "GET /v1/p1/namespaces/risk%1Fdesk/tables/currencies" -> {
                    if ("\"e1\"".equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
                        answer(ex, 304, null, Map.of("ETag", "\"e1\""));
                        return;
                    }
                    ObjectNode body = Fixtures.JSON.createObjectNode();
                    body.put("metadata-location", "s3://wh/risk/desk/currencies/metadata/00001.metadata.json");
                    body.set("metadata", Fixtures.JSON.readTree(metadata));
                    body.putObject("config").put("s3.access-key-id", "VK").put("s3.secret-access-key", "VS").put("s3.session-token", "VT")
                            .put("s3.endpoint", "http://vended.example:9000");
                    answer(ex, 200, body.toString(), Map.of("ETag", "\"e1\""));
                }
                case "POST /v1/p1/namespaces/risk%1Fdesk/tables/currencies" -> answer(ex, 409,
                        "{\"error\":{\"message\":\"Requirement failed: branch main has changed\",\"type\":\"CommitFailedException\",\"code\":409}}", Map.of());
                default -> answer(ex, 404, "{\"error\":{\"message\":\"no " + path + "\",\"type\":\"NoSuchTableException\",\"code\":404}}", Map.of());
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    Map<String, String> settings() {
        Map<String, String> s = new HashMap<>();
        s.put("catalog", "rest");
        s.put("engine", "native");
        s.put("uri", "http://127.0.0.1:" + server.getAddress().getPort());
        s.put("warehouse", "wh");
        s.put("credential", "drishti:s3cret");
        s.put("namespace", "risk.desk");
        return s;
    }

    @Test
    void tokenConfigListingLoadWithETagVendedCredentialsAndAConflict() throws Exception {
        try (Catalog c = Catalogs.open(settings())) {
            assertThat(c.tables()).containsExactly("currencies");
            Table t = c.load("currencies").orElseThrow();
            assertThat(t.metadata().schema().field("CCY")).isNotNull();
            assertThat(t.metadataLocation()).endsWith("00001.metadata.json");
            assertThat(t.refresh()).isFalse();                                    // 304: the same table
            var s3 = t.io().s3Settings();
            assertThat(s3.accessKey()).isEqualTo("VK");
            assertThat(s3.sessionToken()).isEqualTo("VT");
            assertThat(s3.endpoint()).isEqualTo("http://vended.example:9000");
            assertThatThrownBy(() -> t.ops().commit(t.state(), new TableUpdate().requireMainAt(1L).setProperties(Map.of("a", "b"))))
                    .isInstanceOf(CommitConflict.class).hasMessageContaining("branch main has changed");
            assertThat(c.load("nope")).isEmpty();
        }
        assertThat(calls.stream().filter(x -> x.startsWith("POST /v1/oauth/tokens")).count()).isEqualTo(1);          // fetched once, reused
        assertThat(calls).anyMatch(x -> x.startsWith("GET /v1/p1/namespaces/risk%1Fdesk/tables/currencies Bearer tok-1 \"e1\" vended-credentials"));
        assertThat(calls).allMatch(x -> x.startsWith("POST /v1/oauth/tokens") || x.contains("Bearer tok-1"));
    }

    @Test
    void aRefusedCredentialSaysSo() {
        Map<String, String> s = settings();
        s.put("credential", "drishti:wrong");
        assertThatThrownBy(() -> Catalogs.open(s)).hasMessageContaining("refused the credential").hasMessageContaining("401");
    }

    @Test
    void everyCallGoesThroughTheProxy() throws Exception {
        try (FakeProxy proxy = FakeProxy.start()) {
            Map<String, String> s = settings();
            s.put("proxy.url", proxy.url());
            try (Catalog c = Catalogs.open(s)) {
                assertThat(c.tables()).containsExactly("currencies");
            }
            assertThat(proxy.forwarded()).isGreaterThanOrEqualTo(3);                 // token, config, listing
        }
    }
}
