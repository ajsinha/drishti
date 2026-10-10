package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** A live stream opened with control values keeps them: every rebuilt frame is computed with {@code ctl.<id>} as the stream was opened. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.tick-ms=40", "drishti.live.frame=20ms"})
class ControlsLiveTest {

    static Path dir;

    @DynamicPropertySource
    static void sutras(DynamicPropertyRegistry r) throws Exception {
        dir = Files.createTempDirectory(Path.of("target"), "ctl-sutras");
        Files.writeString(dir.resolve("ctl-live.v1.sutra.yaml"), """
                rachana: 1
                sutra: ctl-live
                version: 1
                match: { kind: trade, priority: 1000 }
                title: { id: $.tradeId }
                strip:
                  - { label: Tick, bind: "fmt($.mtm, 'signed0') + ' / ' + ctl.q" }
                controls:
                  - { id: q, kind: search, label: Find }
                panels:
                  - { id: built, kind: provenance, title: How this view was built }
                """);
        r.add("drishti.rachana.dirs[0]", () -> dir.toString());
    }

    @LocalServerPort int port;

    @Test
    void everyLiveFrameIsBuiltWithTheControlValuesTheStreamWasOpenedWith() throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<java.io.InputStream> r = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/views/trade/IRS-48213/stream?ctl.q=abc")).header("Accept", "text/event-stream").build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(r.statusCode()).isEqualTo(200);
        List<String> frames = new ArrayList<>();
        String view = null;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
            String event = null;
            String line;
            long deadline = System.currentTimeMillis() + 15_000;
            while ((line = in.readLine()) != null && System.currentTimeMillis() < deadline && frames.size() < 3) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:") && "view".equals(event)) {
                    view = line.substring(5);
                } else if (line.startsWith("data:") && "frame".equals(event)) {
                    frames.add(line.substring(5));
                }
            }
        }
        assertThat(view).contains("\"controls\":[").contains("\"id\":\"q\"").contains("\"value\":\"abc\"");
        assertThat(frames).hasSizeGreaterThanOrEqualTo(3);
        frames.forEach(f -> assertThat(f).contains("\"op\":\"strip\"").contains(" / abc"));
    }
}
