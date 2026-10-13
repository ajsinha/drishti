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
import com.ash.drishti.server.bi.layer.DatasetDefParser;
import com.ash.drishti.server.bi.layer.ModelDefParser;
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
 * The stored-item dataset-to-model migration (docs/architecture/BI_MODEL_SPEC.md section 8.3, step 5): a store holding a published 2.1 dataset with two tables
 * and a desk-head rule, a published report on it (named by the dataset's id), and a dataset whose draft is ahead of its published version. A dry run plans
 * and changes nothing; the run makes the dataset item the model (same id, its 2.1 text kept in its history), each table a published dataset item, rebinds
 * the report (published again), never gives the restricted role rows it did not see (CLOSED: the role is given {@code False} on the other dataset and the
 * report says how to open it), leaves the draft-ahead dataset alone and lists it; a second run changes nothing.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/model-migrate-${random.uuid}/identity.db", "drishti.packs.overlay=target/model-migrate-overlay/added.yaml",
        "drishti.bi.dir=target/model-migrate-folder", "drishti.bi.query.store-dir=target/model-migrate-store/${random.uuid}",
        "drishti.sources.connectors-dir=target/test-data/model-migrate-connectors", "drishti.sources.plugins.file.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModelMigrationApiTest {

    static final String DATASET = """
            rachana: 1
            expressions: 2
            dataset: desk-book       # the old dataset
            version: 1
            title: Desk and book
            tables:
              trades:
                source: { connector: trading-store, kind: trade }
                columns: { tradeId: { type: string, key: true }, desk: string, book: string, mtm: decimal }
              books:
                source: { connector: reference-store, kind: book }
                columns: { name: string }
            relationships:
              - { from: trades.book, to: books.id }
            measures:
              MTM: { expr: "sum(trades.mtm)" }
              Books: { expr: "count(books)" }
            security:
              rows:
                - { role: desk-head, where: "trades.desk in user.desks" }
                - { role: risk, where: "True" }
            """;
    static final String REPORT = """
            rachana: 1
            report: desk-book-report   # a comment the migration keeps
            title: Desk and book
            dataset: it-ds
            pages:
              - id: one
                title: One
                visuals:
                  - { id: mtm, type: card, title: MTM, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [MTM] } }
            """;

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired BiStore store;
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

    void published(String id, String kind, String body, String draft) {
        Instant t = Instant.parse("2026-10-01T09:00:00Z");
        store.saveItem(new BiStore.Item(id, "ws-models", kind, id, "", "olga", List.of(), draft, draft.equals(body) ? "published" : "draft", 1, "none", "",
                List.of("risk", "desk-head"), List.of(), List.of(), "", null, t, t, "olga", t, "olga"));
        store.addVersion(new BiStore.Version(id, 1, body, "first", "olga", t), 50);
        store.addRevision(id, body, "published", "olga", t, "publish", 1, 200);
    }

    @Test @Order(1)
    void aDryRunPlansAndChangesNothing() throws Exception {
        Instant t = Instant.parse("2026-10-01T09:00:00Z");
        store.saveWorkspace(new BiStore.Workspace("ws-models", "Models", "", false, "olga", false, t, "olga"));
        published("it-ds", "dataset", DATASET, DATASET);
        published("it-report", "report", REPORT, REPORT);
        published("it-ahead", "dataset", DATASET.replace("desk-book", "ahead"), DATASET.replace("desk-book", "ahead").replace("version: 1", "version: 2"));
        JsonNode r = call("POST", "/api/v1/bi/migrate/models", Map.of("dryRun", true));
        assertThat(r.path("wouldConvert").asInt()).isEqualTo(2);
        assertThat(r.path("problems").asInt()).isEqualTo(1);
        assertThat(r.path("report").asText()).contains("model desk-book@1 over [desk-book-trades, desk-book-books]");
        assertThat(store.item("it-ds").orElseThrow().kind()).isEqualTo("dataset");
        assertThat(store.items()).hasSize(3);
    }

    @Test @Order(2)
    void theRunMakesTheModelItsDatasetsAndRebindsTheReport() throws Exception {
        JsonNode r = call("POST", "/api/v1/bi/migrate/models", Map.of());
        assertThat(r.path("converted").asInt()).isEqualTo(2);
        BiStore.Item model = store.item("it-ds").orElseThrow();
        assertThat(model.kind()).isEqualTo("model");
        assertThat(model.publishedVersion()).isEqualTo(2);
        assertThat(ModelDefParser.parse(model.body()).ok()).as(model.body()).isTrue();
        assertThat(store.revisions("it-ds")).anySatisfy(rv -> assertThat(rv.body()).isEqualTo(DATASET));
        List<BiStore.Item> made = store.items().stream().filter(i -> "dataset".equals(i.kind()) && DatasetDefParser.isVersion2(i.body())).toList();
        assertThat(made).extracting(BiStore.Item::title).containsExactlyInAnyOrder("desk-book-trades", "desk-book-books");
        assertThat(made).allMatch(i -> i.isPublished() && i.audienceRoles().contains("desk-head") && "ws-models".equals(i.workspace()));
        String books = made.stream().filter(i -> i.title().equals("desk-book-books")).findFirst().orElseThrow().body();
        assertThat(books).as("CLOSED: the desk-head sees no books, as in 2.1").contains("{ role: desk-head, where: \"False\" }").contains("{ role: risk, where: \"True\" }");
        assertThat(model.body()).doesNotContain("may-see-all");
        BiStore.Item report = store.item("it-report").orElseThrow();
        assertThat(report.body()).contains("model: desk-book@1").contains("# a comment the migration keeps").contains("rachana: 2");
        assertThat(report.publishedVersion()).isEqualTo(2);
        assertThat(store.item("it-ahead").orElseThrow().kind()).as("a draft ahead is never changed").isEqualTo("dataset");
        assertThat(call("GET", "/api/v1/bi/migrate/problems", null).path("problems").findValuesAsText("item")).contains("it-ahead");
        JsonNode audit = call("GET", "/api/v1/admin/audit?limit=200", null);
        assertThat(audit.findValuesAsText("action")).contains("bi-model-migrated", "bi-item-created");
    }

    @Test @Order(3)
    void aSecondRunChangesNothing() throws Exception {
        int items = store.items().size();
        JsonNode r = call("POST", "/api/v1/bi/migrate/models", Map.of());
        assertThat(r.path("converted").asInt()).isZero();
        assertThat(store.items()).hasSize(items);
        assertThat(store.item("it-ds").orElseThrow().publishedVersion()).isEqualTo(2);
    }
}
