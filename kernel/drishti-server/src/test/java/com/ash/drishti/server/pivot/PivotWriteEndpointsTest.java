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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The write endpoints of /api/v1/pivot that had no test: a saved view deleted (and its comments reopened and deleted), compare against
 * another date, the rollups an administrator defines, refreshes and removes, a custom function submitted for approval, and a panel
 * pivot promoted to its Sutra. Each: a valid request with its effect read back, and a refusal that carries a DRS code.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.trades-only.kinds[0]=trade",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PivotWriteEndpointsTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();
    static final String RID = "wt-by-product";

    private MvcResult call(String user, String role, MockHttpServletRequestBuilder r, Object body) throws Exception {
        r = r.header("Authorization", "Bearer " + tokens.mint(user, List.of(role), 300));
        if (body != null) {
            r = r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(r).andReturn();
    }

    private JsonNode ok(String user, String role, MockHttpServletRequestBuilder r, Object body) throws Exception {
        MvcResult res = call(user, role, r, body);
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isBetween(200, 299);
        String text = res.getResponse().getContentAsString();
        return text.isBlank() ? json.createObjectNode() : json.readTree(text);
    }

    /** A refusal: this status and a problem body carrying this DRS code. */
    private void refused(String user, String role, MockHttpServletRequestBuilder r, Object body, int status, String code) throws Exception {
        MvcResult res = call(user, role, r, body);
        String text = res.getResponse().getContentAsString();
        assertThat(res.getResponse().getStatus()).as(text).isEqualTo(status);
        assertThat(text).as("the refusal names its code").contains(code);
    }

    static Map<String, Object> view() {
        return Map.of("source", "trade", "rows", List.of("productType"), "values", List.of(Map.of("measure", "count()", "as", "N")));
    }

    @Test @Order(1)
    void aSavedViewIsDeletedByItsOwnerOnlyAndItsCommentsAreReopenedThenDeleted() throws Exception {
        ok("u-ann", "admin", post("/api/v1/pivot/views"), Map.of("name", "by-product", "view", view(), "sharedWith", List.of("role:trades-only")));
        String base = "/api/v1/pivot/views/u-ann/by-product/comments";
        String cid = ok("u-rita", "trades-only", post(base), Map.of("coords", Map.of("productType", "IRS"), "measure", "N", "text", "checked with the desk")).path("id").asText();
        ok("u-rita", "trades-only", post(base + "/" + cid + "/resolve"), null);
        assertThat(ok("u-rita", "trades-only", get(base).param("open", "true"), null).path("comments")).isEmpty();
        // reopen: the comment is open again (read back), and reopening twice leaves it open
        ok("u-rita", "trades-only", post("/api/v1/pivot/views/u-ann/by-product/comments/" + cid + "/reopen"), null);
        ok("u-rita", "trades-only", post("/api/v1/pivot/views/u-ann/by-product/comments/" + cid + "/reopen"), null);
        assertThat(ok("u-rita", "trades-only", get(base).param("open", "true"), null).path("comments")).hasSize(1);
        refused("u-rita", "trades-only", post("/api/v1/pivot/views/u-ann/by-product/comments/no-such/reopen"), null, 404, "DRS-");
        // delete: only the author or the view's owner; gone from the list; twice is a clean refusal
        refused("u-tom", "trades-only", delete("/api/v1/pivot/views/u-ann/by-product/comments/" + cid), null, 400, "DRS-");
        assertThat(ok("u-rita", "trades-only", delete("/api/v1/pivot/views/u-ann/by-product/comments/" + cid), null).path("deleted").asText()).isEqualTo(cid);
        assertThat(ok("u-rita", "trades-only", get(base), null).path("comments")).isEmpty();
        refused("u-rita", "trades-only", delete("/api/v1/pivot/views/u-ann/by-product/comments/" + cid), null, 404, "DRS-");
        // the view: a person without it cannot delete it, its owner can, and it is gone
        refused("u-rita", "trades-only", delete("/api/v1/pivot/views/by-product"), null, 404, "DRS-5704");
        assertThat(ok("u-ann", "admin", get("/api/v1/pivot/views/u-ann/by-product"), null).path("name").asText()).isEqualTo("by-product");
        assertThat(call("u-ann", "admin", delete("/api/v1/pivot/views/by-product"), null).getResponse().getStatus()).isEqualTo(204);
        refused("u-ann", "admin", get("/api/v1/pivot/views/u-ann/by-product"), null, 404, "DRS-5704");
        assertThat(ok("u-ann", "admin", get("/api/v1/pivot/views"), null).path("views").toString()).doesNotContain("by-product");
        refused("u-ann", "admin", delete("/api/v1/pivot/views/by-product"), null, 404, "DRS-5704");
    }

    @Test @Order(2)
    void compareAnswersTheViewOnTwoDatesAndNeedsTheOtherDate() throws Exception {
        JsonNode a = ok("u-rita", "trades-only", post("/api/v1/pivot/query"), Map.of("view", view()));
        String date = a.path("date").asText();
        assertThat(date).isNotBlank();
        JsonNode c = ok("u-rita", "trades-only", post("/api/v1/pivot/compare"), Map.of("view", view(), "otherDate", date));
        assertThat(c.toString()).as("the comparison answers the cells of both dates").contains(date);
        assertThat(ok("u-rita", "trades-only", post("/api/v1/pivot/compare"), Map.of("view", view(), "otherDate", date))).isEqualTo(c);    // repeated: the same
        refused("u-rita", "trades-only", post("/api/v1/pivot/compare"), Map.of("view", view()), 422, "DRS-");
        refused("u-rita", "trades-only", post("/api/v1/pivot/compare"), Map.of("view", Map.of("source", "no-such-kind"), "otherDate", date), 422, "DRS-");
    }

    @Test @Order(3)
    void anAdministratorDefinesRefreshesAndRemovesARollup() throws Exception {
        Map<String, Object> def = Map.of("id", "wt-by-product", "source", "trade", "grain", List.of("productType"), "functions", List.of("count()"), "title", "Trades by product");
        JsonNode made = ok("u-ann", "admin", post("/api/v1/pivot/rollups"), def);
        assertThat(made.path("id").asText()).isEqualTo("wt-by-product");
        assertThat(made.path("refresh").asText()).startsWith("queued for");
        assertThat(ok("u-ann", "admin", get("/api/v1/pivot/rollups"), null).path("definitions").toString()).contains("wt-by-product");
        JsonNode refreshed = ok("u-ann", "admin", post("/api/v1/pivot/rollups/" + RID + "/refresh"), null);
        assertThat(refreshed.path("rollup").asText()).isEqualTo("wt-by-product");
        assertThat(refreshed.path("words").asText()).isNotBlank();
        ok("u-ann", "admin", post("/api/v1/pivot/rollups/" + RID + "/refresh"), null);          // again: the same rollup, refreshed again
        refused("u-rita", "trades-only", post("/api/v1/pivot/rollups"), def, 403, "DRS-");
        refused("u-ann", "admin", post("/api/v1/pivot/rollups"), Map.of("id", "bad id!", "source", "trade", "functions", List.of("count()")), 422, "DRS-");
        refused("u-ann", "admin", post("/api/v1/pivot/rollups"), Map.of("id", "empty", "source", "trade", "grain", List.of("productType")), 422, "DRS-");
        refused("u-rita", "trades-only", post("/api/v1/pivot/rollups/" + RID + "/refresh"), null, 403, "DRS-");
        refused("u-ann", "admin", post("/api/v1/pivot/rollups/" + "no-such" + "/refresh"), null, 404, "DRS-5704");
        refused("u-rita", "trades-only", delete("/api/v1/pivot/rollups/" + RID), null, 403, "DRS-");
        assertThat(ok("u-ann", "admin", delete("/api/v1/pivot/rollups/" + RID), null).path("removed").asText()).isEqualTo("wt-by-product");
        assertThat(ok("u-ann", "admin", get("/api/v1/pivot/rollups"), null).path("definitions").toString()).doesNotContain("wt-by-product");
        refused("u-ann", "admin", delete("/api/v1/pivot/rollups/" + RID), null, 404, "DRS-5704");
    }

    @Test @Order(4)
    void aCustomFunctionIsSubmittedForApprovalByItsOwner() throws Exception {
        ok("asha", "analyst", post("/api/v1/pivot/functions"), PivotFunctionsApiTest.definition());
        JsonNode sub = ok("asha", "analyst", post("/api/v1/pivot/functions/asha/wret@1/submit"), Map.of("note", "for the desk"));
        assertThat(sub.toString()).contains("wret");
        refused("asha", "analyst", post("/api/v1/pivot/functions/asha/wret/submit"), null, 422, "DRS-");
        refused("raj", "analyst", post("/api/v1/pivot/functions/asha/wret@1/submit"), null, 404, "DRS-");
        refused("asha", "analyst", post("/api/v1/pivot/functions/asha/nope@1/submit"), null, 404, "DRS-");
    }

    @Test @Order(5)
    void promotingAPivotNeedsAnAuthorAPanelSourceAndAnExistingSutra() throws Exception {
        Map<String, Object> panelView = Map.of("view", Map.of("source", "panel/trades/no-panel/x", "rows", List.of("productType")), "sutra", "no-such-sutra");
        refused("u-rita", "trades-only", post("/api/v1/pivot/promote"), panelView, 403, "DRS-5002");
        refused("u-ann", "admin", post("/api/v1/pivot/promote"), Map.of("view", view(), "sutra", "x"), 422, "DRS-");        // not a panel's pivot
        refused("u-ann", "admin", post("/api/v1/pivot/promote"), panelView, 404, "DRS-2003");
    }
}
