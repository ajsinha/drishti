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
package com.ash.drishti.iceberg.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code catalog: s3tables} as drishti-iceberg speaks it, against a stand-in of S3 Tables' Iceberg REST endpoint in the test process
 * (AWS offers no local S3 Tables): the URLs it builds from the ARN ({@code /iceberg/v1/config?warehouse=<ARN>}, then
 * {@code /iceberg/v1/<the ARN URL-encoded>/namespaces/<domain>/tables/<kind as an S3 Tables name>}), every request signed with SigV4 for
 * {@code s3tables} in the ARN's region, and no request for vended credentials.
 */
class S3TablesRestTest {

    static final String P = "/iceberg/v1/" + S3TablesTest.ENCODED;

    HttpServer server;
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<String> refused = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null || ex.getRequestURI().getRawQuery().startsWith("pageToken=") || ex.getRequestURI().getRawQuery().startsWith("snapshots=") ? "" : "?" + ex.getRequestURI().getRawQuery());
            String auth = String.valueOf(ex.getRequestHeaders().getFirst("Authorization"));
            calls.add(ex.getRequestMethod() + " " + path + " | " + auth + " | " + ex.getRequestHeaders().getFirst("X-Iceberg-Access-Delegation"));
            String body;
            int status = 200;
            if (!auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/") || !auth.contains("/us-east-1/s3tables/aws4_request")) {
                status = 403;
                refused.add(ex.getRequestMethod() + " " + path);
                body = "{\"error\":{\"message\":\"not signed for s3tables\",\"type\":\"ForbiddenException\",\"code\":403}}";
            } else {
                switch (ex.getRequestMethod() + " " + path) {
                    case "GET /iceberg/v1/config?warehouse=" + S3TablesTest.ENCODED ->
                            body = "{\"defaults\":{},\"overrides\":{\"prefix\":\"" + S3TablesTest.ENCODED + "\"}}";
                    case "GET " + P + "/namespaces/risk_results" -> body = "{\"namespace\":[\"risk_results\"],\"properties\":{}}";
                    case "GET " + P + "/namespaces/risk_results/tables" ->
                            body = "{\"identifiers\":[{\"namespace\":[\"risk_results\"],\"name\":\"pnl_vector\"}]}";
                    default -> {
                        status = 404;
                        body = "{\"error\":{\"message\":\"no " + path + "\",\"type\":\"NoSuchTableException\",\"code\":404}}";
                    }
                }
            }
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    Map<String, String> settings() {
        Map<String, String> s = new HashMap<>();
        s.put("catalog", "s3tables");
        s.put("table-bucket-arn", S3TablesTest.ARN);
        s.put("uri", "http://127.0.0.1:" + server.getAddress().getPort() + "/iceberg");   // the stand-in in place of s3tables.<region>
        s.put("domain", "risk-results");
        s.put("catalog.rest.access-key-id", "AKIDEXAMPLE");
        s.put("catalog.rest.secret-access-key", "test-secret");
        return s;
    }

    @Test
    void urlsFromTheArnSignedForS3TablesAndKindsReadAsS3TablesNames() throws Exception {
        try (Catalog c = Catalogs.open(settings())) {
            assertThat(c.describe()).startsWith("S3 Tables ").contains("namespace risk_results");
            assertThat(c.reachable()).isTrue();
            assertThat(c.tables()).containsExactly("pnl_vector");
            assertThat(c.load("scenario-pv")).isEmpty();
            assertThat(c.missing("scenario-pv")).contains("scenario_pv").contains("the kind scenario-pv is the S3 Tables table scenario_pv");
        }
        assertThat(calls).allMatch(x -> !x.contains("| AWS4") || x.contains("/us-east-1/s3tables/aws4_request"));
        assertThat(refused).isEmpty();                     // the stand-in's refusals (a signature's hex may itself contain "403")
        assertThat(calls.get(0)).startsWith("GET /iceberg/v1/config?warehouse=" + S3TablesTest.ENCODED + " | AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/");
        assertThat(calls).anyMatch(x -> x.startsWith("GET " + P + "/namespaces/risk_results/tables/scenario_pv ") && x.endsWith("| null"));
    }

    @Test
    void anUnsignedRequestIsWhatTheStandInRefuses() {
        Map<String, String> s = settings();
        s.put("catalog", "rest");                        // a plain REST catalog: no SigV4, so the stand-in answers 403
        s.put("warehouse", S3TablesTest.ARN);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Catalogs.open(s)).hasMessageContaining("Forbidden");
    }
}
