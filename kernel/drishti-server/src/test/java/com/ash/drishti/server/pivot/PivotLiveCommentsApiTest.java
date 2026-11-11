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

import com.ash.drishti.bicompute.live.LivePivots;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Live pivots, the screen's switches and cell comments over /api/v1/pivot on the demo trades (BI_GUIDE.md, live pivots, the screen's
 * switches, cell comments): a live view answers exactly what a plain query answers and says it is exact; its stream opens with the
 * answer; a data-load signal of its date computes it again and a load of another kind does not; it is its person's only; the per-person
 * limit is refused with DRS-5721; {@code exact}, {@code rollups} and {@code progressive} come back in {@code computedAs.asked}; a comment
 * on a cell of a saved view is kept with its versions (the version list is unchanged by it), marked older after the view is saved again,
 * resolved and deleted only by its author or the view's owner, refused in words when empty or off the view, and audited without its text.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.trades-only.kinds[0]=trade", "drishti.pivot.live.max-per-person=2", "drishti.pivot.live.min-interval-ms=0",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class PivotLiveCommentsApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired AuditLog audit;
    @Autowired PivotLiveConfiguration.Live live;
    final ObjectMapper json = new ObjectMapper();

    private MockHttpServletRequestBuilder as(String user, String role, MockHttpServletRequestBuilder r) {
        return r.header("Authorization", "Bearer " + tokens.mint(user, List.of(role), 300));
    }

    private MvcResult call(String user, String role, MockHttpServletRequestBuilder r, Object body, int expect) throws Exception {
        if (body != null) {
            r = r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult res = mvc.perform(as(user, role, r)).andReturn();
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(expect);
        return res;
    }

    private JsonNode ok(String user, String role, MockHttpServletRequestBuilder r, Object body) throws Exception {
        return json.readTree(call(user, role, r, body, 200).getResponse().getContentAsString());
    }

    static Map<String, Object> view() {
        return Map.of("source", "trade", "rows", List.of("productType"), "values", List.of(Map.of("measure", "count()", "as", "N")));
    }

    @Test
    void aLiveViewAnswersAsAQueryStreamsItsAnswerMovesOnALoadOfItsKindAndIsItsPersonsOnly() throws Exception {
        JsonNode plain = ok("u-rita", "trades-only", post("/api/v1/pivot/query"), Map.of("view", view()));
        JsonNode started = ok("u-rita", "trades-only", post("/api/v1/pivot/live"), Map.of("view", view()));
        String id = started.path("id").asText();
        assertThat(id).startsWith("lp-");
        assertThat(started.path("answer").path("cells")).isEqualTo(plain.path("cells"));
        assertThat(started.path("options").path("exact").asBoolean()).as("a live view is exact unless ≈ was asked").isTrue();
        assertThat(started.path("answer").path("computedAs").path("words").asText()).isEqualTo("exact");
        assertThat(ok("u-rita", "trades-only", get("/api/v1/pivot/live"), null).path("live").toString()).contains(id);
        MvcResult stream = call("u-rita", "trades-only", get("/api/v1/pivot/live/" + id + "/events"), null, 200);
        assertThat(stream.getRequest().isAsyncStarted()).isTrue();
        assertThat(stream.getResponse().getContentAsString()).contains("event:answer").contains(id);
        // a load of its kind for its date touches it; another kind does not
        String date = started.path("answer").path("date").asText();
        assertThat(live.pivots().signal(LivePivots.Signal.load("trade", date))).isEqualTo(1);
        assertThat(live.pivots().signal(LivePivots.Signal.load("position", date))).isZero();
        LivePivots.Event e = live.pivots().recomputeNow("u-rita", id);
        assertThat(e.name()).as("nothing moved in the demo data: a status, no patch").isEqualTo("status");
        // another person neither streams nor ends it
        call("u-tom", "trades-only", get("/api/v1/pivot/live/" + id + "/events"), null, 404);
        call("u-tom", "trades-only", delete("/api/v1/pivot/live/" + id), null, 404);
        // the per-person limit (2 here)
        ok("u-rita", "trades-only", post("/api/v1/pivot/live"), Map.of("view", view(), "exact", false));
        JsonNode refused = json.readTree(call("u-rita", "trades-only", post("/api/v1/pivot/live"), Map.of("view", view()), 422).getResponse()
                .getContentAsString());
        assertThat(refused.toString()).contains("DRS-5721").contains("max-per-person");
        call("u-rita", "trades-only", delete("/api/v1/pivot/live/" + id), null, 204);
        call("u-rita", "trades-only", delete("/api/v1/pivot/live/" + id), null, 404);
        assertThat(audit.recent(50, "trade")).anySatisfy(a -> assertThat(a.action()).isEqualTo("pivot.live.start"));
    }

    @Test
    void theSwitchesComeBackAsAskedInComputedAs() throws Exception {
        JsonNode a = ok("u-rita", "trades-only", post("/api/v1/pivot/query"), Map.of("view", view(), "exact", true, "rollups", false));
        assertThat(a.path("computedAs").path("asked").path("exact").asText()).isEqualTo("exact");
        assertThat(a.path("computedAs").path("asked").path("rollups").asBoolean()).isFalse();
        assertThat(a.path("computedAs").path("words").asText()).contains("rollups off");
        JsonNode b = ok("u-rita", "trades-only", post("/api/v1/pivot/query"), Map.of("view", view(), "exact", "≈", "progressive", false));
        assertThat(b.path("computedAs").path("asked").path("exact").asText()).isEqualTo("approximate");
        assertThat(b.path("computedAs").path("asked").path("progressive").asBoolean()).isFalse();
        assertThat(b.path("cells")).as("a count is exact either way").isEqualTo(a.path("cells"));
    }

    @Test
    void aCommentIsKeptWithTheViewsVersionsAndOnlyItsAuthorOrTheOwnerChangesIt() throws Exception {
        ok("u-ann", "admin", post("/api/v1/pivot/views"), Map.of("name", "by-product", "view", view(), "sharedWith", List.of("role:trades-only")));
        String base = "/api/v1/pivot/views/u-ann/by-product/comments";
        JsonNode c = ok("u-rita", "trades-only", post(base), Map.of("coords", Map.of("productType", "IRS"), "measure", "N",
                "text", "IRS count jumped after the 10-30 load: checked with the desk"));
        String cid = c.path("id").asText();
        assertThat(c.path("by").asText()).isEqualTo("u-rita");
        assertThat(c.path("version").asInt()).isEqualTo(1);
        assertThat(c.path("cell").asText()).isEqualTo("productType=IRS");
        JsonNode listed = ok("u-ann", "admin", get(base), null);
        assertThat(listed.path("comments")).hasSize(1);
        assertThat(listed.path("comments").get(0).path("older").asBoolean()).isFalse();
        assertThat(listed.path("open").asInt()).isEqualTo(1);
        // saved again: the comment stays, now on an older version; the version list does not show the comments
        ok("u-ann", "admin", post("/api/v1/pivot/views"), Map.of("name", "by-product", "view", view(), "sharedWith", List.of("role:trades-only")));
        assertThat(ok("u-rita", "trades-only", get(base), null).path("comments").get(0).path("older").asBoolean()).isTrue();
        assertThat(ok("u-ann", "admin", get("/api/v1/pivot/views/u-ann/by-product/versions"), null).path("versions")).hasSize(2);
        // refused in words
        call("u-rita", "trades-only", post(base), Map.of("coords", Map.of("productType", "IRS"), "text", "  "), 400);
        call("u-rita", "trades-only", post(base), Map.of("coords", Map.of("counterparty", "X"), "text", "off the view"), 400);
        call("u-rita", "trades-only", post(base), Map.of("coords", Map.of("productType", "IRS"), "measure", "Nope", "text", "x"), 400);
        call("u-nobody", "nobody", get(base), null, 404);
        // only the author or the owner resolves and deletes
        ok("u-tom", "trades-only", post(base), Map.of("coords", Map.of(), "text", "grand total looks right"));
        call("u-tom", "trades-only", post(base + "/" + cid + "/resolve"), null, 400);
        assertThat(ok("u-rita", "trades-only", post(base + "/" + cid + "/resolve"), null).path("resolved").path("by").asText()).isEqualTo("u-rita");
        assertThat(ok("u-rita", "trades-only", get(base).param("open", "true"), null).path("comments")).hasSize(1);
        ok("u-ann", "admin", post(base + "/" + cid + "/reopen"), null);
        ok("u-ann", "admin", delete(base + "/" + cid), null);
        assertThat(ok("u-ann", "admin", get(base), null).path("comments")).hasSize(1);
        assertThat(audit.recent(50, "u-ann/by-product")).anySatisfy(a -> {
            assertThat(a.action()).isEqualTo("pivot.comment.add");
            assertThat(a.detail()).contains("cell=productType=IRS").doesNotContain("jumped");
        });
    }
}
