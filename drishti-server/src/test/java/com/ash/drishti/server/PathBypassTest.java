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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.security.TokenVerifier;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * SEC-02 (QA 2026-10-01): the token filter must decide on the path the server routes, not on the raw request line, so
 * {@code /api/v1;x/…} or {@code /api/%761/…} never reach a handler without a token. Requests whose path is not in
 * canonical form under {@code /api} and {@code /actuator} (path parameters, needlessly encoded characters, dot or
 * empty segments) are refused with a 400 problem. The requests go over a raw socket so that no client rewrites them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.sources.plugins.demo.settings.ticking=false", "drishti.identity.iterations=1000",
        "drishti.security.metrics-token=scrape-token-for-tests",
        "drishti.security.roles.quant.kinds[0]=*", "drishti.security.roles.quant.calc=true",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
class PathBypassTest {

    @LocalServerPort int port;
    @Autowired TokenVerifier tokens;

    /** Every variant from the QA scripts (t_bypass.py, t_bypass2.py) and LOG.md, sent without a token. */
    private static final List<String> API_VARIANTS = List.of(
            "/api/%761/packs", "/api/v1%2Fpacks", "/api/v1/../v1/packs", "/api/./v1/packs", "/api/v1;x/packs", "/./api/v1/packs",
            "/api/%761/admin/status", "/api/v1;x/admin/status",
            "/api/%761/sources", "/api/%761/business-date", "/api/%761/sutras", "/api/%761/rachana/schema",
            "/api/%761/views/trade/BBG-60000001", "/api/%761/search?q=trade", "/api/%761/entities/trade/BBG-60000001/raw",
            "/api/v1;x/about", "/api/v1;x/packs", "/api/v1;x/sources", "/api/v1;x/sutras", "/api/v1;x/sutras/problems",
            "/api/v1;x/sutras/irs-fixfloat/1", "/api/v1;x/sutras/irs-fixfloat/1/source", "/api/v1;x/rachana/schema",
            "/api/v1;x/business-date", "/api/v1;x/health/live", "/api/v1/sutras;x", "/api/v1/sutras/;x/problems",
            "/api/v1;x/alerts/suggestions", "/api/v1;/packs", "/api/v%31/packs", "/api/%2e/v1/packs", "/api/v1/%2e%2e/v1/packs",
            "/api//v1/sutras", "/API/v1/sutras", "/api/x/../v1/sutras", "/api/v1/./packs");

    /** The operational endpoints: only health is open. */
    private static final List<String> ACTUATOR_VARIANTS = List.of(
            "/actuator;x/prometheus", "/actuator/%70rometheus", "/actuator/health/..;/prometheus", "/actuator/health/../prometheus",
            "/actuator/health/%2e%2e/prometheus", "/actuator/health;x/../prometheus", "/actuator/health/%2E%2E/metrics",
            "/actuator/./prometheus", "/actuator//prometheus", "/api/docs;x", "/api/%64ocs", "/api/docs/..;/docs", "/api/./docs");

    @Test
    void noVariantOfAnApiPathIsServedWithoutAToken() throws IOException {
        assertThat(call("GET", "/api/v1/packs", null).status).isEqualTo(401);
        for (String path : API_VARIANTS) {
            Reply r = call("GET", path, null);
            assertThat(r.status).as(path + " -> " + r.body).isIn(400, 401, 404);
            if (path.contains(";") || path.contains("%76") || path.contains("%31") || path.contains("/./") || path.contains("/../")
                    || path.contains("%2e") || path.contains("//")) {
                assertProblem400(path, r);
            }
        }
    }

    @Test
    void noVariantOfAnOperationalPathIsServedWithoutAToken() throws IOException {
        assertThat(call("GET", "/actuator/prometheus", null).status).isEqualTo(401);
        assertThat(call("GET", "/actuator/health", null).status).isEqualTo(200);
        for (String path : ACTUATOR_VARIANTS) {
            Reply r = call("GET", path, null);
            assertThat(r.status).as(path + " -> " + r.body).isIn(400, 401, 404);
            assertThat(r.body).as(path).doesNotContain("# HELP").doesNotContain("\"openapi\"");
            if (!path.contains("%2E%2E") && !path.contains("%2e%2e")) {
                assertProblem400(path, r);
            }
        }
    }

    @Test
    void variantsAreRefusedEvenWithAToken() throws IOException {
        String admin = "Bearer " + tokens.mint("drishti-dev-admin", List.of("admin"), 300);
        assertThat(call("GET", "/api/v1/packs", admin).status).isEqualTo(200);
        assertProblem400("/api/v1;x/packs", call("GET", "/api/v1;x/packs", admin));
        assertProblem400("/api/%761/packs", call("GET", "/api/%761/packs", admin));
    }

    @Test
    void legitimateEncodedNamesAndIdsStillWork() throws IOException {
        String quinn = "Bearer " + tokens.mint("quinn", List.of("quant"), 300);
        // the console quotes names with urllib.parse.quote(name, safe=''): spaces, semicolons, parentheses are encoded
        Reply saved = call("PUT", "/api/v1/me/calc-snippets/Shift%20by%2010bp%20v2.1", quinn,
                "{\"code\":\"print(view.id)\",\"kind\":\"trade\"}");
        assertThat(saved.status).as(saved.toString()).isEqualTo(200);
        assertThat(saved.body).contains("\"name\":\"Shift by 10bp v2.1\"");
        assertThat(call("GET", "/api/v1/me/calc-snippets", quinn).body).contains("Shift by 10bp v2.1");
        assertThat(call("DELETE", "/api/v1/me/calc-snippets/Shift%20by%2010bp%20v2.1", quinn).status).isEqualTo(204);
        // an encoded semicolon, percent or non-ASCII letter is data, not a path parameter: it reaches the handler
        for (String id : List.of("A%3BB", "A%25B", "Caf%C3%A9%20Ltd", "A%3AB%40C", "A%28B%29")) {
            Reply r = call("DELETE", "/api/v1/me/calc-snippets/" + id, quinn);
            assertThat(r.status).as(id + " -> " + r.body).isEqualTo(404);
            assertThat(r.body).contains("DRS-1001");
        }
        // query strings are not paths: encoded characters there are fine
        assertThat(call("GET", "/api/v1/packs?x=%76%3B", quinn).status).isEqualTo(200);
    }

    private static void assertProblem400(String path, Reply r) {
        assertThat(r.status).as(path + " -> " + r.body).isEqualTo(400);
        assertThat(r.contentType).as(path).startsWith("application/problem+json");
        assertThat(r.body).as(path).contains("\"code\":\"DRS-5001\"").contains("\"status\":400");
    }

    private Reply call(String method, String rawPath, String auth) throws IOException {
        return call(method, rawPath, auth, null);
    }

    /** One HTTP/1.1 exchange with the request line exactly as given. */
    private Reply call(String method, String rawPath, String auth, String json) throws IOException {
        try (Socket s = new Socket("localhost", port)) {
            s.setSoTimeout(30_000);
            byte[] payload = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
            StringBuilder req = new StringBuilder(method).append(' ').append(rawPath).append(" HTTP/1.1\r\nHost: localhost\r\n")
                    .append("Connection: close\r\nAccept: application/json\r\n");
            if (auth != null) {
                req.append("Authorization: ").append(auth).append("\r\n");
            }
            if (json != null) {
                req.append("Content-Type: application/json\r\nContent-Length: ").append(payload.length).append("\r\n");
            }
            OutputStream out = s.getOutputStream();
            out.write(req.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            in.transferTo(buf);
            String text = buf.toString(StandardCharsets.UTF_8);
            int status = Integer.parseInt(text.substring(9, 12));
            int end = text.indexOf("\r\n\r\n");
            String head = end < 0 ? text : text.substring(0, end);
            String contentType = head.lines().filter(l -> l.regionMatches(true, 0, "Content-Type:", 0, 13))
                    .map(l -> l.substring(13).trim()).findFirst().orElse("");
            String body = end < 0 ? "" : text.substring(end + 4);
            boolean chunked = head.lines().anyMatch(l -> l.equalsIgnoreCase("Transfer-Encoding: chunked"));
            return new Reply(status, contentType, chunked ? dechunk(body) : body);
        }
    }

    private static String dechunk(String body) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (at < body.length()) {
            int eol = body.indexOf("\r\n", at);
            int size = Integer.parseInt(body.substring(at, eol).trim(), 16);
            if (size == 0) {
                break;
            }
            // sizes count bytes; the bodies here are ASCII apart from the odd name, so count on the UTF-8 bytes
            byte[] rest = body.substring(eol + 2).getBytes(StandardCharsets.UTF_8);
            String chunk = new String(rest, 0, size, StandardCharsets.UTF_8);
            out.append(chunk);
            at = eol + 2 + chunk.length() + 2;
        }
        return out.toString();
    }

    private record Reply(int status, String contentType, String body) {
        @Override
        public String toString() {
            return status + " " + body;
        }
    }
}
