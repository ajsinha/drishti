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
        assertThat(r.body()).contains("MiB").doesNotContain(" MB");
    }

    @Test
    void aTextBaseRevSaysItMustBeAWholeNumber() throws Exception {
        String auth = "Bearer " + tokens.mint("bea", List.of("author"), 300);
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://localhost:" + port + "/api/v1/builder/designs";
        HttpResponse<String> made = http.send(HttpRequest.newBuilder(URI.create(base)).header("Authorization", auth).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"rev\",\"kind\":\"rev-thing\"}")).build(), HttpResponse.BodyHandlers.ofString());
        String id = made.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/" + id + "/ops")).header("Authorization", auth)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"baseRev\":\"x\",\"ops\":[]}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("must be a whole number");
    }
}
