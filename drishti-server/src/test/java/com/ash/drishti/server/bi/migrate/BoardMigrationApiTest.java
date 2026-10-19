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
package com.ash.drishti.server.bi.migrate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.board.BoardParser;
import com.ash.drishti.server.bi.board.ReportBoards;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Boards become a report layout (R7, 2.4.0): a store holding 2.3 boards (a published one, a draft ahead of its published version, one that does not parse).
 * A dry run lists them and changes nothing; the run (what the start does after the expression and model steps) turns each into a report with board pages,
 * keeping its id, its history and its published version line, audits it, leaves the broken one as it was and lists it, and a second run changes nothing.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/board-migrate-${random.uuid}/identity.db", "drishti.packs.overlay=target/board-migrate-overlay/added.yaml",
        "drishti.bi.dir=target/board-migrate-folder", "drishti.bi.query.store-dir=target/board-migrate-store/${random.uuid}",
        "drishti.sources.connectors-dir=target/test-data/board-migrate-connectors", "drishti.sources.plugins.file.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BoardMigrationApiTest {

    static final String WALL = """
            # the desk's wall (a comment the migration keeps)
            rachana: 1
            board: desk
            title: Desk
            rotate: 30s
            notify: { level: warn, channels: [inbox] }
            pages:
              - id: p1
                title: Numbers
                tiles:
                  - { id: mtm, report: desk-report, visual: mtm, at: { x: 0, y: 0, w: 3, h: 2 }, alert: { on: MTM, warn: { op: lt, value: 0 }, notify: true } }
                  - { id: by, report: desk-report, page: main, visual: by-desk, title: By desk, at: { x: 3, y: 0, w: 9, h: 5 } }
            """;
    static final String WALL_DRAFT = WALL.replace("title: Desk\n", "title: Desk (next)\n");
    static final String BROKEN = "board: broken\npages:\n  - id: p\n    tiles:\n      - { report: r, visual: v, at: { x: 10, y: 0, w: 5, h: 2 } }\n";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired BiStore store;
    @Autowired BiMigration migration;
    final ObjectMapper json = new ObjectMapper();

    JsonNode call(String method, String path, Object body) throws Exception {
        var req = "POST".equals(method) ? post(java.net.URI.create(path)) : get(java.net.URI.create(path));
        req.header("Authorization", "Bearer " + tokens.mint("drishti-dev-admin", List.of("admin"), 300));
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        var res = mvc.perform(req).andReturn().getResponse();
        assertThat(res.getStatus()).as(path + " " + res.getContentAsString()).isBetween(200, 299);
        return json.readTree(res.getContentAsString());
    }

    BiStore.Item board(String id, String body, int published) {
        Instant t = Instant.parse("2026-10-01T09:00:00Z");
        return new BiStore.Item(id, "ws-boards", "board", id, "", "olga", List.of(), body, published > 0 && body.equals(WALL) ? "published" : "draft", published, "none", "",
                List.of(), List.of(), List.of(), "", null, t, t, "olga", published > 0 ? t : null, published > 0 ? "olga" : "");
    }

    void seed() {
        Instant t = Instant.parse("2026-10-01T09:00:00Z");
        store.saveWorkspace(new BiStore.Workspace("ws-boards", "Boards", "", false, "olga", false, t, "olga"));
        store.saveItem(board("bd-published", WALL, 1));
        store.addVersion(new BiStore.Version("bd-published", 1, WALL, "first", "olga", t), 50);
        store.saveItem(board("bd-ahead", WALL_DRAFT, 1));
        store.addVersion(new BiStore.Version("bd-ahead", 1, WALL, "first", "olga", t), 50);
        store.saveItem(board("bd-broken", BROKEN, 0));
    }

    @Test @Order(1)
    void aDryRunListsTheBoardsAndChangesNothing() throws Exception {
        seed();
        JsonNode r = call("POST", "/api/v1/bi/migrate/boards", Map.of("dryRun", true));
        assertThat(r.path("wouldConvert").asInt()).isEqualTo(2);
        assertThat(r.path("problems").asInt()).isEqualTo(1);
        assertThat(store.item("bd-published").orElseThrow().kind()).isEqualTo("board");
    }

    @Test @Order(2)
    void theRunMakesEachBoardAReportWithBoardPagesKeepingItsIdHistoryAndVersions() throws Exception {
        migration.boards("system", false);
        BiStore.Item pub = store.item("bd-published").orElseThrow();
        assertThat(pub.kind()).isEqualTo("report");
        assertThat(pub.body()).contains("# the desk's wall (a comment the migration keeps)").contains("layout: board").contains("type: tile").contains("from: { report: desk-report");
        assertThat(pub.publishedVersion()).isEqualTo(2);
        assertThat(pub.status()).isEqualTo("published");
        assertThat(ReportBoards.boardOf("report", pub.body())).as("the same board").isEqualTo(BoardParser.parse(WALL).board());
        assertThat(store.version("bd-published", 1).orElseThrow().body()).as("old versions are history").isEqualTo(WALL);
        assertThat(store.revisions("bd-published")).anySatisfy(rv -> assertThat(rv.body()).isEqualTo(WALL));
        BiStore.Item ahead = store.item("bd-ahead").orElseThrow();
        assertThat(ahead.kind()).isEqualTo("report");
        assertThat(ahead.status()).isEqualTo("draft");
        assertThat(ahead.body()).contains("title: Desk (next)");
        assertThat(store.item("bd-broken").orElseThrow().kind()).as("never silently changed").isEqualTo("board");
        JsonNode problems = call("GET", "/api/v1/bi/migrate/problems", null);
        assertThat(problems.toString()).contains("bd-broken").contains("\"step\":\"boards\"");
        JsonNode audit = call("GET", "/api/v1/admin/audit?limit=200", null);
        assertThat(audit.findValuesAsText("action")).contains("bi-boards-migrated", "bi-migration-run");
    }

    @Test @Order(3)
    void theConvertedBoardIsServedAsAReportAndAsABoard() throws Exception {
        JsonNode report = call("GET", "/api/v1/bi/reports/bd-published", null);
        assertThat(report.path("report").path("pages").get(0).path("layout").asText()).isEqualTo("board");
        assertThat(report.path("report").path("board").path("rotate").asText()).isEqualTo("30s");
        JsonNode board = call("GET", "/api/v1/bi/boards/bd-published", null);
        assertThat(board.path("board").path("pages").get(0).path("tiles")).hasSize(2);
        assertThat(board.path("tiles").has("p1/mtm")).isTrue();
    }

    @Test @Order(4)
    void aSecondRunChangesNothing() throws Exception {
        JsonNode again = call("POST", "/api/v1/bi/migrate/boards", Map.of());
        assertThat(again.path("converted").asInt()).isZero();
        assertThat(again.path("problems").asInt()).isEqualTo(1);
    }
}
