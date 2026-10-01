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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Scheduled reports: saved, checked, run by hand and by the scheduler, delivered to a folder and to a webhook. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=finance", "drishti.sources.connectors.finance-lake.enabled=false", "drishti.reports.tick=500ms"})
@AutoConfigureMockMvc
class ReportApiTest {

    static HttpServer hook;
    static final List<String> received = new CopyOnWriteArrayList<>();
    static Path folder;

    @BeforeAll
    static void webhook() throws Exception {
        hook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hook.createContext("/hook", ex -> {
            received.add(ex.getRequestHeaders().getFirst("X-Drishti-Report") + "\n" + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        hook.start();
    }

    @AfterAll
    static void stop() {
        hook.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        folder = Files.createTempDirectory("drishti-reports");
        r.add("drishti.reports.folder", folder::toString);
        r.add("drishti.reports.webhooks[0]", () -> "http://127.0.0.1:" + hook.getAddress().getPort() + "/hook");
    }

    @Autowired MockMvc mvc;

    private static String body(String query, String schedule, String deliver, String webhook) {
        return "{\"query\": \"" + query + "\", \"schedule\": \"" + schedule + "\", \"deliver\": \"" + deliver + "\", \"webhook\": \"" + webhook + "\"}";
    }

    @Test
    void aReportIsCheckedWhenSavedAndDeliveredWhenRun() throws Exception {
        mvc.perform(put("/api/v1/me/reports/bad").contentType("application/json").content(body("TRD where mtm < 0", "every tuesday", "folder", "")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("daily HH:MM")));
        mvc.perform(put("/api/v1/me/reports/bad").contentType("application/json")
                .content(body("TRD where mtm < 0", "daily 07:00", "webhook", "http://169.254.169.254/latest")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not allowed")));
        mvc.perform(put("/api/v1/me/reports/Losers").contentType("application/json").content(body("TRD where mtm < 0", "business-days 18:30", "folder", "")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextRun").exists());
        mvc.perform(post("/api/v1/me/reports/Losers/run")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok")).andExpect(jsonPath("$.rows").value(org.hamcrest.Matchers.greaterThan(0)));
        try (var files = Files.walk(folder)) {
            Path csv = files.filter(f -> f.toString().endsWith(".csv")).findFirst().orElseThrow();
            assertThat(Files.readString(csv)).startsWith("kind,id,title").contains("trade,");
            assertThat(csv.getFileName().toString()).startsWith("Losers-");
        }
        mvc.perform(get("/api/v1/me/reports/Losers")).andExpect(jsonPath("$.runs[0].trigger").value("by hand"));
        String url = "http://127.0.0.1:" + hook.getAddress().getPort() + "/hook";
        mvc.perform(put("/api/v1/me/reports/To desk").contentType("application/json").content(body("TRD where mtm < 0", "daily 07:00", "webhook", url)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/me/reports/To desk/run")).andExpect(jsonPath("$.status").value("ok")).andExpect(jsonPath("$.target").value(url));
        assertThat(received).anySatisfy(r -> assertThat(r).startsWith("To desk\nkind,id,title"));
        mvc.perform(delete("/api/v1/me/reports/To desk")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me/reports/To desk")).andExpect(status().isNotFound());
    }

    @Test
    void theSchedulerRunsWhatIsDue() throws Exception {
        mvc.perform(put("/api/v1/me/reports/Every second").contentType("application/json").content(body("TRD", "cron * * * * * *", "folder", "")))
                .andExpect(status().isOk());
        for (int i = 0; i < 40; i++) {
            String r = mvc.perform(get("/api/v1/me/reports/Every second")).andReturn().getResponse().getContentAsString();
            if (r.contains("\"trigger\":\"schedule\"")) {
                assertThat(r).contains("\"status\":\"ok\"");
                mvc.perform(delete("/api/v1/me/reports/Every second"));
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("the scheduler did not run the report");
    }
}
