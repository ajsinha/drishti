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

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * Rupaka phase 4 over the whole stack, security on: every save is a saved state with a diff and a restore, review with a data preview and four-eyes,
 * certification that only a steward gives and that falls when the dataset changes incompatibly, lineage, impact analysis that refuses a publish which breaks
 * published reports unless a bi-admin forces it, bundles for moving content between environments, and the audit trail of all of it.
 *
 * <p>Users: ann writes (bi-author), bob writes and approves (bi-author, approve), sue is a steward (bi-steward), cat reads (bi).
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/bi-gov-${random.uuid}/identity.db", "drishti.packs.overlay=target/bi-gov-overlay/added.yaml",
        "drishti.bi.dir=target/bi-gov-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=target/bi-gov-store/${random.uuid}"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BiGovernanceApiTest {

    private static final String DATASET = "dataset: gov-ds\ntitle: Governed\ntables:\n  t:\n    source: { kind: trade }\n    columns: { a: string, b: string }\n"
            + "measures:\n  N: { expr: \"count(t)\" }\n  M: { expr: \"count(t)\" }\n";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired AuditLog audit;
    final ObjectMapper json = new ObjectMapper();

    String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    String admin() {
        return as("drishti-dev-admin", "admin");
    }

    String ann() {
        return as("ann", "writer");
    }

    String bob() {
        return as("bob", "signoff");
    }

    String cat() {
        return as("cat", "reader");
    }

    String sue() {
        return as("sue", "steward");
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

    void refused(ResultActions r, int status, String code) throws Exception {
        r.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }

    @BeforeAll
    void setUp() throws Exception {
        role("writer", List.of("bi", "bi-author"), false);
        role("signoff", List.of("bi", "bi-author"), true);
        role("reader", List.of("bi"), false);
        role("steward", List.of("bi", "bi-steward"), false);
        for (String u : List.of("ann:writer", "bob:signoff", "cat:reader", "sue:steward")) {
            String[] n = u.split(":");
            mvc.perform(post("/api/v1/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", n[0], "displayName", n[0], "email", n[0] + "@desk.test", "roles", List.of(n[1]),
                            "packs", List.of("finance"), "password", "long-enough-pass-1")))).andExpect(status().isCreated());
        }
        ws = ok(call("POST", "/api/v1/bi/workspaces", ann(), Map.of("name", "Gov Desk"))).path("id").asText();
        call("PUT", "/api/v1/bi/workspaces/" + ws + "/members/bob", ann(), Map.of("role", "member")).andExpect(status().isOk());
        call("PUT", "/api/v1/bi/workspaces/" + ws + "/members/cat", ann(), Map.of("role", "viewer")).andExpect(status().isOk());
    }

    void role(String name, List<String> bi, boolean approve) throws Exception {
        mvc.perform(put("/api/v1/admin/role-definitions/" + name).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kinds", List.of("*"), "approve", approve, "bi", bi)))).andExpect(status().isOk());
    }

    String ws;
    String ds;
    String rep;

    String itemPath(String id) {
        return "/api/v1/bi/items/" + id;
    }

    String report(String dataset) {
        return "report: gov-rep\ndataset: " + dataset + "\npages:\n  - id: main\n    title: Main\n    visuals:\n"
                + "      - { id: n, type: card, title: N, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [N] } }\n"
                + "      - { id: bar, type: bar, title: By b, at: { x: 3, y: 0, w: 6, h: 4 }, wells: { axis: [t.b], values: [N] } }\n";
    }

    // ---- versions ---------------------------------------------------------------------------------------------------------

    @Test @Order(1)
    void everySaveIsAVersionWithADiffAndARestore() throws Exception {
        ds = ok(call("POST", "/api/v1/bi/workspaces/" + ws + "/items", ann(), Map.of("kind", "dataset", "title", "Governed", "body", DATASET, "message", "first cut"))).path("id").asText();
        String two = DATASET.replace("count(t)\" }\n  M", "count(t)\", format: amount0 }\n  M");
        call("PUT", itemPath(ds), ann(), Map.of("body", two, "message", "format the count")).andExpect(status().isOk());
        String three = two.replace("a: string, b: string", "a: string, b: string, c: string");
        call("PUT", itemPath(ds), ann(), Map.of("body", three, "message", "add c")).andExpect(status().isOk());
        call("PUT", itemPath(ds), ann(), Map.of("description", "catalogue only")).andExpect(status().isOk());       // no new saved state: the body did not change

        JsonNode h = ok(call("GET", itemPath(ds) + "/history", ann(), null));
        assertThat(h.path("revisions").size()).isEqualTo(3);
        assertThat(h.path("revisions").get(0).path("message").asText()).isEqualTo("add c");
        assertThat(h.path("revisions").get(0).path("author").asText()).isEqualTo("ann");
        assertThat(h.path("revisions").get(2).path("origin").asText()).isEqualTo("create");
        assertThat(h.path("canRestore").asBoolean()).isTrue();

        JsonNode d = ok(call("GET", itemPath(ds) + "/diff?from=r2&to=draft", ann(), null));
        assertThat(d.path("identical").asBoolean()).isFalse();
        assertThat(d.path("changed").asInt()).isEqualTo(1);
        assertThat(d.toString()).contains("c: string");
        assertThat(ok(call("GET", itemPath(ds) + "/diff?from=r1&to=r1", ann(), null)).path("identical").asBoolean()).isTrue();
        refused(call("GET", itemPath(ds) + "/diff?from=nonsense&to=draft", ann(), null), 422, "DRS-9003");

        JsonNode restored = ok(call("POST", itemPath(ds) + "/history/1/restore", ann(), Map.of()));
        assertThat(restored.path("body").asText()).isEqualTo(DATASET);
        assertThat(restored.path("status").asText()).isEqualTo("draft");
        JsonNode after = ok(call("GET", itemPath(ds) + "/history", ann(), null));
        assertThat(after.path("revisions").size()).isEqualTo(4);                                     // nothing was lost: the restore is a new saved state
        assertThat(after.path("revisions").get(0).path("origin").asText()).isEqualTo("restore");
        refused(call("GET", itemPath(ds) + "/history", cat(), null), 404, "DRS-9001");              // not a member of the item's draft: it is not even there for a reader yet
    }

    // ---- review -------------------------------------------------------------------------------------------------------------

    @Test @Order(2)
    void aStewardReviewsWithTheDiffCheckAndPreviewAndFourEyesHolds() throws Exception {
        call("POST", itemPath(ds) + "/propose", ann(), Map.of("note", "please look")).andExpect(status().isOk());
        // the steward is told, and sees the draft although not a member of the workspace
        JsonNode inbox = ok(call("GET", "/api/v1/me/inbox?limit=20", sue(), null));
        assertThat(inbox.toString()).contains("bi-review").contains("asks for review");
        JsonNode r = ok(call("GET", itemPath(ds) + "/review", sue(), null));
        assertThat(r.path("request").path("actor").asText()).isEqualTo("ann");
        assertThat(r.path("diff").path("added").asInt()).isGreaterThan(5);                           // nothing published yet: every line is new
        assertThat(r.path("check").path("ok").asBoolean()).isTrue();
        assertThat(r.path("preview").has("available")).isTrue();                                    // rows when the source is up, else the reason
        assertThat(r.path("mayDecide").asBoolean()).isTrue();
        refused(call("GET", itemPath(ds) + "/review", cat(), null), 404, "DRS-9001");
        // the author may not decide her own request
        refused(call("POST", itemPath(ds) + "/approve", ann(), Map.of()), 403, "DRS-9002");           // ann has no approve power and is no steward
        // rejection needs a reason; the author is told
        refused(call("POST", itemPath(ds) + "/reject", sue(), Map.of()), 422, "DRS-9003");
        ok(call("POST", itemPath(ds) + "/reject", sue(), Map.of("reason", "name the columns")));
        assertThat(ok(call("GET", "/api/v1/me/inbox?limit=20", ann(), null)).toString()).contains("bi-decision").contains("name the columns");
        // four eyes: bob proposes, bob may not approve
        call("POST", itemPath(ds) + "/propose", ann(), Map.of()).andExpect(status().isOk());
        JsonNode other = ok(call("POST", "/api/v1/bi/workspaces/" + ws + "/items", bob(), Map.of("kind", "dataset", "title", "Bob's", "body", DATASET.replace("gov-ds", "bobs"))));
        call("POST", itemPath(other.path("id").asText()) + "/propose", bob(), Map.of()).andExpect(status().isOk());
        refused(call("POST", itemPath(other.path("id").asText()) + "/approve", bob(), Map.of()), 403, "DRS-9005");
        call("POST", itemPath(other.path("id").asText()) + "/withdraw", bob(), Map.of()).andExpect(status().isOk());
        // the steward approves with a comment: published as version 1
        JsonNode done = ok(call("POST", itemPath(ds) + "/approve", sue(), Map.of("note", "looks right")));
        assertThat(done.path("status").asText()).isEqualTo("published");
        assertThat(done.path("publishedVersion").asInt()).isEqualTo(1);
        JsonNode events = ok(call("GET", itemPath(ds) + "/history", ann(), null)).path("events");
        assertThat(events.toString()).contains("\"action\":\"approve\"").contains("looks right").contains("\"action\":\"reject\"").contains("\"action\":\"request\"");
        assertThat(ok(call("GET", itemPath(ds) + "/history", ann(), null)).path("revisions").toString()).contains("\"publishedVersion\":1");
    }

    // ---- certification and lineage ------------------------------------------------------------------------------------------

    @Test @Order(3)
    void onlyAStewardCertifiesAndTheBadgeShowsInTheCatalogue() throws Exception {
        rep = ok(call("POST", "/api/v1/bi/workspaces/" + ws + "/items", ann(), Map.of("kind", "report", "title", "Governed report", "body", report(ds)))).path("id").asText();
        ok(call("POST", itemPath(rep) + "/publish", ann(), Map.of("note", "v1")));
        refused(call("POST", itemPath(ds) + "/endorse", ann(), Map.of("level", "certified")), 403, "DRS-9002");
        ok(call("POST", itemPath(ds) + "/endorse", sue(), Map.of("level", "certified")));
        ok(call("POST", itemPath(rep) + "/endorse", sue(), Map.of("level", "certified")));
        JsonNode cat = ok(call("GET", "/api/v1/bi/catalogue?endorsement=certified", cat(), null));
        assertThat(cat.path("total").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(cat.path("items").get(0).path("endorsement").asText()).isEqualTo("certified");
    }

    @Test @Order(4)
    void lineageShowsWhereADatasetComesFromAndWhoReadsIt() throws Exception {
        JsonNode l = ok(call("GET", itemPath(ds) + "/lineage", ann(), null));
        assertThat(l.path("upstream").get(0).path("kind").asText()).isEqualTo("trade");
        assertThat(l.path("upstream").get(0).path("packs").toString()).contains("finance");
        assertThat(l.path("downstream").size()).isEqualTo(1);
        assertThat(l.path("downstream").get(0).path("uses").path("fields").toString()).contains("t.b");
        assertThat(l.path("downstream").get(0).path("uses").path("measures").toString()).contains("N");
        JsonNode r = ok(call("GET", itemPath(rep) + "/lineage", ann(), null));
        assertThat(r.path("dataset").path("id").asText()).isEqualTo(ds);
        assertThat(r.path("uses").path("fields").toString()).contains("t.b");
        assertThat(r.path("upstream").get(0).path("table").asText()).isEqualTo("t");
        // a reader cannot see a draft report's name through lineage
        assertThat(ok(call("GET", itemPath(ds) + "/lineage", cat(), null)).path("hidden").asInt()).isZero();
    }

    // ---- impact -------------------------------------------------------------------------------------------------------------

    @Test @Order(5)
    void impactAnalysisAnswersWhatBreaksAndRefusesABreakingPublishUnlessForced() throws Exception {
        JsonNode what = ok(call("GET", itemPath(ds) + "/impact?remove=t.b", ann(), null));
        assertThat(what.path("breaksPublished").asBoolean()).isTrue();
        assertThat(what.path("published").get(0).path("title").asText()).isEqualTo("Governed report");
        assertThat(what.path("published").get(0).path("uses").toString()).contains("t.b");
        assertThat(what.path("wouldRevokeCertification").toString()).contains("Governed report").contains("Governed");
        assertThat(ok(call("GET", itemPath(ds) + "/impact?remove=t.a", ann(), null)).path("breaksPublished").asBoolean()).isFalse();   // nothing uses a
        assertThat(ok(call("GET", itemPath(ds) + "/impact?remove=M", ann(), null)).path("breaksPublished").asBoolean()).isFalse();      // a measure no report uses

        String broken = DATASET.replace("a: string, b: string", "a: string, bee: string");           // b renamed: gone
        call("PUT", itemPath(ds), ann(), Map.of("body", broken, "message", "rename b")).andExpect(status().isOk());
        assertThat(ok(call("GET", itemPath(ds) + "/impact", ann(), null)).path("refused").asBoolean()).isTrue();
        call("POST", itemPath(ds) + "/impact", ann(), Map.of("yaml", broken)).andExpect(status().isOk()).andExpect(jsonPath("$.breaksPublished").value(true));
        // the publish is refused, and says which report breaks
        call("POST", itemPath(ds) + "/publish", ann(), Map.of("note", "rename")).andExpect(status().is(409)).andExpect(jsonPath("$.code").value("DRS-9040"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Governed report")));
        refused(call("POST", itemPath(ds) + "/publish", ann(), Map.of("note", "rename", "force", true)), 403, "DRS-9002");     // forcing is for bi-admin
        // the admin forces it: published with warnings, and the certification of the dataset and of the report is gone
        JsonNode forced = ok(call("POST", itemPath(ds) + "/publish", admin(), Map.of("note", "rename, on purpose", "force", true)));
        assertThat(forced.path("publishedVersion").asInt()).isEqualTo(2);
        assertThat(forced.path("endorsement").asText()).isEqualTo("none");
        assertThat(forced.path("warnings").toString()).contains("Certification of 'Governed' was revoked").contains("Certification of 'Governed report' was revoked").contains("broken");
        assertThat(ok(call("GET", itemPath(rep), ann(), null)).path("endorsement").asText()).isEqualTo("none");
        assertThat(ok(call("GET", itemPath(ds) + "/history", ann(), null)).path("events").toString()).contains("\"action\":\"revoke\"");
        assertThat(ok(call("GET", "/api/v1/me/inbox?limit=20", sue(), null)).toString()).contains("bi-certification").contains("revoked");
    }

    // ---- environments -------------------------------------------------------------------------------------------------------

    @Test @Order(6)
    void aBundleCarriesItemsBetweenEnvironmentsAndADryRunShowsTheConflicts() throws Exception {
        call("PUT", "/api/v1/bi/workspaces/" + ws + "/members/sue", ann(), Map.of("role", "member")).andExpect(status().isOk());     // the importing steward is a member of where it lands
        // put the dataset right again (add b back), re-publish, so the bundle holds a coherent pair
        call("PUT", itemPath(ds), ann(), Map.of("body", DATASET, "message", "b is back")).andExpect(status().isOk());
        ok(call("POST", itemPath(ds) + "/publish", ann(), Map.of("note", "restore b")));
        call("PUT", itemPath(rep), ann(), Map.of("tags", List.of("sample", "gov"))).andExpect(status().isOk());
        refused(call("GET", "/api/v1/bi/bundle?items=" + rep, cat(), null), 403, "DRS-9002");
        JsonNode bundle = ok(call("GET", "/api/v1/bi/bundle?items=" + rep, ann(), null));
        assertThat(bundle.path("format").asText()).isEqualTo("drishti-bi-bundle");
        assertThat(bundle.path("items").size()).isEqualTo(2);                                          // the report and the dataset it reads
        JsonNode first = bundle.path("items").get(0);
        assertThat(first.path("versions").size()).isGreaterThanOrEqualTo(1);
        assertThat(bundle.path("items").toString()).contains("\"sample\":true").contains("\"owner\":\"ann\"");

        // identical content: nothing to do
        JsonNode same = ok(call("POST", "/api/v1/bi/bundle/import", sue(), Map.of("bundle", bundle, "dryRun", true)));
        assertThat(same.path("counts").path("unchanged").asInt()).isEqualTo(2);
        assertThat(same.path("applied").asBoolean()).isFalse();
        refused(call("POST", "/api/v1/bi/bundle/import", ann(), Map.of("bundle", bundle, "dryRun", true)), 403, "DRS-9002");              // a steward's act

        // the content here moves on; the old bundle now conflicts
        call("PUT", itemPath(rep), ann(), Map.of("body", report(ds) + "      - { id: m, type: card, title: M, at: { x: 9, y: 0, w: 3, h: 2 }, wells: { values: [M] } }\n", "message", "more")).andExpect(status().isOk());
        JsonNode dry = ok(call("POST", "/api/v1/bi/bundle/import", sue(), Map.of("bundle", bundle, "dryRun", true)));
        assertThat(dry.path("counts").path("conflict").asInt()).isEqualTo(1);
        assertThat(dry.toString()).contains("differs");
        refused(call("POST", "/api/v1/bi/bundle/import", sue(), Map.of("bundle", bundle, "dryRun", false)), 409, "DRS-9042");
        JsonNode over = ok(call("POST", "/api/v1/bi/bundle/import", sue(), Map.of("bundle", bundle, "dryRun", false, "overwrite", true)));
        assertThat(over.path("applied").asBoolean()).isTrue();
        assertThat(ok(call("GET", itemPath(rep), ann(), null)).path("body").asText()).doesNotContain("title: M,");
        assertThat(ok(call("GET", itemPath(rep) + "/history", ann(), null)).path("revisions").get(0).path("origin").asText()).isEqualTo("import");

        // another environment: delete here, import with the owner and workspace mapped
        call("DELETE", itemPath(rep), admin(), null).andExpect(status().is2xxSuccessful());
        call("DELETE", itemPath(ds), admin(), null).andExpect(status().is2xxSuccessful());
        String other = ok(call("POST", "/api/v1/bi/workspaces", ann(), Map.of("name", "Prod Desk"))).path("id").asText();
        call("PUT", "/api/v1/bi/workspaces/" + other + "/members/sue", ann(), Map.of("role", "member")).andExpect(status().isOk());
        JsonNode mapped = ok(call("POST", "/api/v1/bi/bundle/import", sue(), Map.of("bundle", bundle, "dryRun", false, "overwrite", true, "workspaces", Map.of(ws, other), "owners", Map.of("ann", "bob"))));
        assertThat(mapped.path("items").toString()).contains("\"owner\":\"bob\"");
        JsonNode landed = ok(call("GET", itemPath(rep), sue(), null));
        assertThat(landed.path("owner").asText()).isEqualTo("bob");
        assertThat(landed.path("workspace").asText()).isEqualTo(other);
        assertThat(landed.path("endorsement").asText()).isEqualTo("none");                             // certification does not travel
        assertThat(ok(call("GET", itemPath(rep) + "/versions", sue(), null)).size()).isGreaterThanOrEqualTo(1);

        // a changed file is refused whole
        ObjectNode tampered = (ObjectNode) bundle.deepCopy();
        ((ObjectNode) tampered.path("items").get(0)).put("body", "dataset: evil\n");
        refused(call("POST", "/api/v1/bi/bundle/import", sue(), Map.of("bundle", tampered, "dryRun", true)), 422, "DRS-9041");
        refused(call("POST", "/api/v1/bi/bundle/import", sue(), Map.of("bundle", Map.of("format", "other"), "dryRun", true)), 422, "DRS-9041");
    }

    // ---- audit --------------------------------------------------------------------------------------------------------------

    @Test @Order(7)
    void everyGovernanceActIsAudited() throws Exception {
        String all = audit.recent(2000, null).stream().map(e -> e.action() + ":" + e.subject() + ":" + e.actor()).reduce("", (a, b) -> a + "\n" + b);
        assertThat(all).contains("bi-item-proposed:" + ds + ":ann").contains("bi-item-approved:" + ds + ":sue").contains("bi-item-rejected:" + ds + ":sue")
                .contains("bi-item-published:" + ds + ":drishti-dev-admin").contains("bi-item-endorsed:" + ds + ":sue").contains("bi-item-restored:" + ds + ":ann")
                .contains("bi-certification-revoked:" + ds).contains("bi-bundle-exported:bundle:ann").contains("bi-bundle-imported:bundle:sue").contains("bi-item-imported:" + rep + ":sue");
    }
}
