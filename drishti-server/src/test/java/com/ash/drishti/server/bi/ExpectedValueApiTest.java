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
 * Expected values (docs/guides/REPORTS.md section 14) over the whole stack, security on: a dataset and a report declare {@code tests:}, Check runs them as the
 * caller or as a role, a failing test stops a publish (DRS-9043) unless a bi-admin forces it, and a person cannot run as a role they do not hold.
 *
 * <p>Users: ann writes (bi-author, role writer), sue is a steward (bi-steward), cat reads.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/bi-tests-${random.uuid}/identity.db", "drishti.packs.overlay=${drishti.test.dir}/bi-tests-overlay/added.yaml",
        "drishti.bi.dir=${drishti.test.dir}/bi-tests-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=${drishti.test.dir}/bi-tests-store/${random.uuid}"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExpectedValueApiTest {

    private static final String DATASET = "dataset: tested-ds\ntitle: Tested\ntables:\n  t:\n    source: { kind: trade }\n    columns: { a: string, b: string }\n"
            + "measures:\n  N: { expr: \"count(t)\" }\n";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
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

    void role(String name, List<String> bi) throws Exception {
        mvc.perform(put("/api/v1/admin/role-definitions/" + name).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kinds", List.of("*"), "approve", false, "bi", bi)))).andExpect(status().isOk());
    }

    String ws;
    String ds;
    String rep;
    long count;

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
        ws = ok(call("POST", "/api/v1/bi/workspaces", ann(), Map.of("name", "Tests Desk"))).path("id").asText();
        ds = ok(call("POST", "/api/v1/bi/workspaces/" + ws + "/items", ann(), Map.of("kind", "dataset", "title", "Tested", "body", DATASET, "audienceRoles", List.of("steward", "reader")))).path("id").asText();
        // the count the tests expect, as the dataset's own test runner sees it (the 2.1 dataset query route answers 410 since 2.2)
        call("PUT", itemPath(ds), ann(), Map.of("body", withTests("  - { name: probe, measure: N, expect: -1 }\n"))).andExpect(status().isOk());
        JsonNode probe = ok(call("POST", itemPath(ds) + "/tests", ann(), Map.of()));
        count = probe.path("results").get(0).path("actual").asLong();
        assertThat(count).as(probe.toString()).isPositive();
        call("PUT", itemPath(ds), ann(), Map.of("body", DATASET)).andExpect(status().isOk());
    }

    String withTests(String tests) {
        return DATASET + "tests:\n" + tests;
    }

    String itemPath(String id) {
        return "/api/v1/bi/items/" + id;
    }

    @Test @Order(1)
    void aDatasetsTestsPassFailOrCannotRunAndEachSaysWhy() throws Exception {
        String tests = "  - { name: exact, measure: N, expect: " + count + " }\n"
                + "  - { name: wrong, measure: N, expect: " + (count + 1000) + " }\n"
                + "  - { name: range, measure: N, expect: { min: " + (count - 1) + ", max: " + (count + 1) + " } }\n"
                + "  - { name: near by amount, measure: N, expect: " + (count + 3) + ", tolerance: 5 }\n"
                + "  - { name: near by percent, measure: N, expect: " + (count * 1.004) + ", tolerance: 1% }\n"
                + "  - { name: needs a row, measure: N, by: [t.a], expect: 1 }\n";
        call("PUT", itemPath(ds), ann(), Map.of("body", withTests(tests))).andExpect(status().isOk());
        JsonNode r = ok(call("POST", itemPath(ds) + "/tests", ann(), Map.of()));
        assertThat(r.path("total").asInt()).isEqualTo(6);
        Map<String, String> got = new java.util.LinkedHashMap<>();
        r.path("results").forEach(x -> got.put(x.path("name").asText(), x.path("status").asText()));
        assertThat(got).containsEntry("exact", "pass").containsEntry("wrong", "fail").containsEntry("range", "pass").containsEntry("near by amount", "pass")
                .containsEntry("near by percent", "pass");
        assertThat(got.get("needs a row")).isIn("fail", "error");                      // grouped by a column the sample rows do not make one row of
        assertThat(r.path("passed").asInt()).isEqualTo(4);
        assertThat(r.path("ok").asBoolean()).isFalse();
        JsonNode wrong = null;
        for (JsonNode x : r.path("results")) {
            if (x.path("name").asText().equals("wrong")) {
                wrong = x;
            }
        }
        assertThat(wrong.path("actual").asLong()).isEqualTo(count);
        assertThat(wrong.path("message").asText()).contains("expected").contains("got " + count);
    }

    @Test @Order(2)
    void aTestNamingAMeasureTheDatasetDoesNotHaveIsAProblemWhereYouCheckIt() throws Exception {
        JsonNode v = ok(call("POST", "/api/v1/bi/datasets/validate", ann(), Map.of("yaml", withTests("  - { measure: Nope, expect: 1 }\n  - { measure: N }\n  - { measure: N, expect: 1, tolerance: lots }\n"))));
        assertThat(v.path("valid").asBoolean()).isFalse();
        assertThat(v.path("problems").toString()).contains("tests[0].measure").contains("tests[1].expect").contains("tests[2].tolerance");
    }

    @Test @Order(3)
    void aFailingTestStopsAPublishAndOnlyABiAdminMayForceIt() throws Exception {
        call("POST", itemPath(ds) + "/publish", ann(), Map.of("note", "first")).andExpect(status().is(409)).andExpect(jsonPath("$.code").value("DRS-9043"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("wrong")));
        refused(call("POST", itemPath(ds) + "/publish", ann(), Map.of("note", "first", "force", true)), 403, "DRS-9002");
        JsonNode forced = ok(call("POST", itemPath(ds) + "/publish", admin(), Map.of("note", "on purpose", "force", true)));
        assertThat(forced.path("publishedVersion").asInt()).isEqualTo(1);
        // mend the expectation: the next publish needs no force
        String mended = withTests("  - { name: exact, measure: N, expect: " + count + " }\n");
        call("PUT", itemPath(ds), ann(), Map.of("body", mended)).andExpect(status().isOk());
        JsonNode second = ok(call("POST", itemPath(ds) + "/publish", ann(), Map.of("note", "mended")));
        assertThat(second.path("publishedVersion").asInt()).isEqualTo(2);
    }

    @Test @Order(4)
    void aReportsTestsAskAVisualOrAMeasureAndRunAsTheCallerOrARoleTheyHold() throws Exception {
        String report = "report: tested-rep\ndataset: " + ds + "\npages:\n  - id: main\n    title: Main\n    visuals:\n"
                + "      - { id: n, type: card, title: N, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [N] } }\n"
                + "tests:\n"
                + "  - { name: card shows the count, visual: n, expect: " + count + " }\n"
                + "  - { name: card is wrong, visual: n, expect: 1 }\n"
                + "  - { name: measure through the report, measure: N, expect: { min: 1 } }\n";
        rep = ok(call("POST", "/api/v1/bi/workspaces/" + ws + "/items", ann(), Map.of("kind", "report", "title", "Tested report", "body", report))).path("id").asText();
        JsonNode r = ok(call("POST", itemPath(rep) + "/tests", ann(), Map.of()));
        Map<String, String> got = new java.util.LinkedHashMap<>();
        r.path("results").forEach(x -> got.put(x.path("name").asText(), x.path("status").asText()));
        assertThat(got).containsEntry("card shows the count", "pass").containsEntry("card is wrong", "fail").containsEntry("measure through the report", "pass");
        call("PUT", "/api/v1/bi/workspaces/" + ws + "/members/sue", ann(), Map.of("role", "member")).andExpect(status().isOk());
        // an unsaved draft is checked from its text; a role the caller holds is fine, one they do not hold is refused as an error per test
        String draft = report.replace("expect: 1 }", "expect: " + count + " }");
        JsonNode d = ok(call("POST", itemPath(rep) + "/tests", ann(), Map.of("yaml", draft)));
        assertThat(d.path("ok").asBoolean()).isTrue();
        JsonNode asWriter = ok(call("POST", itemPath(rep) + "/tests", ann(), Map.of("role", "writer")));
        assertThat(asWriter.path("role").asText()).isEqualTo("writer");
        JsonNode asReader = ok(call("POST", itemPath(rep) + "/tests", ann(), Map.of("role", "reader")));
        assertThat(asReader.path("errors").asInt()).isEqualTo(3);
        assertThat(asReader.path("results").get(0).path("message").asText()).contains("does not hold the role 'reader'");
        JsonNode steward = ok(call("POST", itemPath(rep) + "/tests", sue(), Map.of("role", "reader")));
        assertThat(steward.path("errors").asInt()).as("a steward may run as any role").isZero();
        // a report with a test of a visual it does not have is invalid
        JsonNode bad = ok(call("POST", "/api/v1/bi/reports/validate", ann(), Map.of("yaml", report.replace("visual: n,", "visual: ghost,"))));
        assertThat(bad.path("valid").asBoolean()).isFalse();
        assertThat(bad.path("problems").toString()).contains("tests[0].visual");
    }

    @Test @Order(5)
    void aReaderMayNotRunThemAndTheyAreAuditedAndTheReportKeepsThemThroughTheDesignersWriter() throws Exception {
        call("POST", itemPath(rep) + "/tests", cat(), Map.of()).andExpect(status().is4xxClientError());
        JsonNode f = ok(call("POST", "/api/v1/bi/reports/format", ann(), Map.of("report", ok(call("POST", "/api/v1/bi/reports/validate", ann(),
                Map.of("yaml", "report: r\ndataset: " + ds + "\npages:\n  - id: main\n    title: Main\n    visuals:\n      - { id: n, type: card, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [N] } }\ntests:\n  - { name: x, visual: n, expect: 7, tolerance: 1% }\n"))).path("report"))));
        assertThat(f.path("yaml").asText()).contains("tests:").contains("visual: \"n\"").contains("tolerance: \"1%\"");
        JsonNode events = ok(call("GET", "/api/v1/admin/audit?limit=200", admin(), null));
        assertThat(events.toString()).contains("bi-tests-run");
    }
}
