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
package com.ash.drishti.server.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Write-back end to end on the book desk (WRITE_BACK.md W1, W2, W6, W7): a form's draft is submitted (the review sheet first), waits for
 * one approver who is never the submitter, is applied in its lane as an overlay, closes the draft with the change id, shows on the page
 * for every reader (and is named in "How this view was built"), reaches a live viewer of the page, and is told to the triggers and the
 * consoles; a second change based on the old value is conflicted, never applied; a person without the edit power is refused.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=trading,counterparty-risk",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=COUNTERPARTY",
        "drishti.edit.file=../config/edit/edit.yaml", "DRISHTI_EDIT_ENABLED=true",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/edit-${random.uuid}.db",
        "drishti.security.roles.trader.kinds[0]=*", "drishti.security.roles.trader.raw=true", "drishti.security.roles.trader.edit=true",
        "drishti.security.roles.checker.kinds[0]=*", "drishti.security.roles.checker.raw=true", "drishti.security.roles.checker.approve=true",
        "drishti.security.roles.checker.edit=true",
        "drishti.security.roles.reader.kinds[0]=*", "drishti.security.roles.reader.raw=false"})
@AutoConfigureMockMvc
class WriteBackFlowTest {

    static final String DESK = "/api/v1/views/portfolio-trades/BOOK-RATES-2";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired SourceRouter router;
    @Autowired EditFeed feed;
    private final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode call(String method, String url, String auth, Object body, int expect) throws Exception {
        var req = "POST".equals(method) ? post(url) : get(url);
        req = req.header("Authorization", auth);
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        String out = mvc.perform(req).andExpect(status().is(expect)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return out.isBlank() ? json.createObjectNode() : json.readTree(out);
    }

    private JsonNode tradesForm(String auth) throws Exception {
        JsonNode v = json.readTree(mvc.perform(get(DESK).param("sutra", "book-desk").header("Authorization", auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        for (JsonNode p : v.path("panels")) {
            if ("trades".equals(p.path("id").asText())) {
                return p.path("form");
            }
        }
        throw new AssertionError("no trades panel");
    }

    private JsonNode view(String auth) throws Exception {
        return json.readTree(mvc.perform(get(DESK).param("sutra", "book-desk").header("Authorization", auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode waitFor(String id, String state, String auth) throws Exception {
        long until = System.currentTimeMillis() + 15_000;
        JsonNode c;
        do {
            c = call("GET", "/api/v1/changes/" + id, auth, null, 200);
            if (state.equals(c.path("state").asText())) {
                return c;
            }
            Thread.sleep(50);
        } while (System.currentTimeMillis() < until);
        throw new AssertionError(id + " is " + c.path("state").asText() + ", not " + state + ": " + c);
    }

    @Test
    void aFormsDraftIsSubmittedApprovedAppliedAndSeenByEveryViewer() throws Exception {
        String tia = as("tia", "trader");
        String kim = as("kim", "checker");
        JsonNode form = tradesForm(tia);
        assertThat(form.path("save").path("target").asText()).isEqualTo("overlay");
        assertThat(call("GET", "/api/v1/changes/settings", tia, null, 200).path("mayEdit").asBoolean()).isTrue();
        JsonNode row = null;
        for (JsonNode r : form.path("rows")) {                   // a trade under the desk limit: the form's rules hold without a comment
            if (row == null && r.path("values").path("notional").asDouble() < 29_000_000 && r.path("values").path("notional").asDouble() > 0) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        String trade = row.path("key").asText();
        BigDecimal was = row.path("values").path("notional").decimalValue();
        BigDecimal now = was.add(BigDecimal.valueOf(1_250));
        Map<String, Object> values = Map.of("notional", now);
        Map<String, Object> seen = new HashMap<>();
        seen.put("trade_id", trade);
        seen.put("notional", was);
        JsonNode draft = call("POST", "/api/v1/drafts", tia, Map.of("kind", "portfolio-trades", "id", "BOOK-RATES-2", "sutra", "book-desk",
                "panel", "trades", "mode", "edit", "records", List.of(Map.of("record", trade, "values", values, "was", seen))), 201);
        String draftId = draft.path("draft").asText();

        JsonNode sheet = call("POST", "/api/v1/changes/preview", tia, Map.of("draft", draftId, "reason", "booking typo"), 200);
        assertThat(sheet.path("ok").asBoolean()).isTrue();
        JsonNode one = sheet.path("changes").get(0);
        assertThat(one.path("where").asText()).startsWith("Correction kept in Drishti (overlay)");
        assertThat(one.path("approvals").asInt()).isEqualTo(1);
        assertThat(one.path("diff").get(0).path("moved").asBoolean()).isFalse();
        assertThat(call("POST", "/api/v1/changes/preview", tia, Map.of("draft", draftId), 200).path("changes").get(0).path("reasonRequired").asBoolean())
                .as("the sheet asks for the reason").isTrue();

        List<EntityDocument> ticks = new CopyOnWriteArrayList<>();
        List<Object> events = new CopyOnWriteArrayList<>();
        AutoCloseable heard = feed.listen(events::add);
        Subscription viewer = router.subscribe(EntityRef.of("portfolio-trades", "BOOK-RATES-2"), ticks::add);

        JsonNode sent = call("POST", "/api/v1/changes", tia, Map.of("draft", draftId, "reason", "booking typo"), 202).path("changes").get(0);
        String id = sent.path("id").asText();
        assertThat(sent.path("state").asText()).isEqualTo("awaiting");
        assertThat(call("POST", "/api/v1/changes/" + id + "/approve", tia, Map.of(), 403).path("code").asText()).as("not an approver").isEqualTo("DRS-5002");
        JsonNode list = call("GET", "/api/v1/changes?state=awaiting", kim, null, 200);
        assertThat(list.path("changes").findValuesAsText("id")).contains(id);
        call("POST", "/api/v1/changes/" + id + "/approve", kim, Map.of("note", "checked against the ticket"), 200);
        JsonNode applied = waitFor(id, "applied", tia);
        assertThat(applied.path("lane").asInt()).isBetween(0, 63);

        JsonNode rowNow = null;
        for (JsonNode r : tradesForm(as("rae", "reader")).path("rows")) {
            if (trade.equals(r.path("key").asText())) {
                rowNow = r;
            }
        }
        assertThat(rowNow).isNotNull();
        assertThat(rowNow.path("values").path("notional").decimalValue()).as("every reader sees the corrected value").isEqualByComparingTo(now);
        assertThat(view(tia).toString()).contains(EditNotes.LABEL).contains(id);
        call("GET", "/api/v1/drafts/" + draftId, tia, null, 404);                    // the draft is closed with the change id
        long until = System.currentTimeMillis() + 10_000;
        while (ticks.isEmpty() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertThat(ticks).as("a live viewer of the page is told").isNotEmpty();
        assertThat(ticks.get(ticks.size() - 1).data().toString()).contains(now.stripTrailingZeros().toPlainString());
        viewer.close();
        heard.close();
        Map<String, Object> appliedEvent = events.stream().map(e -> ((EditFeed.Event) e).body())
                .filter(b -> "applied".equals(b.get("type")) && id.equals(b.get("id"))).findFirst().orElseThrow();
        assertThat(feed.forReader(new EditFeed.Event(appliedEvent), new com.ash.drishti.server.security.Principal("rae", List.of("reader"))))
                .as("a reader of the kind hears it changed, without the submitter's name or reason").containsEntry("by", "").doesNotContainKey("reason");
        assertThat(feed.forReader(new EditFeed.Event(appliedEvent), new com.ash.drishti.server.security.Principal("tia", List.of("trader"))))
                .containsEntry("mine", true);
        JsonNode history = call("GET", "/api/v1/entities/trade/" + trade + "/history", rae(), null, 200);
        assertThat(history.path("changes").findValuesAsText("id")).contains(id);
        assertThat(history.path("version").asLong()).isGreaterThanOrEqualTo(1);

        // a change based on the value before: the field has moved since, so it is conflicted, never applied over it
        JsonNode stale = call("POST", "/api/v1/changes", kim, Map.of("kind", "trade", "entity", trade, "sutra", "book-desk", "panel", "trades",
                "reason", "late", "items", List.of(Map.of("field", "NOTIONAL", "from", was, "to", was.add(BigDecimal.ONE)))), 202).path("changes").get(0);
        assertThat(call("POST", "/api/v1/changes/" + stale.path("id").asText() + "/approve", kim, Map.of(), 403).path("code").asText())
                .as("four eyes: never your own").isEqualTo("DRS-9302");
        call("POST", "/api/v1/changes/" + stale.path("id").asText() + "/approve", as("ken", "checker"), Map.of(), 200);
        JsonNode conflicted = waitFor(stale.path("id").asText(), "conflicted", kim);
        assertThat(conflicted.path("stateReason").asText()).contains("you saw").contains("it is now");
    }

    private String rae() {
        return as("rae", "reader");
    }

    @Test
    void withoutTheEditPowerOrAReasonOrOnAReadOnlyFieldNothingIsSubmitted() throws Exception {
        Map<String, Object> change = Map.of("kind", "trade", "entity", "T-1", "reason", "x", "items", List.of(Map.of("field", "NOTIONAL", "from", 1, "to", 2)));
        assertThat(call("POST", "/api/v1/changes", rae(), change, 403).path("detail").asText()).contains("edit power");
        Map<String, Object> noReason = Map.of("kind", "trade", "entity", "T-1", "items", List.of(Map.of("field", "NOTIONAL", "from", 1, "to", 2)));
        assertThat(call("POST", "/api/v1/changes", as("tia", "trader"), noReason, 403).path("detail").asText()).contains("needs a reason");
        Map<String, Object> readOnly = Map.of("kind", "trade", "entity", "T-1", "reason", "x", "items", List.of(Map.of("field", "TRADE_ID", "from", "T-1", "to", "T-2")));
        assertThat(call("POST", "/api/v1/changes", as("tia", "trader"), readOnly, 403).path("detail").asText()).contains("read-only");
        Map<String, Object> notEditable = Map.of("kind", "trade", "entity", "T-1", "reason", "x", "items", List.of(Map.of("field", "NO_SUCH_FIELD", "to", 2)));
        assertThat(call("POST", "/api/v1/changes", as("tia", "trader"), notEditable, 403).path("detail").asText()).contains("no Sutra");
        assertThat(call("GET", "/api/v1/admin/edits", as("tia", "trader"), null, 403).path("code").asText()).isEqualTo("DRS-5002");
        JsonNode admin = call("GET", "/api/v1/admin/edits", as("ada", "admin"), null, 200);
        assertThat(admin.path("enabled").asBoolean()).isTrue();
        assertThat(admin.path("mode").asText()).isEqualTo("single");
        assertThat(admin.path("store").asText()).isEqualTo("sqlite");
        assertThat(admin.path("lanes")).hasSize(64);
        assertThat(admin.path("editableKinds").toString()).contains("trade");
    }
}
