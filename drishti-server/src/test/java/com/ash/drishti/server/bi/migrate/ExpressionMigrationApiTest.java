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
 * The stored-item expression migration (docs/architecture/BI_DESIGN.md section 12.2, task 6): a store holding items in the old expression syntax (a published
 * dataset whose draft is its published text, a draft with its own changes ahead of the published version, an item the converter cannot convert, an item
 * already in DEL v2). A dry run lists and changes nothing; the run converts the draft and the published text (the published one becomes the next version),
 * keeps the text before restorable in the history, audits every change, marks the item it could not convert (Admin, BI settings and its page) without
 * touching it, and a second run (the next start) changes nothing.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/expr-migrate-${random.uuid}/identity.db", "drishti.packs.overlay=target/expr-migrate-overlay/added.yaml",
        "drishti.bi.dir=target/expr-migrate-folder", "drishti.bi.query.store-dir=target/expr-migrate-store/${random.uuid}",
        "drishti.sources.connectors-dir=target/test-data/expr-migrate-connectors", "drishti.sources.plugins.file.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExpressionMigrationApiTest {

    static final String OLD = """
            rachana: 1
            dataset: old-trades     # a comment the migration keeps
            version: 1
            tables:
              trades:
                source: { connector: trading-store, kind: trade }
                columns: { desk: string, mtm: decimal, status: string }
            measures:
              MTM:  { expr: "sum(trades.mtm)" }
              Live: { expr: "sum(trades.mtm, trades.status == 'Live')" }
              Per:  { expr: "[MTM] / [Live]" }
            security:
              rows:
                - { role: risk, where: "true" }
            """;
    static final String OLD_DRAFT = OLD.replace("Per:  { expr: \"[MTM] / [Live]\" }", "Per:  { expr: \"[MTM] / [Live]\" }\n  Neg:  { expr: \"count(trades, trades.mtm < 0)\" }");
    static final String BROKEN = OLD.replace("\"[MTM] / [Live]\"", "\"sum(trades.mtm\"");
    static final String V2 = """
            rachana: 1
            expressions: 2
            dataset: new-trades
            tables:
              trades:
                source: { connector: trading-store, kind: trade }
                columns: { mtm: decimal }
            measures:
              MTM: { expr: "sum(trades.mtm, where=trades.mtm > 0)" }
            """;

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired BiStore store;
    @Autowired BiMigration migration;
    final ObjectMapper json = new ObjectMapper();

    String admin() {
        return "Bearer " + tokens.mint("drishti-dev-admin", List.of("admin"), 300);
    }

    JsonNode call(String method, String path, Object body) throws Exception {
        var req = "POST".equals(method) ? post(java.net.URI.create(path)) : get(java.net.URI.create(path));
        req.header("Authorization", admin());
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        var res = mvc.perform(req).andReturn().getResponse();
        assertThat(res.getStatus()).as(path + " " + res.getContentAsString()).isBetween(200, 299);
        return json.readTree(res.getContentAsString());
    }

    BiStore.Item item(String id, String body, int published) {
        Instant t = Instant.parse("2026-10-01T09:00:00Z");
        return new BiStore.Item(id, "ws-migrate", "dataset", id, "", "olga", List.of(), body, published > 0 && body.equals(OLD) ? "published" : "draft", published, "none", "",
                List.of(), List.of(), List.of(), "", null, t, t, "olga", published > 0 ? t : null, published > 0 ? "olga" : "");
    }

    /** What a 2.1 store holds: saved before the old syntax was refused. */
    void seed() {
        Instant t = Instant.parse("2026-10-01T09:00:00Z");
        store.saveWorkspace(new BiStore.Workspace("ws-migrate", "Migration", "", false, "olga", false, t, "olga"));
        store.saveItem(item("it-published", OLD, 1));
        store.addVersion(new BiStore.Version("it-published", 1, OLD, "first", "olga", t), 50);
        store.addRevision("it-published", OLD, "published", "olga", t, "publish", 1, 200);
        store.saveItem(item("it-ahead", OLD_DRAFT, 1));
        store.addVersion(new BiStore.Version("it-ahead", 1, OLD, "first", "olga", t), 50);
        store.addRevision("it-ahead", OLD, "published", "olga", t, "publish", 1, 200);
        store.addRevision("it-ahead", OLD_DRAFT, "a draft change", "olga", t, "save", 0, 200);
        store.saveItem(item("it-broken", BROKEN, 0));
        store.saveItem(item("it-v2", V2, 0));
    }

    @Test @Order(1)
    void aDryRunListsWhatWouldChangeAndChangesNothing() throws Exception {
        seed();
        JsonNode r = call("POST", "/api/v1/bi/migrate/expressions", Map.of("dryRun", true));
        assertThat(r.path("dryRun").asBoolean()).isTrue();
        assertThat(r.path("wouldConvert").asInt()).isEqualTo(2);
        assertThat(r.path("problems").asInt()).isEqualTo(1);
        JsonNode ahead = find(r, "it-ahead");
        assertThat(ahead.path("state").asText()).isEqualTo("would convert");
        assertThat(ahead.path("draft").size()).isEqualTo(4);                     // Live, Per, Neg, the rule
        assertThat(ahead.path("published").size()).isEqualTo(3);
        assertThat(find(r, "it-broken").path("problems").get(0).path("path").asText()).isEqualTo("measures.Per.expr");
        assertThat(find(r, "it-v2")).isNull();
        assertThat(store.item("it-published").orElseThrow().body()).isEqualTo(OLD);
        assertThat(store.versions("it-published")).hasSize(1);
    }

    @Test @Order(2)
    void theStartUpRunConvertsDraftsAndPublishedTextsKeepingHistoryAndAuditing() throws Exception {
        migration.start();                                                       // what the next start of the server does
        BiStore.Item pub = store.item("it-published").orElseThrow();
        assertThat(pub.body()).contains("expressions: 2").contains("# a comment the migration keeps")
                .contains("sum(trades.mtm, where=trades.status == 'Live')").contains("measure('MTM') / measure('Live')").contains("where: \"True\"");
        assertThat(pub.publishedVersion()).isEqualTo(2);
        assertThat(pub.status()).isEqualTo("published");
        assertThat(store.version("it-published", 2).orElseThrow().body()).isEqualTo(pub.body());
        assertThat(store.version("it-published", 1).orElseThrow().body()).as("old versions are history").isEqualTo(OLD);
        assertThat(store.revisions("it-published")).anySatisfy(rv -> assertThat(rv.body()).isEqualTo(OLD));

        BiStore.Item ahead = store.item("it-ahead").orElseThrow();
        assertThat(ahead.status()).as("a draft ahead of the published version stays a draft").isEqualTo("draft");
        assertThat(ahead.body()).contains("count(trades, where=trades.mtm < 0)");
        assertThat(ahead.publishedVersion()).isEqualTo(2);
        assertThat(store.version("it-ahead", 2).orElseThrow().body()).doesNotContain("Neg").contains("measure('MTM')");
        assertThat(store.revisions("it-ahead")).anySatisfy(rv -> assertThat(rv.body()).isEqualTo(OLD_DRAFT));
        assertThat(store.events("it-ahead").get(0).action()).isEqualTo("migrate");
        JsonNode history = call("GET", "/api/v1/bi/items/it-ahead/history", null);
        assertThat(history.path("revisions").get(0).path("draft").asBoolean()).as("the newest saved state is the draft").isTrue();
        assertThat(history.path("revisions").get(0).path("origin").asText()).isEqualTo("migrate");

        assertThat(store.item("it-broken").orElseThrow().body()).as("never silently changed").isEqualTo(BROKEN);
        assertThat(store.item("it-v2").orElseThrow().body()).isEqualTo(V2);

        JsonNode audit = call("GET", "/api/v1/admin/audit?limit=200", null);
        assertThat(audit.findValuesAsText("action")).contains("bi-expressions-migrated", "bi-migration-run");
    }

    @Test @Order(3)
    void theTextBeforeIsRestorable() throws Exception {
        int before = store.revisions("it-published").stream().filter(rv -> rv.body().equals(OLD)).findFirst().orElseThrow().seq();
        JsonNode restored = call("POST", "/api/v1/bi/items/it-published/history/" + before + "/restore", null);
        assertThat(restored.path("status").asText()).isEqualTo("draft");
        assertThat(store.item("it-published").orElseThrow().body()).isEqualTo(OLD);
    }

    @Test @Order(4)
    void anItemThatCannotBeConvertedIsMarkedWhereAnAdminLooks() throws Exception {
        JsonNode problems = call("GET", "/api/v1/bi/migrate/problems", null);
        assertThat(problems.path("count").asInt()).isEqualTo(1);
        JsonNode p = problems.path("problems").get(0);
        assertThat(p.path("item").asText()).isEqualTo("it-broken");
        assertThat(p.path("step").asText()).isEqualTo("expressions");
        assertThat(p.path("problem").asText()).contains("not converted, the item keeps its text").contains("DRS-");
        assertThat(call("GET", "/api/v1/bi/settings", null).path("migrationProblems").size()).isEqualTo(1);
        assertThat(call("GET", "/api/v1/bi/items/it-broken", null).path("migrationProblems").size()).isEqualTo(1);
    }

    @Test @Order(5)
    void aSecondRunIsIdempotentAndARestoredOldTextIsConvertedAgain() throws Exception {
        JsonNode again = call("POST", "/api/v1/bi/migrate/expressions", Map.of());
        assertThat(again.path("converted").asInt()).as("only the item restored to its old text").isEqualTo(1);
        assertThat(find(again, "it-published").path("published").size()).as("its published text was converted already").isZero();
        JsonNode third = call("POST", "/api/v1/bi/migrate/expressions", Map.of());
        assertThat(third.path("converted").asInt()).isZero();
        assertThat(store.item("it-published").orElseThrow().publishedVersion()).isEqualTo(2);
    }

    static JsonNode find(JsonNode r, String id) {
        for (JsonNode i : r.path("items")) {
            if (id.equals(i.path("id").asText())) {
                return i;
            }
        }
        return null;
    }
}
