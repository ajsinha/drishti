package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.security.TokenVerifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** QA L-11: an upload over the limit is answered 413 on a real connection, not a broken pipe. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"drishti.rachana.hot-reload=false",
        "drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:target/upload-limit-${random.uuid}/identity.db",
        "drishti.builder.designs.dir=target/upload-limit-files-${random.uuid}"})
class DesignUploadLimitTest {

    @LocalServerPort int port;
    @Autowired TokenVerifier tokens;

    @Test
    void aTwentyEightMegabyteUploadAnswers413() throws Exception {
        String auth = "Bearer " + tokens.mint("ana", List.of("author"), 300);
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://localhost:" + port + "/api/v1/builder/designs";
        HttpResponse<String> made = http.send(HttpRequest.newBuilder(URI.create(base)).header("Authorization", auth).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"big\",\"kind\":\"big-thing\"}")).build(), HttpResponse.BodyHandlers.ofString());
        String id = made.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String blob = "y".repeat(2_000_000);
        StringBuilder body = new StringBuilder("{\"samples\":[");
        for (int i = 0; i < 14; i++) {
            body.append(i > 0 ? "," : "").append("{\"name\":\"a").append(i).append("\",\"document\":{\"blob\":\"").append(blob).append("\"}}");
        }
        body.append("]}");
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/" + id + "/samples")).header("Authorization", auth)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(413);
    }
}
