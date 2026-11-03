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
package com.ash.drishti.server.pivot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * /api/v1/pivot/functions, /explain and /whatif on the demo trades (PIVOT_GUIDE.md, custom functions, explain, what-if): the playground's
 * harness and states, a function saved only after the harness, private until an approver who is not its author approves it, used in a view
 * with its label, withdrawn in its cells; Python off as shipped; an explanation whose steps add up; a what-if beside the original. Audited.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.pivot-approver.kinds[0]=trade", "drishti.security.roles.analyst.kinds[0]=trade",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class PivotFunctionsApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired AuditLog audit;
    final ObjectMapper json = new ObjectMapper();

    private JsonNode call(String user, String role, MockHttpServletRequestBuilder r, Object body, int expect) throws Exception {
        r = r.header("Authorization", "Bearer " + tokens.mint(user, List.of(role), 300));
        if (body != null) {
            r = r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult res = mvc.perform(r).andReturn();
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(expect);
        return json.readTree(res.getResponse().getContentAsString());
    }

    static Map<String, Object> definition() {
        List<Map<String, Object>> rows = new ArrayList<>();
        java.util.Random r = new java.util.Random(3);
        for (int i = 0; i < 500; i++) {
            rows.add(Map.of("r", r.nextGaussian(), "w", 1 + r.nextInt(9), "g", "G" + (i % 3)));
        }
        return Map.of("name", "wret", "language", "formula", "code", "sum(r * w) / sum(w)", "inputs", List.of("r", "w"),
                "sample", Map.of("rows", rows), "group", "g", "parts", 3);
    }

    private String numberField(String user, String role) throws Exception {
        JsonNode cat = call(user, role, get("/api/v1/pivot/catalogue").param("source", "trade"), null, 200);
        for (JsonNode f : cat.path("fields")) {
            if ("number".equals(f.path("type").asText())) {
                return f.path("name").asText();
            }
        }
        throw new AssertionError("no number field");
    }

    @Test
    void theLifeOfACustomFunction() throws Exception {
        JsonNode t = call("asha", "analyst", post("/api/v1/pivot/functions/test"), definition(), 200);
        assertThat(t.path("harness").path("passed").asBoolean()).as(t.toString()).isTrue();
        assertThat(t.path("playground").path("steps").size()).isGreaterThanOrEqualTo(5);
        assertThat(t.path("playground").path("same").asBoolean()).isTrue();
        assertThat(t.path("playground").path("pivot").path("rows").size()).isEqualTo(3);

        JsonNode saved = call("asha", "analyst", post("/api/v1/pivot/functions"), definition(), 200);
        assertThat(saved.path("ref").asText()).isEqualTo("fn:asha/wret@1");
        call("asha", "analyst", post("/api/v1/pivot/functions/asha/wret@1/approve"), Map.of("scope", "everyone", "reason", "mine"), 403);
        String num = numberField("raj", "analyst");
        Map<String, Object> view = Map.of("source", "trade", "values", List.of(Map.of("measure", "fn:asha/wret@1(" + num + ", " + num + ")")));
        call("raj", "analyst", post("/api/v1/pivot/query"), view, 403);
        call("anna", "pivot-approver", post("/api/v1/pivot/functions/asha/wret@1/approve"), Map.of("scope", "everyone", "reason",
                "reviewed"), 200);
        JsonNode a = call("raj", "analyst", post("/api/v1/pivot/query"), view, 200);
        assertThat(a.toString()).contains("fn:asha/wret@1");
        call("anna", "pivot-approver", post("/api/v1/pivot/functions/asha/wret@1/revoke"), Map.of("reason", "wrong weights"), 200);
        JsonNode w = call("raj", "analyst", post("/api/v1/pivot/query"), view, 200);
        assertThat(w.toString()).contains("function withdrawn by anna");

        Map<String, Object> py = new java.util.LinkedHashMap<>(definition());
        py.put("language", "python");
        py.put("code", "x = 1");
        py.put("entry", "X");
        py.put("kind", "mergeable");
        JsonNode off = call("asha", "analyst", post("/api/v1/pivot/functions/test"), py, 404);
        assertThat(off.toString()).contains("DRS-5713");
        assertThat(audit.recent(50, null).stream().map(AuditLog.Event::action).toList()).contains("pivot.function.save",
                "pivot.function.approve", "pivot.function.revoke", "pivot.function.use");
    }

    @Test
    @SuppressWarnings("unchecked")
    void explainAddsUpAndWhatIfKeepsTheOriginal() throws Exception {
        String num = numberField("asha", "analyst");
        JsonNode cat = call("asha", "analyst", get("/api/v1/pivot/catalogue").param("source", "trade"), null, 200);
        String text = null;
        for (JsonNode f : cat.path("fields")) {
            if ("string".equals(f.path("type").asText()) && !"productType".equals(f.path("name").asText())) {
                text = f.path("name").asText();
                break;
            }
        }
        Map<String, Object> view = Map.of("source", "trade", "rows", List.of("productType"), "values", List.of("sum(" + num + ")"));
        JsonNode q = call("asha", "analyst", post("/api/v1/pivot/query"), view, 200);
        List<String> types = new ArrayList<>();
        q.path("rows").forEach(r -> {
            if (r.path("depth").asInt() == 1 && r.hasNonNull("value") && !"total".equals(r.path("kind").asText())) {
                types.add(r.path("value").asText());
            }
        });
        assertThat(types.size()).isGreaterThanOrEqualTo(2);
        JsonNode e = call("asha", "analyst", post("/api/v1/pivot/explain"), Map.of("view", view, "from", Map.of("productType", types.get(0)),
                "to", Map.of("productType", types.get(1)), "by", text), 200);
        double sum = 0;
        for (JsonNode s : e.path("steps")) {
            sum += s.path("step").asDouble();
        }
        sum += e.path("residual").path("value").asDouble();
        assertThat(sum).isCloseTo(e.path("difference").asDouble(), org.assertj.core.data.Offset.offset(1e-6 * Math.max(1,
                Math.abs(e.path("difference").asDouble()))));

        JsonNode w = call("asha", "analyst", post("/api/v1/pivot/whatif"), Map.of("view", view, "whatIf", Map.of("changes",
                List.of(Map.of("field", num, "scale", 2)))), 200);
        double orig = w.path("original").path("cells").path("*|*").path("values").path("sum(" + num + ")").asDouble();
        double wi = w.path("whatIf").path("cells").path("*|*").path("values").path("sum(" + num + ")").asDouble();
        assertThat(wi).isCloseTo(2 * orig, org.assertj.core.data.Offset.offset(1e-6 * Math.max(1, Math.abs(orig))));
    }
}
