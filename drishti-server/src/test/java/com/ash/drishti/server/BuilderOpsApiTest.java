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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Build workbench step 5: {@code /builder/edit} and {@code /builder/check} (stateless), and a Design's {@code /ops},
 * {@code /undo}, {@code /redo} and {@code /check} (owner only, 409 DRS-5007 on a stale revision).
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.designer.kinds[0]=trade", "drishti.security.roles.designer.kinds[1]=netting-set",
        "drishti.identity.database-url=jdbc:sqlite:target/ops-api-${random.uuid}/identity.db",
        "drishti.builder.designs.dir=target/ops-api-files-${random.uuid}", "drishti.builder.designs.max-ops=3"})
@AutoConfigureMockMvc
class BuilderOpsApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUTRA = """
            rachana: 1
            sutra: ops-check
            version: 1
            match: { kind: trade, priority: 100 }
            title: { pill: T, id: $.tradeId }
            panels:
              # the terms
              - id: terms
                kind: kv
                columns:
                  - { label: Coupon, bind: $.terms.coupon }
              - id: legs
                kind: table
                rows: $.legs
                columns:
                  - { label: Notional, bind: "@.notional" }
            """;

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private ResultActions send(String url, String who, String body) throws Exception {
        var b = post(java.net.URI.create(url)).header("Authorization", who);
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(b);
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return JSON.readTree(r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    private static String json(Object... pairs) throws Exception {
        var o = JSON.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            o.set((String) pairs[i], pairs[i + 1] instanceof JsonNode n ? n : JSON.valueToTree(pairs[i + 1]));
        }
        return JSON.writeValueAsString(o);
    }

    private static JsonNode tree(String s) throws Exception {
        return JSON.readTree(s);
    }

    @Test
    void editIsStatelessAndReportsLocatedProblems() throws Exception {
        String who = as("ed", "designer");
        JsonNode r = ok(send("/api/v1/builder/edit", who, json("yaml", SUTRA, "ops", tree("""
                [{"op":"setOption","panel":"legs","option":"title","value":"Legs"},
                 {"op":"remove","panel":"ghost"},
                 {"op":"addPanel","kind":"gauge","options":{"value":"$.score"}}]"""))));
        assertThat(r.path("applied").asInt()).isEqualTo(2);
        assertThat(r.path("problems")).hasSize(1);
        assertThat(r.path("problems").get(0).path("op").asInt()).isEqualTo(1);
        assertThat(r.path("problems").get(0).path("code").asText()).isEqualTo("DRS-5021");
        assertThat(r.path("yaml").asText()).contains("# the terms", "title: Legs", "kind: gauge");
        send("/api/v1/builder/edit", who, json("yaml", SUTRA, "ops", tree("[{\"op\":\"remove\"},{\"op\":\"zap\"}]")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-5001"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("operation 0")));
        send("/api/v1/builder/edit", who, json("ops", tree("[]"))).andExpect(status().isBadRequest());
    }

    @Test
    void checkAnswersAPanelBySampleMatrixWithEmptyCellsAndNoAccess() throws Exception {
        String rita = as("rita2", "risk");
        JsonNode m = ok(send("/api/v1/builder/check", rita, json("yaml", SUTRA, "kind", "trade", "samples", tree("""
                [{"name":"full","document":{"tradeId":"T1","terms":{"coupon":0.05},"legs":[{"notional":100}]}},
                 {"name":"nolegs","document":{"tradeId":"T2","terms":{"coupon":0.05}}},
                 {"name":"curve","ref":{"kind":"curve","id":"USD-SOFR"}}]"""))));
        assertThat(m.path("samples")).hasSize(3);
        JsonNode legs = panel(m, "legs");
        assertThat(legs.path("cells").get(0).path("status").asText()).isEqualTo("ok");
        assertThat(legs.path("cells").get(1).path("status").asText()).isEqualTo("empty");
        assertThat(legs.path("counts").path("empty").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(m.path("counts").has("noAccess")).isTrue();
        // a role that may not open curves: the stored-entity sample is noAccess in every panel, the pasted ones still check
        String dee = as("dee2", "designer");
        JsonNode denied = ok(send("/api/v1/builder/check", dee, json("yaml", SUTRA, "kind", "trade", "samples", tree("""
                [{"name":"full","document":{"tradeId":"T1","terms":{"coupon":0.05},"legs":[{"notional":100}]}},
                 {"name":"curve","ref":{"kind":"curve","id":"USD-SOFR"}}]"""))));
        assertThat(panel(denied, "legs").path("cells").get(1).path("status").asText()).isEqualTo("noAccess");
        assertThat(panel(denied, "terms").path("cells").get(1).path("status").asText()).isEqualTo("noAccess");
        assertThat(panel(denied, "legs").path("cells").get(0).path("status").asText()).isEqualTo("ok");
        assertThat(denied.path("ok").asBoolean()).isFalse();
        send("/api/v1/builder/check", dee, json("yaml", SUTRA, "samples", tree("[]"))).andExpect(status().isBadRequest());
        send("/api/v1/builder/check", dee, json("yaml", "rachana: 1\nsutra: [", "samples", tree("[{\"name\":\"a\",\"document\":{}}]")))
                .andExpect(status().is4xxClientError());
    }

    private static JsonNode panel(JsonNode matrix, String id) {
        for (JsonNode p : matrix.path("panels")) {
            if (id.equals(p.path("id").asText())) {
                return p;
            }
        }
        throw new AssertionError("no panel " + id + " in " + matrix);
    }

    @Test
    void aDesignKeepsItsOperationsAndMovesAlongThemWithUndoAndRedo() throws Exception {
        String ann = as("ann5", "author");
        String id = ok(send("/api/v1/builder/designs", ann, json("name", "Ops", "kind", "trade", "sutra", SUTRA))).path("id").asText();
        String base = "/api/v1/builder/designs/" + id;
        ok(send(base + "/samples", ann, json("samples", tree("[{\"name\":\"s\",\"document\":{\"tradeId\":\"T1\",\"terms\":{\"coupon\":0.05},\"legs\":[{\"notional\":100}]}}]"))));
        int rev = ok(mvc.perform(get(base).header("Authorization", ann))).path("rev").asInt();
        assertThat(rev).isEqualTo(1);

        JsonNode a = ok(send(base + "/ops", ann, json("baseRev", rev, "ops", tree("[{\"op\":\"setOption\",\"panel\":\"legs\",\"option\":\"title\",\"value\":\"Legs\"}]"))));
        assertThat(a.path("rev").asInt()).isEqualTo(2);
        assertThat(a.path("yaml").asText()).contains("title: Legs", "# the terms");
        assertThat(a.path("problems")).isEmpty();
        assertThat(a.path("preview").path("panels")).hasSize(2);

        JsonNode b = ok(send(base + "/ops", ann, json("baseRev", 2, "ops", tree("[{\"op\":\"move\",\"panel\":\"legs\",\"area\":\"right\",\"span\":6},{\"op\":\"remove\",\"panel\":\"ghost\"}]"))));
        assertThat(b.path("rev").asInt()).isEqualTo(3);
        assertThat(b.path("applied").asInt()).isEqualTo(1);
        assertThat(b.path("problems").get(0).path("op").asInt()).isEqualTo(1);

        // a stale revision is a 409 that changes nothing
        send(base + "/ops", ann, json("baseRev", 1, "ops", tree("[{\"op\":\"remove\",\"panel\":\"legs\"}]"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRS-5007"));
        send(base + "/ops", ann, json("ops", tree("[]"))).andExpect(status().isBadRequest());
        assertThat(ok(mvc.perform(get(base).header("Authorization", ann))).path("sutra").asText()).contains("area: right");

        JsonNode u = ok(send(base + "/undo", ann, null));
        assertThat(u.path("rev").asInt()).isEqualTo(4);
        assertThat(u.path("yaml").asText()).contains("title: Legs").doesNotContain("area: right");
        JsonNode u2 = ok(send(base + "/undo", ann, json("baseRev", 4)));
        assertThat(u2.path("yaml").asText()).doesNotContain("title: Legs");
        send(base + "/undo", ann, null).andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("nothing to undo")));
        JsonNode r = ok(send(base + "/redo", ann, null));
        assertThat(r.path("yaml").asText()).contains("title: Legs").doesNotContain("area: right");
        ok(send(base + "/redo", ann, null));
        send(base + "/redo", ann, null).andExpect(status().isConflict());
        // an edit after an undo drops what redo would have brought back
        ok(send(base + "/undo", ann, null));
        ok(send(base + "/ops", ann, json("baseRev", 8, "ops", tree("[{\"op\":\"setOption\",\"panel\":\"terms\",\"option\":\"title\",\"value\":\"Terms\"}]"))));
        send(base + "/redo", ann, null).andExpect(status().isConflict());
        // the log keeps at most max-ops (3) steps
        for (int i = 0; i < 4; i++) {
            int now = ok(mvc.perform(get(base).header("Authorization", ann))).path("rev").asInt();
            ok(send(base + "/ops", ann, json("baseRev", now, "ops", tree("[{\"op\":\"setOption\",\"panel\":\"terms\",\"option\":\"code\",\"value\":\"C" + i + "\"}]"))));
        }
        assertThat(ok(mvc.perform(get(base).header("Authorization", ann))).path("ops")).hasSize(3);

        JsonNode m = ok(send(base + "/check", ann, null));
        assertThat(m.path("ok").asBoolean()).isTrue();
        assertThat(panel(m, "legs").path("cells").get(0).path("status").asText()).isEqualTo("ok");
        assertThat(ok(mvc.perform(get(base).header("Authorization", ann))).path("status").asText()).isEqualTo("checked");
        ok(send(base + "/ops", ann, json("baseRev", m.path("rev").asInt(), "ops", tree("[{\"op\":\"setOption\",\"panel\":\"terms\",\"option\":\"code\",\"value\":\"Z\"}]"))));
        assertThat(ok(mvc.perform(get(base).header("Authorization", ann))).path("status").asText()).isEqualTo("draft");
    }

    @Test
    void onlyTheOwnerReachesADesignsOperationsUndoRedoAndCheck() throws Exception {
        String ann = as("ann6", "author"), bob = as("bob6", "author");
        String id = ok(send("/api/v1/builder/designs", ann, json("kind", "trade", "sutra", SUTRA))).path("id").asText();
        String base = "/api/v1/builder/designs/" + id;
        for (String verb : new String[] {"/ops", "/undo", "/redo", "/check"}) {
            send(base + verb, bob, json("baseRev", 1, "ops", tree("[]"))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-5006"));
        }
    }
}
