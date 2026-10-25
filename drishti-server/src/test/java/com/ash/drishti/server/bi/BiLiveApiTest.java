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
package com.ash.drishti.server.bi;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.engine.ArrowTestReader;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Rupaka phase 5 over the whole stack: a real server on a random port with a ticking demo source (security on), the shipped live sample datasets, reports and
 * board. The live dataset follows the trades the demo ticker moves; a report's stream says when it changed; a visual over it is answered for each reader with
 * their masks on every update; a Perspective visual loads as masked Arrow and then asks only for the rows that changed; the streams are limited per person; a
 * board resolves its tiles and refuses what is wrong with its code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"drishti.sources.plugins.demo.settings.tick-ms=60", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.security.registered-users-only=false",
        "drishti.packs.enabled=trading,counterparty-risk,market-risk", "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/bi-live-${random.uuid}/identity.db",
        "drishti.packs.overlay=${drishti.test.dir}/bi-live-overlay/added.yaml", "drishti.bi.dir=${drishti.test.dir}/bi-live-folder", "drishti.bi.folder-refresh=1s",
        "drishti.bi.query.store-dir=${drishti.test.dir}/bi-live-store/${random.uuid}", "drishti.bi.live.batch=30ms", "drishti.bi.live.frame=50ms",
        "drishti.bi.live.status=1s", "drishti.bi.live.max-streams-per-user=2"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BiLiveApiTest {

    static final String REPORT = "file:report:live-trading-desk";
    static final String BOARD = "file:report:desk-wall";

    @LocalServerPort int port;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static {
        try {
            for (String kind : List.of("datasets", "models", "reports")) {                       // a board is a report with board pages since 2.4.0 (R7)
                Path to = com.ash.drishti.server.TestRun.root().resolve("bi-live-folder/" + kind);
                Files.createDirectories(to);
                try (var files = Files.list(Path.of("../config/bi/" + kind))) {
                    for (Path f : files.filter(p -> p.getFileName().toString().contains("live") || p.getFileName().toString().startsWith("desk-wall")).toList()) {
                        Files.copy(f, to.resolve(f.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    record Answer(int status, byte[] body, Map<String, List<String>> headers) {
        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 600);
    }

    Answer call(String method, String path, String who, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Authorization", who).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Answer(r.statusCode(), r.body(), r.headers().map());
    }

    JsonNode ok(Answer a) throws Exception {
        assertThat(a.status()).as(a.text()).isBetween(200, 299);
        return json.readTree(a.body());
    }

    @BeforeAll
    void roles() throws Exception {
        String admin = as("drishti-dev-admin", "admin");
        for (String[] r : List.of(new String[] {"live-risk", "true"}, new String[] {"live-analyst", "false"})) {
            Answer a = call("PUT", "/api/v1/admin/role-definitions/" + r[0], admin, "{\"kinds\":[\"*\"],\"approve\":false,\"raw\":" + r[1] + ",\"bi\":[\"bi\"]}");
            assertThat(a.status()).as(a.text()).isEqualTo(200);
        }
        Answer a = call("PUT", "/api/v1/admin/role-definitions/live-author", admin, "{\"kinds\":[\"*\"],\"approve\":false,\"raw\":true,\"bi\":[\"bi\",\"bi-author\"]}");
        assertThat(a.status()).as(a.text()).isEqualTo(200);
    }

    String rita() {
        return as("rita", "live-risk");
    }

    String ann() {
        return as("ann", "live-analyst");
    }

    /** Events (name and data) read from an SSE path until the condition holds or ten seconds pass. */
    List<String[]> stream(String path, String who, java.util.function.Predicate<List<String[]>> done) throws Exception {
        HttpResponse<java.io.InputStream> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Authorization", who)
                .header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
        List<String[]> events = new CopyOnWriteArrayList<>();
        if (r.statusCode() != 200) {
            events.add(new String[] {"http", String.valueOf(r.statusCode()), new String(r.body().readAllBytes(), StandardCharsets.UTF_8)});
            return events;
        }
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
                String event = null;
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:") && event != null) {
                        events.add(new String[] {event, line.substring(5)});
                    }
                }
            } catch (java.io.IOException ignored) {
                // closed by the test
            }
        });
        long end = System.currentTimeMillis() + 30_000;     // stops as soon as done: the window only matters on a loaded machine (the drill runs Java 25 beside the console suite)
        while (System.currentTimeMillis() < end && !done.test(events)) {
            Thread.sleep(20);
        }
        r.body().close();
        reader.interrupt();
        return new ArrayList<>(events);
    }

    // ---- the tests ---------------------------------------------------------------------------------------------------

    @Test @Order(1)
    void aReportOverALiveDatasetIsDrawnFromTheLiveTableAndSaysItIsLive() throws Exception {
        JsonNode rep = ok(call("GET", "/api/v1/bi/reports/" + REPORT, rita(), null));
        assertThat(rep.path("valid").asBoolean()).as(rep.path("problems").toString()).isTrue();
        assertThat(rep.path("dataset").path("tables").get(0).path("mode").asText()).isEqualTo("live");
        JsonNode card = ok(call("POST", "/api/v1/bi/reports/" + REPORT + "/query", rita(), "{\"page\":\"desk\",\"visual\":\"trades\",\"state\":{}}"));
        assertThat(card.path("meta").path("layer").asText()).isEqualTo("live");
        assertThat(card.path("rows").get(0).get(0).asLong()).isGreaterThan(100);
        assertThat(card.path("live").path("state").asText()).isEqualTo("live");
        assertThat(card.path("live").path("tables").get(0).path("pushes").asBoolean()).as("the demo source pushes its live trades").isTrue();
        assertThat(card.path("live").path("tables").get(0).path("subscribed").asInt()).isGreaterThan(100);
    }

    @Test @Order(2)
    void theStreamHelloesThenTicksWhenTheTablesChangeAndCarriesNoData() throws Exception {
        List<String[]> events = stream("/api/v1/bi/reports/" + REPORT + "/live", rita(), e -> e.stream().filter(x -> x[0].equals("tick")).count() >= 3);
        assertThat(events.get(0)[0]).isEqualTo("hello");
        List<String[]> ticks = events.stream().filter(x -> x[0].equals("tick")).toList();
        assertThat(ticks).hasSizeGreaterThanOrEqualTo(3);
        long previous = json.readTree(events.get(0)[1]).path("generation").asLong();
        for (String[] t : ticks) {
            JsonNode n = json.readTree(t[1]);
            assertThat(n.path("generation").asLong()).as("each tick is a newer state").isGreaterThan(previous);
            previous = n.path("generation").asLong();
            assertThat(n.has("rows")).as("a tick tells that the tables changed, not what is in them").isFalse();
            assertThat(n.path("state").asText()).isEqualTo("live");
        }
        assertThat(events.get(0)[1]).doesNotContain("\"TRADER_ID\"");
    }

    @Test @Order(3)
    void everyReaderIsAnsweredAgainAfterAnUpdateWithTheirOwnMasks() throws Exception {
        String body = "{\"page\":\"grid\",\"visual\":\"all-trades\",\"state\":{\"limit\":50}}";
        JsonNode risk = ok(call("POST", "/api/v1/bi/reports/" + REPORT + "/query", rita(), body));
        JsonNode analyst = ok(call("POST", "/api/v1/bi/reports/" + REPORT + "/query", ann(), body));
        int trader = columnIndex(risk, "TRADER_ID");
        assertThat(risk.path("rows").get(0).get(trader).asText()).startsWith("TRDR-");
        assertThat(analyst.path("rows").get(0).get(trader).asText()).isEqualTo("•••");
        assertThat(analyst.path("meta").path("masked").toString()).contains("TRADER_ID");
        long gen = risk.path("live").path("generation").asLong();
        long end = System.currentTimeMillis() + 30_000;     // stops as soon as done: the window only matters on a loaded machine (the drill runs Java 25 beside the console suite)
        JsonNode later = risk;
        while (System.currentTimeMillis() < end && later.path("live").path("generation").asLong() == gen) {
            Thread.sleep(100);
            later = ok(call("POST", "/api/v1/bi/reports/" + REPORT + "/query", rita(), body));
        }
        assertThat(later.path("live").path("generation").asLong()).as("the table keeps changing").isGreaterThan(gen);
        assertThat(ok(call("POST", "/api/v1/bi/reports/" + REPORT + "/query", ann(), body)).path("rows").get(0).get(trader).asText()).as("still masked after the update").isEqualTo("•••");
    }

    static int columnIndex(JsonNode answer, String name) {
        for (int i = 0; i < answer.path("columns").size(); i++) {
            if (answer.path("columns").get(i).path("name").asText().equals(name)) {
                return i;
            }
        }
        throw new AssertionError(name + " is not a column of " + answer.path("columns"));
    }

    @Test @Order(4)
    void aPerspectiveVisualLoadsAsMaskedArrowThenAsksOnlyForTheRowsThatChanged() throws Exception {
        String path = "/api/v1/bi/reports/" + REPORT + "/query?format=arrow";
        String snapshot = "{\"page\":\"grid\",\"visual\":\"all-trades\",\"state\":{}}";
        Answer risk = call("POST", path, rita(), snapshot);
        assertThat(risk.status()).as(risk.text()).isEqualTo(200);
        assertThat(risk.headers().get("content-type").get(0)).isEqualTo("application/vnd.apache.arrow.stream");
        ArrowTestReader.Table table = ArrowTestReader.read(risk.body());
        assertThat(table.rows()).isGreaterThan(100);
        assertThat(table.columns()).containsKeys("TRADE_ID", "TRADER_ID", "MTM", "id", "updateSeq");
        assertThat(table.columns().get("TRADER_ID").get(0).toString()).startsWith("TRDR-");
        ArrowTestReader.Table masked = ArrowTestReader.read(call("POST", path, ann(), snapshot).body());
        assertThat(masked.columns().get("TRADER_ID")).allMatch("•••"::equals);
        assertThat(masked.rows()).isEqualTo(table.rows());
        JsonNode live = json.readTree(risk.headers().get("x-bi-live").get(0));
        long seq = live.path("tables").get(0).path("seq").asLong();
        assertThat(seq).isGreaterThanOrEqualTo(table.rows());
        // the next answers carry only what changed since: every row's updateSeq is above the one asked for
        long end = System.currentTimeMillis() + 30_000;     // stops as soon as done: the window only matters on a loaded machine (the drill runs Java 25 beside the console suite)
        JsonNode delta = null;
        while (System.currentTimeMillis() < end) {
            delta = ok(call("POST", "/api/v1/bi/reports/" + REPORT + "/query", rita(), "{\"page\":\"grid\",\"visual\":\"all-trades\",\"state\":{\"since\":" + seq + "}}"));
            if (delta.path("rowCount").asInt() > 0) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(delta).isNotNull();
        assertThat(delta.path("rowCount").asInt()).isBetween(1, table.rows());
        int s = columnIndex(delta, "updateSeq");
        for (JsonNode row : delta.path("rows")) {
            assertThat(row.get(s).asLong()).isGreaterThan(seq);
        }
    }

    @Test @Order(5)
    void aThirdStreamForOnePersonIsRefusedWithItsCode() throws Exception {
        String who = as("pat", "live-risk");
        String path = "/api/v1/bi/reports/" + REPORT + "/live";
        List<Thread> held = new ArrayList<>();
        List<java.io.InputStream> open = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            HttpResponse<java.io.InputStream> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Authorization", who).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertThat(r.statusCode()).isEqualTo(200);
            open.add(r.body());
        }
        Answer third = call("GET", path, who, null);
        assertThat(third.status()).isEqualTo(429);
        assertThat(third.text()).contains("DRS-9051");
        open.forEach(in -> {
            try {
                in.close();
            } catch (java.io.IOException ignored) {
                // closing
            }
        });
        assertThat(held).isEmpty();
    }

    @Test @Order(6)
    void aBoardResolvesItsTilesToReportsAndTheirLiveness() throws Exception {
        JsonNode board = ok(call("GET", "/api/v1/bi/boards/" + BOARD, rita(), null));
        assertThat(board.path("valid").asBoolean()).as(board.path("problems").toString()).isTrue();
        assertThat(board.path("board").path("pages")).hasSize(2);
        assertThat(board.path("board").path("rotate").asText()).isEqualTo("20s");
        JsonNode tile = board.path("tiles").path("desk/net-mtm");
        assertThat(tile.path("report").asText()).isEqualTo(REPORT);
        assertThat(tile.path("live").asBoolean()).isTrue();
        assertThat(tile.path("type").asText()).isEqualTo("card");
        assertThat(board.path("tiles").path("exposure/netmtm").path("report").asText()).isEqualTo("file:report:live-exposure");
    }

    @Test @Order(7)
    void aBoardWithAWrongTileOrAReservedSettingIsRefusedWithItsCode() throws Exception {
        String author = as("wes", "live-author");
        String yaml = """
                board: bad
                notifications: { email: a@b.c }
                pages:
                  - id: p
                    tiles:
                      - { report: live-trading-desk, visual: nope, at: { x: 0, y: 0, w: 4, h: 3 } }
                      - { report: no-such-report, visual: mtm, at: { x: 4, y: 0, w: 4, h: 3 } }
                      - { report: live-trading-desk, visual: mtm, at: { x: 0, y: 3, w: 13, h: 3 } }
                      - { report: live-trading-desk, visual: mtm, at: { x: 0, y: 6, w: 4, h: 3 }, alert: { warn: { op: lt, value: 0 } } }
                """;
        JsonNode r = ok(call("POST", "/api/v1/bi/boards/validate", author, json.writeValueAsString(Map.of("yaml", yaml))));
        assertThat(r.path("valid").asBoolean()).isFalse();
        String problems = r.path("problems").toString();
        assertThat(problems).contains("DRS-9052").contains("no visual 'nope'").contains("no report 'no-such-report'").contains("x + w is at most 12").contains("DRS-9053");
        assertThat(call("POST", "/api/v1/bi/boards/validate", rita(), json.writeValueAsString(Map.of("yaml", yaml))).status()).as("validating is an author's tool").isEqualTo(403);
    }

    @Test @Order(8)
    void aPerspectiveVisualOverTwoTablesAndAnUnknownAggregateAreRefused() throws Exception {
        String author = as("wes", "live-author");
        // the 2.1 copy keeps its Perspective pivot (config/bi has a matrix since 2.3.0, the browser computing nothing)
        String yaml = Files.readString(Path.of("src/test/resources/bi/samples-2.1/reports/live-trading-desk.report.yaml")).replace("aggregates: { trade.MTM: sum, trade.NOTIONAL: sum }",
                "aggregates: { trade.MTM: total }");
        JsonNode r = ok(call("POST", "/api/v1/bi/reports/validate", author, json.writeValueAsString(Map.of("yaml", yaml))));
        assertThat(r.path("valid").asBoolean()).isFalse();
        assertThat(r.path("problems").toString()).contains("DRS-9032").contains("is not an aggregate");
    }
}
