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
package com.ash.drishti.server.bi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Governance beyond removal, over the whole stack: a field or measure renamed in a dataset (the same path or expression under a new name) is recognised and
 * offered as a rename that rewrites the reports that use it when the dataset is published; a changed type or meaning is reported as an impact of its own;
 * an import keeps what it replaced and can be rolled back (refused when an item was edited since, unless a bi-admin forces it); lineage reaches past the
 * connector to the table, topic or path it declares, masked for people who are not administrators.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/bi-gov2-${random.uuid}/identity.db", "drishti.packs.overlay=${drishti.test.dir}/bi-gov2-overlay/added.yaml",
        "drishti.bi.dir=${drishti.test.dir}/bi-gov2-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=${drishti.test.dir}/bi-gov2-store/${random.uuid}",
        "drishti.bi.governance.imports-dir=${drishti.test.dir}/bi-gov2-imports/${random.uuid}"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BiGovernanceRenameTest {

    private static final String DATASET = "dataset: ren-ds\ntitle: Renamed\ntables:\n  t:\n    source: { kind: trade }\n    columns:\n      a: string\n      b: { path: obj.b, type: string }\n"
            + "measures:\n  N: { expr: \"count(t)\" }\n  Size: { expr: \"count(t)\" }\n";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();
    String ws;
    String ds;
    String rep;
    final String conn = "orders-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    String admin() {
        return as("drishti-dev-admin", "admin");
    }

    String ann() {
        return as("ann", "writer");
    }

    String sue() {
        return as("sue", "steward");
    }

    String cat() {
        return as("cat", "reader");
    }

    ResultActions call(String method, String path, String who, Object body) throws Exception {
        var req = switch (method) {
            case "POST" -> post(java.net.URI.create(path));
            case "PUT" -> put(java.net.URI.create(path));
            case "DELETE" -> delete(java.net.URI.create(path));
            default -> get(java.net.URI.create(path));
        };
        req.header("Authorization", who);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    void role(String name, List<String> bi) throws Exception {
        mvc.perform(put("/api/v1/admin/role-definitions/" + name).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kinds", List.of("*"), "approve", false, "bi", bi)))).andExpect(status().isOk());
    }

    String item(String id) {
        return "/api/v1/bi/items/" + id;
    }

    String report() {
        return "report: ren-rep\ndataset: " + ds + "\npages:\n  - id: main\n    title: Main\n    visuals:\n"
                + "      - { id: n, type: card, title: Size, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Size] } }\n"
                + "      - { id: bar, type: bar, title: By b, at: { x: 3, y: 0, w: 6, h: 4 }, wells: { axis: [t.b], values: [N] }, filters: [{ field: t.b, op: eq, values: [x] }] }\n";
    }

    @BeforeAll
    void setUp() throws Exception {
        role("writer", List.of("bi", "bi-author"));
        role("reader", List.of("bi"));
        role("steward", List.of("bi", "bi-steward"));
        for (String u : List.of("ann:writer", "cat:reader", "sue:steward")) {
            String[] n = u.split(":");
            mvc.perform(post("/api/v1/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", n[0], "displayName", n[0], "email", n[0] + "@desk.test", "roles", List.of(n[1]),
                            "packs", List.of("finance"), "password", "long-enough-pass-1")))).andExpect(status().isCreated());
        }
        ws = ok(call("POST", "/api/v1/bi/workspaces", ann(), Map.of("name", "Rename Desk"))).path("id").asText();
        call("PUT", "/api/v1/bi/workspaces/" + ws + "/members/sue", ann(), Map.of("role", "member")).andExpect(status().isOk());
        ds = ok(call("POST", "/api/v1/bi/workspaces/" + ws + "/items", ann(), Map.of("kind", "dataset", "title", "Renamed", "body", DATASET, "audienceRoles", List.of("steward", "reader")))).path("id").asText();
        ok(call("POST", item(ds) + "/publish", ann(), Map.of("note", "v1")));
        rep = ok(call("POST", "/api/v1/bi/workspaces/" + ws + "/items", ann(), Map.of("kind", "report", "title", "Renamed report", "body", report(), "audienceRoles", List.of("steward", "reader")))).path("id").asText();
        ok(call("POST", item(rep) + "/publish", ann(), Map.of("note", "v1")));
    }

    // ---- rename ------------------------------------------------------------------------------------------------------------

    @Test @Order(1)
    void aFieldAndAMeasureRenamedWithTheSamePathOrExpressionAreRecognisedAsRenames() throws Exception {
        String renamed = DATASET.replace("      b: { path: obj.b, type: string }", "      bee: { path: obj.b, type: string }").replace("  Size: { expr: \"count(t)\" }", "  Rows: { expr: \"count(t)\" }");
        call("PUT", item(ds), ann(), Map.of("body", renamed, "message", "rename b and Size")).andExpect(status().isOk());
        JsonNode im = ok(call("GET", item(ds) + "/impact", ann(), null));
        assertThat(im.path("renames").toString()).contains("\"from\":\"t.b\"").contains("\"to\":\"t.bee\"").contains("\"from\":\"Size\"").contains("\"to\":\"Rows\"");
        assertThat(im.path("breaksPublished").asBoolean()).isTrue();
        assertThat(im.path("renamable").asBoolean()).isTrue();
        assertThat(im.path("published").get(0).path("uses").toString()).contains("[renamed to t.bee]").contains("[renamed to Rows]");
        // without asking for the renames the publish is refused, as before
        call("POST", item(ds) + "/publish", ann(), Map.of("note", "rename")).andExpect(status().is(409)).andExpect(jsonPath("$.code").value("DRS-9040"));
        // with them, the reports follow in the same act
        JsonNode done = ok(call("POST", item(ds) + "/publish", ann(), Map.of("note", "rename", "applyRenames", true)));
        assertThat(done.path("publishedVersion").asInt()).isEqualTo(2);
        assertThat(done.path("warnings").toString()).contains("Report 'Renamed report': 3 name(s) updated, published as version 2");
        JsonNode r = ok(call("GET", item(rep), ann(), null));
        assertThat(r.path("publishedVersion").asInt()).isEqualTo(2);
        assertThat(r.path("status").asText()).isEqualTo("published");
        assertThat(r.path("body").asText()).contains("t.bee").contains("Rows").doesNotContain("t.b,").doesNotContain("[Size]").contains("title: Size");      // the title is not a name
        // the reader's view of the published report works from the new names
        assertThat(ok(call("GET", item(rep) + "/impact", ann(), null)).path("applies").asBoolean()).isFalse();
        JsonNode events = ok(call("GET", item(rep) + "/history", ann(), null)).path("events");
        assertThat(events.toString()).contains("\"action\":\"rename\"");
        assertThat(ok(call("GET", item(rep) + "/history", ann(), null)).path("revisions").get(0).path("origin").asText()).isEqualTo("rename");
    }

    @Test @Order(2)
    void aChangedTypeOrMeaningIsAnImpactNotARemoval() throws Exception {
        String changed = ok(call("GET", item(ds), ann(), null)).path("body").asText().replace("a: string", "a: integer").replace("Rows: { expr: \"count(t)\" }", "Rows: { expr: \"count(t) * 2\" }")
                .replace("path: obj.b", "path: obj.other");
        // the report uses t.bee and Rows: their meaning changed; t.a is not used
        call("PUT", item(ds), ann(), Map.of("body", changed, "message", "retype")).andExpect(status().isOk());
        JsonNode im = ok(call("GET", item(ds) + "/impact", ann(), null));
        assertThat(im.path("change").path("breaking").asBoolean()).isFalse();
        assertThat(im.path("change").path("alters").toString()).contains("\"name\":\"t.a\"").contains("\"what\":\"type\"").contains("\"name\":\"Rows\"").contains("\"name\":\"t.bee\"");
        JsonNode altered = im.path("altered");
        assertThat(altered.size()).isEqualTo(1);
        assertThat(altered.get(0).path("title").asText()).isEqualTo("Renamed report");
        assertThat(altered.get(0).path("uses").toString()).contains("meaning").contains("Rows").contains("t.bee").doesNotContain("t.a:");
        assertThat(im.path("refused").asBoolean()).as("a changed meaning is shown, not refused").isFalse();
        call("PUT", item(ds), ann(), Map.of("body", ok(call("GET", item(ds) + "/history/" + (revisions(ds) - 1), ann(), null)).path("body").asText(), "message", "back")).andExpect(status().isOk());
    }

    int revisions(String id) throws Exception {
        return ok(call("GET", item(id) + "/history", ann(), null)).path("revisions").size();
    }

    // ---- rolling an import back ---------------------------------------------------------------------------------------------

    @Test @Order(3)
    void anImportKeepsWhatItReplacedAndRollsBackUnlessEditedSince() throws Exception {
        JsonNode bundle = ok(call("GET", "/api/v1/bi/bundle?items=" + rep, ann(), null));
        String before = ok(call("GET", item(rep), ann(), null)).path("body").asText();
        // change the report here, then import the older bundle over it
        call("PUT", item(rep), ann(), Map.of("body", before + "      - { id: extra, type: card, title: Extra, at: { x: 9, y: 0, w: 3, h: 2 }, wells: { values: [Rows] } }\n", "message", "more")).andExpect(status().isOk());
        String edited = ok(call("GET", item(rep), ann(), null)).path("body").asText();
        JsonNode done = ok(call("POST", "/api/v1/bi/bundle/import", sue(), Map.of("bundle", bundle, "dryRun", false, "overwrite", true)));
        String importId = done.path("importId").asText();
        assertThat(importId).isNotBlank();
        assertThat(ok(call("GET", item(rep), ann(), null)).path("body").asText()).isEqualTo(before);
        JsonNode detail = ok(call("GET", "/api/v1/bi/imports/" + importId, sue(), null));
        assertThat(detail.path("items").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(detail.path("entries").toString()).contains("\"changedSince\":false");
        assertThat(ok(call("GET", "/api/v1/bi/imports", sue(), null)).path("imports").get(0).path("id").asText()).isEqualTo(importId);
        call("GET", "/api/v1/bi/imports", ann(), null).andExpect(status().isForbidden());                     // a steward's act

        // somebody edits after the import: the roll back stops, nothing changes
        call("PUT", item(rep), ann(), Map.of("body", before + "# edited after the import\n", "message", "after")).andExpect(status().isOk());
        call("POST", "/api/v1/bi/imports/" + importId + "/rollback", sue(), Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("edited or published since")));
        call("POST", "/api/v1/bi/imports/" + importId + "/rollback", sue(), Map.of("force", true)).andExpect(status().isForbidden());     // forcing is for bi-admin
        JsonNode back = ok(call("POST", "/api/v1/bi/imports/" + importId + "/rollback", admin(), Map.of("force", true)));
        assertThat(back.path("restored").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(back.path("forced").asBoolean()).isTrue();
        assertThat(ok(call("GET", item(rep), ann(), null)).path("body").asText()).isEqualTo(edited);
        assertThat(ok(call("GET", item(rep) + "/history", ann(), null)).path("events").toString()).contains("\"action\":\"rollback\"");
        call("POST", "/api/v1/bi/imports/" + importId + "/rollback", admin(), Map.of()).andExpect(status().isConflict());                  // once
        assertThat(ok(call("GET", "/api/v1/bi/imports/" + importId, sue(), null)).path("rolledBack").asBoolean()).isTrue();
    }

    @Test @Order(4)
    void aRolledBackImportTakesAwayWhatItCreated() throws Exception {
        JsonNode bundle = ok(call("GET", "/api/v1/bi/bundle?items=" + rep, ann(), null));
        call("DELETE", item(rep), admin(), null).andExpect(status().is2xxSuccessful());
        JsonNode done = ok(call("POST", "/api/v1/bi/bundle/import", sue(), Map.of("bundle", bundle, "dryRun", false, "overwrite", true)));
        assertThat(ok(call("GET", item(rep), ann(), null)).path("title").asText()).isEqualTo("Renamed report");
        JsonNode back = ok(call("POST", "/api/v1/bi/imports/" + done.path("importId").asText() + "/rollback", sue(), Map.of()));
        assertThat(back.path("removed").asInt()).isEqualTo(1);
        call("GET", item(rep), ann(), null).andExpect(status().isNotFound());
    }

    // ---- lineage past the connector ----------------------------------------------------------------------------------------

    @Test @Order(5)
    void lineageReachesPastTheConnectorToTheTableItDeclaresMaskedForNonAdministrators() throws Exception {
        call("PUT", "/api/v1/admin/connectors/" + conn, admin(), Map.of("plugin", "file", "kinds", List.of("trade"),
                "settings", Map.of("root", "/srv/lake/orders"))).andExpect(status().is2xxSuccessful());
        String src = "dataset: lin-ds\ntitle: Lineage\ntables:\n  o:\n    source: { connector: " + conn + ", kind: trade }\n    columns: { code: string }\nmeasures:\n  N: { expr: \"count(o)\" }\n";
        String id = ok(call("POST", "/api/v1/bi/workspaces/" + ws + "/items", ann(), Map.of("kind", "dataset", "title", "Lineage", "body", src, "audienceRoles", List.of("steward", "reader")))).path("id").asText();
        ok(call("POST", item(id) + "/publish", ann(), Map.of("note", "v1")));
        JsonNode asAdmin = ok(call("GET", item(id) + "/lineage", admin(), null)).path("upstream").get(0).path("source");
        assertThat(asAdmin.path("plugin").asText()).isEqualTo("file");
        assertThat(asAdmin.path("masked").asBoolean()).isFalse();
        assertThat(asAdmin.path("declared").path("root").asText()).isEqualTo("/srv/lake/orders");
        JsonNode asReader = ok(call("GET", item(id) + "/lineage", cat(), null)).path("upstream").get(0).path("source");
        assertThat(asReader.path("masked").asBoolean()).isTrue();
        assertThat(asReader.path("declared").path("root").asText()).isEqualTo("•••");
        assertThat(asReader.toString()).doesNotContain("/srv/lake");
    }
}
