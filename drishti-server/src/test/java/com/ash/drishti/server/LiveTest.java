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

import com.ash.drishti.engine.live.Frame;
import com.ash.drishti.engine.live.Patch;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** A real server on a random port with a fast-ticking demo source; the SSE stream is read over HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "drishti.rachana.hot-reload=false",
        "drishti.sources.plugins.demo.settings.tick-ms=40", "drishti.live.frame=20ms"})
class LiveTest {

    @LocalServerPort int port;

    private List<String[]> read(String path, int frames) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<java.io.InputStream> r = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(r.statusCode()).isEqualTo(200);
        List<String[]> events = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
            String event = null;
            String line;
            long deadline = System.currentTimeMillis() + 10_000;
            while ((line = in.readLine()) != null && System.currentTimeMillis() < deadline) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:") && event != null) {
                    events.add(new String[] {event, line.substring(5)});
                    if (events.stream().filter(e -> e[0].equals("frame")).count() >= frames) {
                        break;
                    }
                }
            }
        }
        return events;
    }

    @Test
    void theIrsViewTicksOverServerSentEvents() throws Exception {
        List<String[]> events = read("/api/v1/views/trade/IRS-48213/stream", 3);
        assertThat(events.get(0)[0]).isEqualTo("view");
        assertThat(events.get(0)[1]).contains("\"mnemonic\":\"TRD\"");
        List<String> frames = events.stream().filter(e -> e[0].equals("frame")).map(e -> e[1]).toList();
        assertThat(frames).hasSizeGreaterThanOrEqualTo(3);
        assertThat(frames.get(0)).contains("\"op\":\"strip\"").contains("\"path\":\"$.mtm\"").contains("\"p99Ms\"");
        assertThat(String.join("", frames)).contains("\"id\":\"curve\"");
    }

    @Test
    void liveHealthReportsLatency() throws Exception {
        read("/api/v1/views/trade/CFT-77120/stream", 2);
        String body = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/health/live")).build(),
                HttpResponse.BodyHandlers.ofString()).body();
        assertThat(body).contains("\"p99Ms\"").contains("\"frames\"");
    }

    @Test
    void mailboxMergesNewestPerTarget() {
        var a = new Frame(1, 10, List.of(Patch.class.cast(stripPatch(5, "a")), stripPatch(1, "x")), 1, 1);
        var b = new Frame(2, 11, List.of(stripPatch(5, "b")), 2, 2);
        Frame m = com.ash.drishti.server.api.MailboxAccess.merge(a, b);
        assertThat(m.seq()).isEqualTo(2);
        assertThat(m.patches()).hasSize(2);
        assertThat(m.patches().get(1).cell().text()).isEqualTo("b");
    }

    @Test
    void aMergedDeleteAndRestoreKeepTheirOrderSoTheLastOneWins() {
        var deleted = new Patch("deleted", null, null, null, null, java.time.Instant.parse("2026-10-01T09:30:00Z"));
        var restored = new Patch("restored", null, null, null, null);
        Frame gone = com.ash.drishti.server.api.MailboxAccess.merge(new Frame(1, 10, List.of(stripPatch(1, "x")), 1, 1),
                new Frame(2, 11, List.of(deleted), 1, 1));
        assertThat(gone.patches()).extracting(Patch::op).containsExactly("strip", "deleted");
        Frame back = com.ash.drishti.server.api.MailboxAccess.merge(gone, new Frame(3, 12, List.of(restored, stripPatch(1, "y")), 1, 1));
        assertThat(back.patches()).extracting(Patch::op).containsExactly("deleted", "restored", "strip");
        Frame goneAgain = com.ash.drishti.server.api.MailboxAccess.merge(back, new Frame(4, 13, List.of(deleted), 1, 1));
        assertThat(goneAgain.patches()).extracting(Patch::op).containsExactly("restored", "strip", "deleted");
    }

    static Patch stripPatch(int i, String text) {
        return new Patch("strip", i, com.ash.drishti.engine.view.ViewModel.Cell.of(null, text), null, null);
    }
}
