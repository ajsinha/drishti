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
package com.ash.drishti.server.net;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Phase N6 on two real servers in one JVM: A holds the ticking trades and the lake a BI report reads; B has the same packs and BI
 * folder but no data. A ticking entity held on A streams to a viewer on B (the live relay), and the report asked of B answers what A
 * answers (routed to the node that holds the model's data).
 */
class NetLiveBiTest {

    static NetNodes cluster;
    static final ObjectMapper JSON = new ObjectMapper();
    static final String REPORT = "file:report:lake-report";

    static void bi(Path folder, Path lake) throws Exception {
        Files.createDirectories(folder.resolve("datasets"));
        Files.createDirectories(folder.resolve("reports"));
        Files.writeString(folder.resolve("datasets/lake-report.dataset.yaml"), """
                rachana: 1
                dataset: lake-report
                title: Lake for reports
                tables:
                  trades:
                    source: { connector: rep-lake, kind: trade }
                    columns:
                      TRADE_ID: { type: string, key: true }
                      DESK: string
                      NOTIONAL: decimal
                measures:
                  Notional: { expr: "sum(trades.NOTIONAL)" }
                  Trades: { expr: "count(trades)" }
                """);
        Files.writeString(folder.resolve("reports/lake-report.report.yaml"), """
                report: lake-report
                title: Lake report
                dataset: lake-report
                pages:
                  - id: main
                    title: Main
                    visuals:
                      - { id: total, type: card, title: Notional, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Notional] } }
                      - { id: by-desk, type: bar, title: Notional by desk, at: { x: 0, y: 2, w: 6, h: 4 }, wells: { axis: [trades.DESK], values: [Notional] } }
                """);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            lines.add("{\"TRADE_ID\":\"LK-" + i + "\",\"DESK\":\"DESK-" + (i % 3) + "\",\"NOTIONAL\":" + 1000.0 * (i + 1) + ",\"LEGAL_ENTITY\":\"LE-NORTH\"}");
        }
        Path day = lake.resolve("2026-10-08").resolve("trade.jsonl");
        Files.createDirectories(day.getParent());
        Files.write(day, lines, StandardCharsets.UTF_8);
    }

    @BeforeAll
    static void start() throws Exception {
        Path root = Path.of("target", "net-it", "livebi-" + System.nanoTime()).toAbsolutePath();
        int[] p = NetNodes.PORTS;
        bi(root.resolve("bi"), root.resolve("lake"));
        NetNodes.folder(root.resolve("a/net"), p[0], List.of(), List.of(), List.of());
        NetNodes.folder(root.resolve("b/net"), p[1], List.of(), List.of(), List.of(p[0]));
        cluster = new NetNodes(root);
        List<String> common = List.of("--drishti.bi.dir=" + root.resolve("bi"), "--drishti.sources.plugins.file.enabled=false",
                "--drishti.sources.connectors.finance-lake.enabled=false", "--drishti.sources.connectors.rep-lake.plugin=file",
                "--drishti.sources.connectors.rep-lake.kinds[0]=trade", "--drishti.sources.connectors.rep-lake.settings.root=" + root.resolve("lake"),
                "--drishti.sources.connectors.rep-lake.settings.id-field=TRADE_ID", "--drishti.sources.connectors.rep-lake.settings.rescan-seconds=3600");
        List<String> a = new ArrayList<>(common);
        a.addAll(List.of("--drishti.sources.plugins.demo.settings.ticking=true", "--drishti.bi.query.store-dir=" + root.resolve("a/bi-store")));
        List<String> b = new ArrayList<>(common);
        b.addAll(List.of("--drishti.sources.plugins.demo.enabled=false", "--drishti.sources.connectors.rep-lake.enabled=false",
                "--drishti.bi.query.store-dir=" + root.resolve("b/bi-store")));
        cluster.start("a", p[0], a.toArray(String[]::new));
        cluster.start("b", p[1], b.toArray(String[]::new));
        assertThat(NetNodes.await(15_000, () -> List.of("a", "b").stream().allMatch(n -> cluster.node(n).bean(DigestService.class).table().nodes().size() == 2)))
                .isTrue();
    }

    @AfterAll
    static void stop() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void aTickingEntityHeldOnAStreamsToAViewerOnB() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(cluster.node("b").url() + "/api/v1/views/trade/IRS-48213/stream"))
                .header("Accept", "text/event-stream").build();
        HttpResponse<java.io.InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(r.statusCode()).isEqualTo(200);
        List<String> events = new ArrayList<>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var lines = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        synchronized (events) {           // no I/O: the test's own list
                            events.add(line.substring(6).trim());
                        }
                    }
                }
            } catch (java.io.IOException e) {
                // closed by the test
            }
        });
        boolean framed = NetNodes.await(20_000, () -> {
            synchronized (events) {                       // no I/O: a look at the list
                return events.contains("view") && events.contains("frame");
            }
        });
        r.body().close();
        reader.interrupt();
        assertThat(framed).as("events seen: %s; B sent: %s", events, cluster.node("b").bean(ForwardLog.class).recent()).isTrue();
        long relays = cluster.node("b").bean(ForwardLog.class).recent().stream().filter(c -> c.what().equals("stream") && c.outcome().equals("ok")).count();
        assertThat(relays).isPositive();
    }

    @Test
    void aBiReportOnBOverAsModelAnswers() throws Exception {
        String body = "{\"page\":\"main\",\"visual\":\"total\",\"state\":{\"dates\":{\"from\":\"2026-10-08\",\"to\":\"2026-10-08\"}}}";
        var onA = cluster.call("POST", cluster.node("a").url() + "/api/v1/bi/reports/" + REPORT + "/query", body);
        assertThat(onA.statusCode()).as(onA.body()).isEqualTo(200);
        var onB = cluster.call("POST", cluster.node("b").url() + "/api/v1/bi/reports/" + REPORT + "/query", body);
        assertThat(onB.statusCode()).as(onB.body()).isEqualTo(200);
        JsonNode a = JSON.readTree(onA.body());
        JsonNode b = JSON.readTree(onB.body());
        assertThat(b.path("rows")).isEqualTo(a.path("rows"));
        assertThat(a.path("rows").toString()).contains("465000");          // 1000 + 2000 + ... + 30000
        var route = cluster.node("b").bean(NetRouting.class).report(REPORT, java.util.Set.of("trade"), com.ash.drishti.server.security.Principal.anonymous("x"));
        assertThat(route.route()).isEqualTo(NetRouting.Route.VIEW);
    }
}
