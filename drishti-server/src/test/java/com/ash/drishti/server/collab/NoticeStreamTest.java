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
package com.ash.drishti.server.collab;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The bell: a share sent to a user arrives as a {@code notice} event on the one stream the console already holds open
 * ({@code /me/alerts/stream}), rendered for that reader, with no data value in it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/noticestream-${random.uuid}/identity.db",
        "drishti.packs.overlay=target/noticestream-overlay/added.yaml", "drishti.collab.inbox.poll=100ms"})
class NoticeStreamTest {

    @LocalServerPort int port;
    @Autowired TokenVerifier tokens;
    private final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private HttpResponse<String> call(HttpClient c, String method, String path, String who, String role, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Authorization", as(who, role))
                .header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aShareArrivesAsANoticeEventOnTheAlertStreamForTheRecipientOnly() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        try {
            for (String u : List.of("ann", "ravi", "rng")) {
                assertThat(call(client, "POST", "/api/v1/admin/users", "drishti-dev-admin", "admin", Map.of("username", u, "roles", List.of("trader"),
                        "packs", List.of("finance"), "password", "long-enough-pass-1")).statusCode()).isEqualTo(201);
            }
            List<String> ravi = new CopyOnWriteArrayList<>();
            List<String> rng = new CopyOnWriteArrayList<>();
            open(client, "ravi", ravi);
            open(client, "rng", rng);
            long deadline = System.currentTimeMillis() + 5000;
            while ((!ravi.contains("event:hello") || !rng.contains("event:hello")) && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertThat(ravi).contains("event:hello");

            HttpResponse<String> sent = call(client, "POST", "/api/v1/shares", "ann", "trader",
                    Map.of("kind", "trade", "id", "IRS-48213", "note", "A. Shah asked about this", "to", Map.of("users", List.of("ravi"))));
            assertThat(sent.statusCode()).as(sent.body()).isEqualTo(201);

            deadline = System.currentTimeMillis() + 5000;
            while (ravi.stream().noneMatch(l -> l.contains("shareId")) && System.currentTimeMillis() < deadline) {
                Thread.sleep(25);
            }
            assertThat(ravi).contains("event:notice");
            String data = ravi.stream().filter(l -> l.startsWith("data:") && l.contains("shareId")).findFirst().orElseThrow();
            assertThat(data).contains("\"type\":\"share\"").contains("\"access\":true").contains("shared trade IRS-48213")
                    .contains("asked about this").doesNotContain("A. Shah");
            Thread.sleep(300);
            assertThat(rng).as("someone who was not addressed hears nothing").noneMatch(l -> l.contains("notice"));
        } finally {
            client.shutdownNow();
        }
    }

    private void open(HttpClient client, String user, List<String> into) {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/me/alerts/stream"))
                .header("Authorization", as(user, "trader")).header("Accept", "text/event-stream").build();
        client.sendAsync(req, HttpResponse.BodyHandlers.ofLines()).thenAccept(r -> {
            try (Stream<String> lines = r.body()) {
                lines.forEach(into::add);
            }
        });
    }
}
