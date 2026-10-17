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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
 * Python items over the whole stack, security on (RUPAKA.md section 20.8): a Python item is a BI item (versions, review, publish), publishing checks its manifest
 * (DRS-9070), a viewer reads what was published and an editor the draft, the shipped Python items and the sample python report resolve for the console's runner.
 *
 * <p>Users: ann writes (bi-author), cat reads (bi), dan has no BI power.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/bi-py-${random.uuid}/identity.db", "drishti.packs.overlay=target/bi-py-overlay/added.yaml",
        "drishti.bi.dir=target/bi-py-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=target/bi-py-store/${random.uuid}"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PythonBiApiTest {

    static final Path CONFIG = Path.of("..", "config", "bi");
    static final Path FOLDER = Path.of("target", "bi-py-folder");

    static {
        try {
            for (String f : List.of("datasets/trades-basic-trade.dataset.yaml", "models/trades-basic.model.yaml", "python/desk-pnl-volatility.python.yaml", "python/pnl-histogram.python.yaml",
                    "reports/python-desk-risk.report.yaml")) {
                Path to = FOLDER.resolve(f);
                Files.createDirectories(to.getParent());
                Files.writeString(to, Files.readString(CONFIG.resolve(f)).replace("sample: true", "sample: false"));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static final String BODY = "python: vol\ntitle: Vol\ninterface: measure\ninputs: [pnl1d]\npackages: [pandas]\ntarget: server\nsource: |\n  def compute(table, params):\n      return 1.0\n";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();
    String ws;
    String item;

    String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    ResultActions call(String method, String path, String who, Object body) throws Exception {
        var req = switch (method) {
            case "POST" -> post(java.net.URI.create(path));
            case "PUT" -> put(java.net.URI.create(path));
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
        String admin = as("drishti-dev-admin", "admin");
        for (String[] r : new String[][] {{"writer", "bi,bi-author"}, {"reader", "bi"}, {"nobi", ""}}) {
            mvc.perform(put("/api/v1/admin/role-definitions/" + r[0]).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("kinds", List.of("*"), "bi", r[1].isEmpty() ? List.of() : List.of(r[1].split(",")))))).andExpect(status().isOk());
        }
        for (String u : List.of("ann:writer", "cat:reader", "dan:nobi")) {
            String[] n = u.split(":");
            mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", n[0], "displayName", n[0], "email", n[0] + "@desk.test", "roles", List.of(n[1]), "packs", List.of("finance"),
                            "password", "long-enough-pass-1")))).andExpect(status().isCreated());
        }
        ws = ok(call("POST", "/api/v1/bi/workspaces", as("ann", "writer"), Map.of("name", "Py Desk"))).path("id").asText();
        call("PUT", "/api/v1/bi/workspaces/" + ws + "/members/cat", as("ann", "writer"), Map.of("role", "viewer")).andExpect(status().isOk());
    }

    @Test
    @Order(1)
    void pythonIsAKindOfBiItemAndItsManifestIsCheckedOnPublish() throws Exception {
        assertThat(ok(call("GET", "/api/v1/bi/me", as("ann", "writer"), null)).path("kinds").toString()).contains("python");
        item = ok(call("POST", "/api/v1/bi/workspaces/" + ws + "/items", as("ann", "writer"), Map.of("kind", "python", "title", "Vol", "description", "volatility"))).path("id").asText();
        call("PUT", "/api/v1/bi/items/" + item, as("ann", "writer"), Map.of("body", "python: vol\ninterface: transform\nsource: pass\n")).andExpect(status().isOk());
        refused(call("POST", "/api/v1/bi/items/" + item + "/publish", as("ann", "writer"), Map.of()), 422, "DRS-9070");
        call("PUT", "/api/v1/bi/items/" + item, as("ann", "writer"), Map.of("body", BODY)).andExpect(status().isOk());
        JsonNode published = ok(call("POST", "/api/v1/bi/items/" + item + "/publish", as("ann", "writer"), Map.of("note", "first")));
        assertThat(published.path("publishedVersion").asInt()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void aViewerReadsWhatWasPublishedAndAnEditorTheDraft() throws Exception {
        call("PUT", "/api/v1/bi/items/" + item, as("ann", "writer"), Map.of("body", BODY.replace("return 1.0", "return 2.0"))).andExpect(status().isOk());
        JsonNode viewer = ok(call("GET", "/api/v1/bi/python/" + item, as("cat", "reader"), null));
        assertThat(viewer.path("version").asInt()).isEqualTo(1);
        assertThat(viewer.path("draft").asBoolean()).isFalse();
        assertThat(viewer.path("manifest").path("source").asText()).contains("return 1.0");
        JsonNode editor = ok(call("GET", "/api/v1/bi/python/" + item, as("ann", "writer"), null));
        assertThat(editor.path("draft").asBoolean()).isTrue();
        assertThat(editor.path("manifest").path("source").asText()).contains("return 2.0");
        call("GET", "/api/v1/bi/python/" + item, as("dan", "nobi"), null).andExpect(status().isForbidden());
        refused(call("GET", "/api/v1/bi/python/nope-" + item, as("ann", "writer"), null), 404, "DRS-9001");
    }

    @Test
    @Order(3)
    void validateListsEveryProblemAndDoesNotNeedAnItem() throws Exception {
        JsonNode good = ok(call("POST", "/api/v1/bi/python/validate", as("cat", "reader"), Map.of("yaml", BODY)));
        assertThat(good.path("valid").asBoolean()).isTrue();
        assertThat(good.path("manifest").path("inputs").toString()).contains("pnl1d");
        JsonNode bad = ok(call("POST", "/api/v1/bi/python/validate", as("cat", "reader"), Map.of("yaml", BODY.replace("pandas", "requests").replace("measure", "chart"))));
        assertThat(bad.path("valid").asBoolean()).isFalse();
        assertThat(bad.path("problems").toString()).contains("DRS-9070").contains("allowed package").contains("interface");
        assertThat(bad.path("packages").toString()).contains("pandas");
        call("POST", "/api/v1/bi/python/validate", as("dan", "nobi"), Map.of("yaml", BODY)).andExpect(status().isForbidden());
    }

    @Test
    @Order(4)
    void theShippedItemsAndTheSampleReportResolveForTheRunner() throws Exception {
        JsonNode shipped = ok(call("GET", "/api/v1/bi/python/desk-pnl-volatility", as("cat", "reader"), null));
        assertThat(shipped.path("id").asText()).isEqualTo("file:python:desk-pnl-volatility");
        assertThat(shipped.path("manifest").path("interface").asText()).isEqualTo("measure");
        JsonNode measure = ok(call("POST", "/api/v1/bi/reports/file:report:python-desk-risk/python", as("cat", "reader"), Map.of("page", "risk", "visual", "volatility")));
        assertThat(measure.path("id").asText()).isEqualTo("file:python:desk-pnl-volatility");
        assertThat(measure.path("measure").path("name").asText()).isEqualTo("Desk P&L volatility");
        assertThat(measure.path("measure").path("by").toString()).contains("trade.DESK");
        assertThat(measure.path("measure").path("inputs").toString()).contains("trade.PNL_1D");
        assertThat(measure.path("run").asText()).isEqualTo("auto");
        JsonNode visual = ok(call("POST", "/api/v1/bi/reports/file:report:python-desk-risk/python", as("cat", "reader"), Map.of("page", "risk", "visual", "histogram")));
        assertThat(visual.path("id").asText()).isEqualTo("file:python:pnl-histogram");
        assertThat(visual.path("measure").isNull()).isTrue();
        assertThat(visual.path("params").path("bins").asInt()).isEqualTo(24);
        assertThat(visual.path("manifest").path("source").asText()).contains("np.histogram");
        refused(call("POST", "/api/v1/bi/reports/file:report:python-desk-risk/python", as("cat", "reader"), Map.of("page", "risk", "visual", "nope")), 422, "DRS-9035");
        call("POST", "/api/v1/bi/reports/file:report:python-desk-risk/python", as("dan", "nobi"), Map.of("page", "risk", "visual", "volatility")).andExpect(status().isForbidden());
    }
}
