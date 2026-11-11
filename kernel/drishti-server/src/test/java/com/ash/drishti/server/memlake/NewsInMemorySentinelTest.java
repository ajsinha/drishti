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
package com.ash.drishti.server.memlake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.memlake.MemoryLake;
import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * News first, and the proof that it never touches a disk (HOW_IT_FITS.md sections D13 and 15, 3d): the news-and-markets
 * pack's four kinds are held in {@code mem://news}, filled by replenish jobs from a vendor feed whose every headline, snippet, source and
 * company name is a unique marker. The pages, the search, the raw documents, a download (the one allowed way out) are exercised through
 * the API; a scheduled report and an Ask are refused in words with the contract. Then the run's data folder, the process's temporary
 * folder and everything logged are searched for the markers: none may be found.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=news-and-markets",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.news-store.enabled=false",
        "drishti.sources.connectors.news-mem.plugin=delta",
        "drishti.sources.connectors.news-mem.kinds[0]=news", "drishti.sources.connectors.news-mem.kinds[1]=quote",
        "drishti.sources.connectors.news-mem.kinds[2]=news-topic", "drishti.sources.connectors.news-mem.kinds[3]=listed-company",
        "drishti.sources.connectors.news-mem.settings.root=mem://news",
        "drishti.sources.routes.news=news-mem", "drishti.sources.routes.quote=news-mem",
        "drishti.sources.routes.news-topic=news-mem", "drishti.sources.routes.listed-company=news-mem",
        "drishti.lake.memory.stores.news.max-mb=32", "drishti.lake.memory.stores.news.chunk-kb=256",
        "drishti.lake.memory.stores.news.contract=NEWS-TEST-1",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
@ExtendWith(OutputCaptureExtension.class)
class NewsInMemorySentinelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** Every value the feed sends carries this, so a marker found anywhere on a disk proves a leak. */
    static final String MARK = "SENTINEL" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(java.util.Locale.ROOT);
    private static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
    private static final HttpServer FEED = feed();

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired MemoryLake lake;
    @Autowired com.ash.drishti.engine.source.SourceRegistry registry;

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry r) {
        // the whole list in one property source (Spring binds a list from the one source that names it)
        String base = "http://127.0.0.1:" + FEED.getAddress().getPort() + "/";
        String[][] tables = {{"news", "NEWS_ID"}, {"quote", "QUOTE_ID"}, {"news-topic", "TOPIC_ID"}, {"listed-company", "COMPANY_ID"}};
        for (int i = 0; i < tables.length; i++) {
            String t = "drishti.lake.memory.tables[" + i + "].";
            String name = tables[i][0];
            String key = tables[i][1];
            r.add(t + "name", () -> name);
            r.add(t + "store", () -> "news");
            r.add(t + "key[0]", () -> key);
            r.add(t + "business-date", () -> "2026-10-02");
            r.add(t + "source.url", () -> base + name);
            int order = i + 1;
            r.add(t + "warm-up", () -> order);
            r.add(t + "pin", () -> name.equals("news"));
        }
    }

    @AfterAll
    static void stop() {
        FEED.stop(0);
    }

    private String admin() {
        return "Bearer " + tokens.mint("sentinel-admin", List.of("admin"), 600);
    }

    // ------------------------------------------------------------------------------------------------------------ the feed

    private static HttpServer feed() {
        try {
            Map<String, byte[]> bodies = Map.of(
                    "news", marked(gz(REPO.resolve("data/testdata/news-and-markets/news/news.2026-10-18.jsonl.gz")), "news"),
                    "quote", marked(gz(REPO.resolve("data/testdata/news-and-markets/quote/quote.2026-10-18.jsonl.gz")), "quote"),
                    "news-topic", marked(Files.readAllBytes(REPO.resolve("config/packs/economy/news-and-markets/samples/news-topic.jsonl")), "news-topic"),
                    "listed-company", marked(Files.readAllBytes(REPO.resolve("config/packs/economy/news-and-markets/samples/listed-company.jsonl")), "listed-company"));
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            bodies.forEach((name, body) -> s.createContext("/" + name, ex -> {
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            }));
            s.start();
            return s;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static byte[] gz(Path p) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(p))) {
            return in.readAllBytes();
        }
    }

    /** The documents with every human-readable value replaced by a marker (ids kept: ids may be logged, values never). */
    private static byte[] marked(byte[] jsonl, String kind) throws IOException {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (String line : new String(jsonl, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            ObjectNode d = (ObjectNode) JSON.readTree(line);
            n++;
            switch (kind) {
                case "news" -> {
                    d.put("ENTITY", MARK + "-ENTITY-" + n);
                    for (JsonNode item : d.path("ITEMS")) {
                        ((ObjectNode) item).put("TITLE", MARK + "-TITLE-" + n).put("SOURCE", MARK + "-SOURCE").put("SNIPPET", MARK + "-SNIPPET");
                    }
                }
                case "quote" -> d.put("NAME", MARK + "-QUOTE-" + n);
                default -> d.put("NAME", MARK + "-NAME-" + n);
            }
            b.append(d).append('\n');
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------------------------ the scan

    /** Files under {@code root} holding the marker (raw, or inside a gzip). */
    static List<String> found(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            return out;
        }
        byte[] mark = MARK.getBytes(StandardCharsets.UTF_8);
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                byte[] b;
                try {
                    b = Files.readAllBytes(p);
                } catch (IOException e) {
                    continue;                                    // removed while walking (a cache swept, a temporary file gone)
                }
                if (contains(b, mark) || (b.length > 2 && (b[0] & 0xff) == 0x1f && (b[1] & 0xff) == 0x8b && contains(gunzip(b), mark))) {
                    out.add(p.toString());
                }
            }
        }
        return out;
    }

    private static byte[] gunzip(byte[] b) {
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(b))) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            in.transferTo(o);
            return o.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    static boolean contains(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private void awaitWarm() throws InterruptedException {
        long until = System.currentTimeMillis() + 60_000;
        while (!lake.warm() && System.currentTimeMillis() < until) {
            Thread.sleep(100);
        }
        assertThat(lake.warm()).as("the warm-up finished").isTrue();
    }

    @Test
    void theScanFindsAPlantedMarker(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("plain.txt"), "x " + MARK + " y");
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(Files.newOutputStream(dir.resolve("rows.jsonl.gz")))) {
            gz.write(("{\"T\":\"" + MARK + "\"}").getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(dir.resolve("clean.txt"), "nothing here");
        assertThat(found(dir)).hasSize(2);
    }

    @Test
    void newsHeldInMemoryIsServedAndNeverWrittenToADisk(CapturedOutput output) throws Exception {
        awaitWarm();
        // the four tables are ready in memory
        mvc.perform(get("/api/v1/admin/lakes/memory").header("Authorization", admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.tables[0].name").value("news")).andExpect(jsonPath("$.tables[0].state").value("ready"))
                .andExpect(jsonPath("$.tables[1].state").value("ready")).andExpect(jsonPath("$.tables[2].state").value("ready"))
                .andExpect(jsonPath("$.tables[3].state").value("ready")).andExpect(jsonPath("$.stores[0].contract").value("NEWS-TEST-1"));
        mvc.perform(get("/api/v1/admin/lakes/memory/jobs").header("Authorization", admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.runs[0].outcome").value("ok"));

        // pages, raw documents and searches read it: the markers come back to the user
        assertThat(registry.connector("news-mem").orElseThrow().memoryOnly()).isTrue();
        MvcResult page = mvc.perform(get("/api/v1/views/news/NEWS-Alphabet").header("Authorization", admin())).andExpect(status().isOk()).andReturn();
        assertThat(page.getResponse().getContentAsString()).contains(MARK);
        for (String ref : List.of("quote/QUOTE-AAPL", "listed-company/CO-AAPL", "news-topic/TOPIC-FED")) {
            String body = mvc.perform(get("/api/v1/views/" + ref).header("Authorization", admin())).andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString();
            assertThat(body).as(ref).contains(MARK);
        }
        String search = mvc.perform(get("/api/v1/search").param("q", "CO Apple").header("Authorization", admin())).andReturn().getResponse()
                .getContentAsString();
        assertThat(search).isNotBlank();

        // a download is the one way out: streamed, allowed, audited without values
        MvcResult dl = mvc.perform(get("/api/v1/data/listed-company/rows").header("Authorization", admin())).andReturn();
        assertThat(dl.getResponse().getStatus()).as(dl.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(new String(gunzip(dl.getResponse().getContentAsByteArray()), StandardCharsets.UTF_8)).contains(MARK);

        // a scheduled report would deliver rows: refused, in words, with the contract
        mvc.perform(put("/api/v1/me/reports/sentinel").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"NEWS where COUNT > 1\",\"schedule\":\"business-days 18:30\",\"deliver\":\"folder\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-5219"));
        String refusal = mvc.perform(put("/api/v1/me/reports/sentinel").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"NEWS where COUNT > 1\",\"schedule\":\"business-days 18:30\"}")).andReturn().getResponse().getContentAsString();
        assertThat(refusal).contains("held in memory only").contains("NEWS-TEST-1");

        // a refill runs again from the feed (the store refills after a restart or an eviction the same way)
        mvc.perform(post("/api/v1/admin/lakes/memory/refill/news").header("Authorization", admin())).andExpect(status().isOk());
        long until = System.currentTimeMillis() + 30_000;
        while (lake.jobs("news", 100).size() < 2 && System.currentTimeMillis() < until) {
            Thread.sleep(100);
        }
        assertThat(lake.jobs("news", 100).get(0).get("outcome")).isEqualTo("ok");

        // nothing of it on a disk: the run's folder (data, caches, indexes, stores, audit, BI, reports), the process's temporary folder, the logs
        List<String> leaks = new ArrayList<>();
        leaks.addAll(found(Path.of(System.getProperty("drishti.test.dir", "target/test-run"))));
        leaks.addAll(found(com.ash.drishti.common.TempFiles.root()));
        assertThat(leaks).as("files holding a marker").isEmpty();
        assertThat(output.getAll()).as("the logs").doesNotContain(MARK);
        assertThat(com.ash.drishti.memlake.store.MemoryStores.store("news").pool().inUseBytes()).isGreaterThan(0);
    }
}
