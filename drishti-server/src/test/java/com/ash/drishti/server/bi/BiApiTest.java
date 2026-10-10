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
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Rupaka phase 1 over the whole stack, security on: powers on roles, workspaces and their roles, items as typed placeholders, the
 * lifecycle draft, proposed, published (with review and four-eyes), versions, endorsement, catalogue visibility by audience and role,
 * the shipped BI folder, favourites and recents, and the audit trail.
 *
 * <p>Roles: writer (bi, bi-author), signoff (bi, bi-author, approve), reader (bi), steward (bi, bi-steward), plain (no BI power).
 * Users: ann writer, bob approver, cat reader, sue steward, nobi plain.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance,logistics",
        "drishti.identity.database-url=jdbc:sqlite:target/bi-api-${random.uuid}/identity.db", "drishti.packs.overlay=target/bi-api-overlay/added.yaml",
        "drishti.bi.dir=target/bi-api-folder", "drishti.bi.folder-refresh=1s"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BiApiTest {

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

    String nobi() {
        return as("nobi", "plain");
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

    /** The shipped files exist before the context starts, so the folder's first read sees them (no race with its watcher). */
    static {
        try {
            Path reports = Path.of("target/bi-api-folder/reports");
            Files.createDirectories(reports);
            Files.writeString(reports.resolve("desk-pnl.report.yaml"), "title: Desk profit (shipped)\ndescription: From the BI folder\ntags: [pnl, desk]\nendorsement: certified\npages: []\n");
            Files.writeString(reports.resolve("risk-only.report.yaml"), "title: Risk only\naudience:\n  roles: [steward]\n");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @BeforeAll
    void setUp() throws Exception {
        role("writer", List.of("bi", "bi-author"), false);
        role("signoff", List.of("bi", "bi-author"), true);
        role("reader", List.of("bi"), false);
        role("steward", List.of("bi", "bi-steward"), false);
        role("plain", List.of(), false);
        for (String u : List.of("ann:writer", "bob:signoff", "cat:reader", "sue:steward", "nobi:plain")) {
            String[] n = u.split(":");
            mvc.perform(post("/api/v1/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", n[0], "displayName", n[0], "email", n[0] + "@desk.test", "roles", List.of(n[1]),
                            "packs", List.of("finance"), "password", "long-enough-pass-1")))).andExpect(status().isCreated());
        }
    }

    void role(String name, List<String> bi, boolean approve) throws Exception {
        var r = mvc.perform(put("/api/v1/admin/role-definitions/" + name).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kinds", List.of("*"), "approve", approve, "bi", bi)))).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
        assertThat(json.readTree(r.getContentAsString()).path("bi").size()).isEqualTo(bi.size());
    }

    String workspace;
    String item;

    @Test @Order(1)
    void powersDecideWhoOpensBi() throws Exception {
        assertThat(ok(call("GET", "/api/v1/bi/me", ann(), null)).path("powers").toString()).contains("bi-author").doesNotContain("bi-steward");
        assertThat(ok(call("GET", "/api/v1/bi/me", cat(), null)).path("mayCreateWorkspace").asBoolean()).isFalse();
        assertThat(ok(call("GET", "/api/v1/bi/me", admin(), null)).path("powers").size()).isEqualTo(4);
        refused(call("GET", "/api/v1/bi/home", nobi(), null), 403, "DRS-9002");
        refused(call("GET", "/api/v1/bi/catalogue", nobi(), null), 403, "DRS-9002");
        // saving a role without naming bi (Admin, Roles does) keeps the powers it had
        call("PUT", "/api/v1/admin/role-definitions/reader", admin(), Map.of("kinds", List.of("*"), "description", "edited elsewhere")).andExpect(status().isOk()).andExpect(jsonPath("$.bi.length()").value(1));
        call("PUT", "/api/v1/admin/role-definitions/typo", admin(), Map.of("kinds", List.of("*"), "bi", List.of("bi-god"))).andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("DRS-9003"));
    }

    @Test @Order(2)
    void workspacesAreCreatedByAuthorsAndSharedByRole() throws Exception {
        refused(call("POST", "/api/v1/bi/workspaces", cat(), Map.of("name", "Nope")), 403, "DRS-9002");
        JsonNode w = ok(call("POST", "/api/v1/bi/workspaces", ann(), Map.of("name", "Rates Desk", "description", "The desk")));
        workspace = w.path("id").asText();
        assertThat(workspace).isEqualTo("rates-desk");
        assertThat(w.path("role").asText()).isEqualTo("admin");
        refused(call("POST", "/api/v1/bi/workspaces", ann(), Map.of("name", "Rates Desk")), 409, "DRS-9004");
        refused(call("GET", "/api/v1/bi/workspaces/" + workspace, cat(), null), 404, "DRS-9001");        // not a member: its existence is not revealed
        call("PUT", "/api/v1/bi/workspaces/" + workspace + "/members/cat", ann(), Map.of("role", "viewer")).andExpect(status().isOk());
        call("PUT", "/api/v1/bi/workspaces/" + workspace + "/members/bob", ann(), Map.of("role", "member")).andExpect(status().isOk());
        refused(call("PUT", "/api/v1/bi/workspaces/" + workspace + "/members/ghost", ann(), Map.of("role", "viewer")), 422, "DRS-9003");
        refused(call("PUT", "/api/v1/bi/workspaces/" + workspace + "/members/cat", cat(), Map.of("role", "admin")), 403, "DRS-9002");
        refused(call("PUT", "/api/v1/bi/workspaces/" + workspace + "/members/ann", ann(), Map.of("role", "viewer")), 409, "DRS-9004");   // the last admin
        assertThat(ok(call("GET", "/api/v1/bi/workspaces", cat(), null)).toString()).contains("rates-desk").contains("shipped");
        assertThat(ok(call("GET", "/api/v1/bi/workspaces", sue(), null)).toString()).doesNotContain("rates-desk");
    }

    @Test @Order(3)
    void itemsAreTypedPlaceholdersWithYamlBodies() throws Exception {
        refused(call("POST", "/api/v1/bi/workspaces/" + workspace + "/items", cat(), Map.of("kind", "report", "title", "x")), 403, "DRS-9002");
        refused(call("POST", "/api/v1/bi/workspaces/" + workspace + "/items", ann(), Map.of("kind", "chart", "title", "x")), 422, "DRS-9003");
        refused(call("POST", "/api/v1/bi/workspaces/" + workspace + "/items", ann(), Map.of("kind", "report", "title", "x", "body", "a: [1")), 422, "DRS-9003");
        refused(call("POST", "/api/v1/bi/workspaces/" + workspace + "/items", ann(), Map.of("kind", "report", "title", "x", "audienceRoles", List.of("nope"))), 422, "DRS-9003");
        JsonNode i = ok(call("POST", "/api/v1/bi/workspaces/" + workspace + "/items", ann(),
                Map.of("kind", "report", "title", "Desk P&L", "description", "Daily P&L by book", "tags", List.of("pnl", "daily"), "body", "pages: []\n",
                        "audienceRoles", List.of("reader", "steward"))));
        item = i.path("id").asText();
        assertThat(i.path("status").asText()).isEqualTo("draft");
        assertThat(i.path("can").path("publish").asBoolean()).isTrue();
        refused(call("GET", "/api/v1/bi/items/" + item, sue(), null), 404, "DRS-9001");                    // a draft is for its workspace only
        refused(call("GET", "/api/v1/bi/items/" + item, cat(), null), 404, "DRS-9001");                    // even a viewer of that workspace reads what is published, not drafts
        assertThat(ok(call("GET", "/api/v1/bi/workspaces/" + workspace + "/items", cat(), null)).toString()).doesNotContain("Desk P&L");
        assertThat(ok(call("GET", "/api/v1/bi/catalogue", cat(), null)).path("items").toString()).doesNotContain("Desk P&L");
    }

    @Test @Order(4)
    void publishingMakesVersionsAndTheCatalogueShowsOnlyWhatTheAudienceMaySee() throws Exception {
        refused(call("POST", "/api/v1/bi/items/" + item + "/publish", cat(), Map.of()), 403, "DRS-9002");
        JsonNode p = ok(call("POST", "/api/v1/bi/items/" + item + "/publish", ann(), Map.of("note", "first")));
        assertThat(p.path("publishedVersion").asInt()).isEqualTo(1);
        assertThat(p.path("status").asText()).isEqualTo("published");
        refused(call("POST", "/api/v1/bi/items/" + item + "/publish", ann(), Map.of()), 409, "DRS-9004");     // nothing new
        assertThat(ok(call("GET", "/api/v1/bi/catalogue", cat(), null)).path("items").toString()).contains("Desk P&L");
        assertThat(ok(call("GET", "/api/v1/bi/catalogue", sue(), null)).path("items").toString()).contains("Desk P&L");     // in the audience by role
        // an item whose audience excludes sue
        JsonNode other = ok(call("POST", "/api/v1/bi/workspaces/" + workspace + "/items", ann(), Map.of("kind", "board", "title", "Members only")));
        ok(call("POST", "/api/v1/bi/items/" + other.path("id").asText() + "/publish", ann(), Map.of()));
        assertThat(ok(call("GET", "/api/v1/bi/catalogue", sue(), null)).path("items").toString()).doesNotContain("Members only");
        assertThat(ok(call("GET", "/api/v1/bi/catalogue", cat(), null)).path("items").toString()).contains("Members only");     // cat is a workspace member
        refused(call("GET", "/api/v1/bi/items/" + other.path("id").asText(), sue(), null), 404, "DRS-9001");
        // editing a published item leaves the published version visible and the draft pending
        ok(call("PUT", "/api/v1/bi/items/" + item, ann(), Map.of("body", "pages: [a]\n")));
        JsonNode viewer = ok(call("GET", "/api/v1/bi/items/" + item, cat(), null));
        assertThat(viewer.path("body").asText()).isEqualTo("pages: []\n");
        assertThat(viewer.path("bodyIs").asText()).isEqualTo("published");
        assertThat(ok(call("GET", "/api/v1/bi/items/" + item, ann(), null)).path("draftChanges").asBoolean()).isTrue();
        assertThat(ok(call("POST", "/api/v1/bi/items/" + item + "/publish", ann(), Map.of("note", "second"))).path("publishedVersion").asInt()).isEqualTo(2);
        JsonNode versions = ok(call("GET", "/api/v1/bi/items/" + item + "/versions", cat(), null));
        assertThat(versions.size()).isEqualTo(2);
        assertThat(ok(call("GET", "/api/v1/bi/items/" + item + "/versions/1", cat(), null)).path("body").asText()).isEqualTo("pages: []\n");
        JsonNode restored = ok(call("POST", "/api/v1/bi/items/" + item + "/versions/1/restore", ann(), null));
        assertThat(restored.path("status").asText()).isEqualTo("draft");
        assertThat(ok(call("GET", "/api/v1/bi/items/" + item, ann(), null)).path("body").asText()).isEqualTo("pages: []\n");
    }

    @Test @Order(5)
    void reviewBeforePublishUsesTheApproverPowerAndFourEyes() throws Exception {
        ok(call("PUT", "/api/v1/bi/workspaces/" + workspace, ann(), Map.of("reviewRequired", true)));
        JsonNode i = ok(call("POST", "/api/v1/bi/workspaces/" + workspace + "/items", ann(), Map.of("kind", "dataset", "title", "Trades", "body", "table: trades\n")));
        String id = i.path("id").asText();
        refused(call("POST", "/api/v1/bi/items/" + id + "/publish", ann(), Map.of()), 403, "DRS-9002");     // review is required
        assertThat(ok(call("POST", "/api/v1/bi/items/" + id + "/propose", ann(), Map.of("note", "please"))).path("status").asText()).isEqualTo("proposed");
        refused(call("PUT", "/api/v1/bi/items/" + id, ann(), Map.of("title", "Changed")), 409, "DRS-9004");   // frozen while proposed
        refused(call("POST", "/api/v1/bi/items/" + id + "/approve", ann(), Map.of()), 403, "DRS-9002");     // ann lacks the approve power
        JsonNode approved = ok(call("POST", "/api/v1/bi/items/" + id + "/approve", bob(), Map.of("note", "looks right")));
        assertThat(approved.path("publishedVersion").asInt()).isEqualTo(1);
        // four-eyes: bob proposes, bob may not approve
        JsonNode j = ok(call("POST", "/api/v1/bi/workspaces/" + workspace + "/items", bob(), Map.of("kind", "dataset", "title", "Cashflows")));
        String jid = j.path("id").asText();
        ok(call("POST", "/api/v1/bi/items/" + jid + "/propose", bob(), Map.of()));
        refused(call("POST", "/api/v1/bi/items/" + jid + "/approve", bob(), Map.of()), 403, "DRS-9005");
        refused(call("POST", "/api/v1/bi/items/" + jid + "/reject", bob(), Map.of("reason", "no")), 403, "DRS-9005");
    }

    @Test @Order(6)
    void rejectingNeedsAReasonAndWithdrawingReturnsToDraft() throws Exception {
        JsonNode i = ok(call("POST", "/api/v1/bi/workspaces/" + workspace + "/items", ann(), Map.of("kind", "report", "title", "To reject")));
        String id = i.path("id").asText();
        ok(call("POST", "/api/v1/bi/items/" + id + "/propose", ann(), Map.of()));
        refused(call("POST", "/api/v1/bi/items/" + id + "/reject", bob(), Map.of()), 422, "DRS-9003");
        assertThat(ok(call("POST", "/api/v1/bi/items/" + id + "/reject", bob(), Map.of("reason", "not yet"))).path("status").asText()).isEqualTo("draft");
        ok(call("POST", "/api/v1/bi/items/" + id + "/propose", ann(), Map.of()));
        assertThat(ok(call("POST", "/api/v1/bi/items/" + id + "/withdraw", ann(), null)).path("status").asText()).isEqualTo("draft");
    }

    @Test @Order(7)
    void endorsementPromotedByOwnersCertifiedBySteward() throws Exception {
        refused(call("POST", "/api/v1/bi/items/" + item + "/endorse", ann(), Map.of("level", "certified")), 403, "DRS-9002");
        assertThat(ok(call("POST", "/api/v1/bi/items/" + item + "/endorse", ann(), Map.of("level", "promoted"))).path("endorsement").asText()).isEqualTo("promoted");
        JsonNode c = ok(call("POST", "/api/v1/bi/items/" + item + "/endorse", sue(), Map.of("level", "certified")));
        assertThat(c.path("endorsement").asText()).isEqualTo("certified");
        assertThat(c.path("endorsedBy").asText()).isEqualTo("sue");
        refused(call("POST", "/api/v1/bi/items/" + item + "/endorse", ann(), Map.of("level", "none")), 403, "DRS-9002");     // only a steward removes certification
        // certified is pinned first and listed on Home
        JsonNode cat = ok(call("GET", "/api/v1/bi/catalogue?sort=title", cat(), null));
        assertThat(cat.path("total").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(ok(call("GET", "/api/v1/bi/catalogue", cat(), null)).path("items").get(0).path("endorsement").asText()).isEqualTo("certified");
        assertThat(ok(call("GET", "/api/v1/bi/home", cat(), null)).path("certified").toString()).contains("Desk P&L");
    }

    @Test @Order(8)
    void catalogueSearchesFiltersAndSorts() throws Exception {
        assertThat(ok(call("GET", "/api/v1/bi/catalogue?q=daily%20p%26l", cat(), null)).path("total").asInt()).isEqualTo(1);
        assertThat(ok(call("GET", "/api/v1/bi/catalogue?q=pnl", cat(), null)).path("total").asInt()).isEqualTo(2);       // tag on both the item and the shipped file
        assertThat(ok(call("GET", "/api/v1/bi/catalogue?kind=board", cat(), null)).path("total").asInt()).isEqualTo(1);
        assertThat(ok(call("GET", "/api/v1/bi/catalogue?endorsement=certified", cat(), null)).path("total").asInt()).isEqualTo(2);
        assertThat(ok(call("GET", "/api/v1/bi/catalogue?owner=ann&kind=report", cat(), null)).path("total").asInt()).isEqualTo(1);
        assertThat(ok(call("GET", "/api/v1/bi/catalogue?tag=daily", cat(), null)).path("total").asInt()).isEqualTo(1);
        assertThat(ok(call("GET", "/api/v1/bi/catalogue?fresh=1", cat(), null)).path("total").asInt()).isGreaterThanOrEqualTo(2);
        JsonNode byTitle = ok(call("GET", "/api/v1/bi/catalogue?sort=title", cat(), null)).path("items");
        assertThat(byTitle.get(0).path("title").asText()).isLessThan(byTitle.get(1).path("title").asText());
        assertThat(ok(call("GET", "/api/v1/bi/catalogue", cat(), null)).path("facets").path("kind").path("report").asInt()).isGreaterThanOrEqualTo(2);
    }

    @Test @Order(9)
    void shippedFolderItemsAreReadOnlyAndAudienceChecked() throws Exception {
        String shipped = "file:report:desk-pnl";
        JsonNode s = ok(call("GET", "/api/v1/bi/items/" + shipped, cat(), null));
        assertThat(s.path("title").asText()).isEqualTo("Desk profit (shipped)");
        assertThat(s.path("shipped").asBoolean()).isTrue();
        assertThat(s.path("can").path("edit").asBoolean()).isFalse();
        refused(call("PUT", "/api/v1/bi/items/" + shipped, ann(), Map.of("title", "x")), 409, "DRS-9004");
        refused(call("GET", "/api/v1/bi/items/file:report:risk-only", cat(), null), 404, "DRS-9001");
        ok(call("GET", "/api/v1/bi/items/file:report:risk-only", sue(), null));
    }

    @Test @Order(10)
    void favouritesAndRecentsFollowTheUser() throws Exception {
        ok(call("POST", "/api/v1/bi/items/" + item + "/open", cat(), null));
        ok(call("PUT", "/api/v1/bi/items/" + item + "/favourite", cat(), Map.of("on", true)));
        JsonNode home = ok(call("GET", "/api/v1/bi/home", cat(), null));
        assertThat(home.path("recent").toString()).contains("Desk P&L");
        assertThat(home.path("favourites").toString()).contains("Desk P&L");
        assertThat(home.path("shared").toString()).contains("Desk P&L");
        assertThat(ok(call("GET", "/api/v1/bi/home", sue(), null)).path("recent").size()).isEqualTo(0);
        assertThat(ok(call("GET", "/api/v1/bi/items/" + item, cat(), null)).path("views").asInt()).isEqualTo(1);
    }

    @Test @Order(11)
    void personalWorkspacesArePrivateAndCannotPublish() throws Exception {
        JsonNode mine = ok(call("GET", "/api/v1/bi/workspaces/me", ann(), null));
        assertThat(mine.path("personal").asBoolean()).isTrue();
        JsonNode d = ok(call("POST", "/api/v1/bi/workspaces/me/items", ann(), Map.of("kind", "notebook", "title", "Scratch")));
        String id = d.path("id").asText();
        refused(call("POST", "/api/v1/bi/items/" + id + "/publish", ann(), Map.of()), 409, "DRS-9004");
        refused(call("GET", "/api/v1/bi/items/" + id, bob(), null), 404, "DRS-9001");
        refused(call("PUT", "/api/v1/bi/workspaces/" + mine.path("id").asText() + "/members/bob", ann(), Map.of("role", "viewer")), 409, "DRS-9004");
        JsonNode moved = ok(call("POST", "/api/v1/bi/items/" + id + "/move", ann(), Map.of("workspace", workspace)));
        assertThat(moved.path("workspace").asText()).isEqualTo(workspace);
        refused(call("DELETE", "/api/v1/bi/workspaces/" + workspace, ann(), null), 409, "DRS-9004");      // still holds items
        call("DELETE", "/api/v1/bi/items/" + id, ann(), null).andExpect(status().isNoContent());
    }

    @Test @Order(12)
    void settingsChangeWhoCreatesWorkspaces() throws Exception {
        refused(call("GET", "/api/v1/bi/settings", ann(), null), 403, "DRS-9002");
        JsonNode s = ok(call("PUT", "/api/v1/bi/settings", admin(), Map.of("workspaceCreation", "admins")));
        assertThat(s.path("workspaceCreation").asText()).isEqualTo("admins");
        refused(call("POST", "/api/v1/bi/workspaces", ann(), Map.of("name", "Second")), 403, "DRS-9002");
        refused(call("PUT", "/api/v1/bi/settings", admin(), Map.of("workspaceCreation", "anyone")), 422, "DRS-9003");
        ok(call("PUT", "/api/v1/bi/settings", admin(), Map.of("workspaceCreation", "authors")));
        ok(call("POST", "/api/v1/bi/workspaces", ann(), Map.of("name", "Second")));
    }

    @Test @Order(13)
    void everyChangeIsAudited() throws Exception {
        String all = audit.recent(500, null).stream().map(e -> e.action() + ":" + e.subject() + ":" + e.actor()).reduce("", (a, b) -> a + "\n" + b);
        for (String action : List.of("bi-workspace-created", "bi-member-set", "bi-item-created", "bi-item-published", "bi-item-updated", "bi-item-proposed",
                "bi-item-approved", "bi-item-rejected", "bi-item-withdrawn", "bi-item-endorsed", "bi-item-restored", "bi-item-moved", "bi-item-deleted", "bi-settings-changed")) {
            assertThat(all).as(action).contains(action + ":");
        }
        assertThat(all).contains("bi-item-endorsed:" + item + ":sue");
    }
}
