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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Sutra Next phases 2 and 3 end to end, on the book desk ({@code /v/portfolio-trades/<book>?sutra=book-desk}): panels reading other
 * packs' kinds (trade from trading, counterparty from core, credit-limit from counterparty-risk), a click evaluated on the server as the
 * reader, only the listening panels returned, children filled lazily under the clicked row, a new click replacing them; and masks in
 * both directions: a masked counterparty stays masked in the payload and the response (never sent), a panel that follows it shows
 * nothing but "hidden for you" (the counterparty and its limit), and its token is refused to another person and on another screen.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=trading,counterparty-risk",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=COUNTERPARTY",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true",
        "drishti.security.roles.nolimits.kinds[0]=portfolio-trades", "drishti.security.roles.nolimits.kinds[1]=trade",
        "drishti.security.roles.nolimits.kinds[2]=counterparty", "drishti.security.roles.nolimits.raw=true"})
@AutoConfigureMockMvc
class SutraNextEventsTest {

    static final String MASK = "•••";
    static final String DESK = "/api/v1/views/portfolio-trades/BOOK-RATES-2";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    private final ObjectMapper json = new ObjectMapper();

    private String as(String role) {
        return "Bearer " + tokens.mint("u-" + role, List.of(role), 300);
    }

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode view(String role) throws Exception {
        return json.readTree(mvc.perform(get(DESK).param("sutra", "book-desk").header("Authorization", as(role))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String click(String auth, String url, String panel, int row, List<String> selections, int expect) throws Exception {
        String body = json.writeValueAsString(java.util.Map.of("panel", panel, "event", "open", "row", row, "selections", selections));
        return mvc.perform(post(url + "/events").param("sutra", "book-desk").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().is(expect)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode panel(JsonNode panels, String id) {
        for (JsonNode p : panels) {
            if (id.equals(p.path("id").asText())) {
                return p;
            }
        }
        throw new AssertionError("no panel " + id + " in " + panels);
    }

    private static String field(JsonNode kv, String label) {
        for (JsonNode f : kv.path("data").path("fields")) {
            if (label.equals(f.path("label").asText())) {
                return f.path("text").asText();
            }
        }
        throw new AssertionError("no field " + label + " in " + kv);
    }

    @Test
    void theFirstPaintWaitsAndLoadsNoChildren() throws Exception {
        JsonNode v = view("full");
        JsonNode trades = panel(v.path("panels"), "trades");
        assertThat(trades.path("wire").path("emits").get(0).path("name").asText()).isEqualTo("open");
        assertThat(trades.path("wire").path("children").get(0).path("place").asText()).isEqualTo("expand-row");
        assertThat(trades.path("data").path("rows").get(0).path("ev").asText()).isEqualTo("0");
        for (String id : List.of("counterparty", "limit", "buckets")) {
            JsonNode p = panel(v.path("panels"), id);
            assertThat(p.path("wire").path("waits").asText()).as(id).startsWith("Pick a trade");
            assertThat(p.path("data").isMissingNode() || p.path("data").isNull()).as(id).isTrue();
        }
        assertThat(v.toString()).doesNotContain("\"flows\"");                  // children are lazy: nothing until a click
        assertThat(v.path("provenance").path("sutra").asText()).isEqualTo("book-desk");
    }

    @Test
    void aClickFillsTheListenersAndTheChildrenAcrossPacksAndANewClickReplacesThem() throws Exception {
        JsonNode v = view("full");
        JsonNode rows = panel(v.path("panels"), "trades").path("data").path("rows");
        String first = rows.get(0).path("cells").get(0).path("text").asText();
        String cpty = rows.get(0).path("cells").get(2).path("text").asText();
        JsonNode r = json.readTree(click(as("full"), DESK, "trades", 0, List.of(), 200));
        assertThat(r.path("selection").path("payload").path("id").asText()).isEqualTo(first);
        assertThat(r.path("selection").path("payload").path("ref").asText()).isEqualTo(cpty);
        assertThat(r.path("selection").path("token").asText()).startsWith("tok_");
        assertThat(r.path("panels")).extracting(p -> p.path("id").asText()).containsExactlyInAnyOrder("counterparty", "limit", "buckets");
        assertThat(field(panel(r.path("panels"), "counterparty"), "Name")).isNotBlank().isNotEqualTo("—");      // core pack
        assertThat(field(panel(r.path("panels"), "limit"), "Measure")).contains("PFE");                         // counterparty-risk pack
        assertThat(panel(r.path("panels"), "buckets").path("data").path("bars").size()).isEqualTo(5);
        JsonNode kids = r.path("children").get(0);
        assertThat(kids.path("place").asText()).isEqualTo("expand-row");
        JsonNode flows = kids.path("panels").get(0);
        assertThat(flows.path("id").asText()).isEqualTo("flows");                                           // trading pack, the trade itself
        assertThat(flows.path("title").asText()).isEqualTo("Cash flows of " + first);
        assertThat(flows.path("data").path("rows").size()).isPositive();
        assertThat(flows.path("wire").path("parent").asText()).isEqualTo("trades");

        String second = rows.get(1).path("cells").get(0).path("text").asText();
        JsonNode r2 = json.readTree(click(as("full"), DESK, "trades", 1, List.of(r.path("selection").path("token").asText()), 200));
        assertThat(r2.path("selection").path("payload").path("id").asText()).isEqualTo(second);
        assertThat(r2.path("children").get(0).path("panels").get(0).path("title").asText()).isEqualTo("Cash flows of " + second);

        JsonNode lazy = json.readTree(mvc.perform(get(DESK + "/panels/trades/children").param("sutra", "book-desk")
                .param("sel", r2.path("selection").path("token").asText()).header("Authorization", as("full")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(lazy.get(0).path("hidden").asBoolean()).isFalse();
        assertThat(lazy.get(0).path("panels").get(0).path("title").asText()).isEqualTo("Cash flows of " + second);
        JsonNode none = json.readTree(mvc.perform(get(DESK + "/panels/trades/children").param("sutra", "book-desk")
                .header("Authorization", as("full"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(none.get(0).path("hidden").asBoolean()).isTrue();                   // nothing picked: the child is hidden
    }

    @Test
    void aMaskedIdStaysMaskedAcrossPanelsAndItsTokenIsTheReadersAlone() throws Exception {
        String raw = panel(view("full").path("panels"), "trades").path("data").path("rows").get(0).path("cells").get(2).path("text").asText();
        assertThat(raw).startsWith("CP-");
        JsonNode v = view("masked");
        assertThat(panel(v.path("panels"), "trades").path("data").path("rows").get(0).path("cells").get(2).path("text").asText()).isEqualTo(MASK);
        String body = click(as("ann", "masked"), DESK, "trades", 0, List.of(), 200);
        JsonNode r = json.readTree(body);
        assertThat(r.path("selection").path("payload").path("ref").asText()).isEqualTo(MASK);
        assertThat(body).doesNotContain(raw);                                          // the id is never sent, in the payload or a panel
        for (String followed : List.of("counterparty", "limit")) {                     // a followed masked id shows nothing, in words (15)
            assertThat(panel(r.path("panels"), followed).path("denied").asText()).as(followed).isEqualTo("hidden for you");
            assertThat(panel(r.path("panels"), followed).path("data").isMissingNode()).as(followed).isTrue();
        }
        String token = r.path("selection").path("token").asText();
        assertThat(token).startsWith("tok_").doesNotContain(raw);
        assertThat(click(as("ann", "masked"), DESK, "trades", 1, List.of(token), 200)).doesNotContain(raw);
        assertThat(click(as("bob", "masked"), DESK, "trades", 1, List.of(token), 403)).contains("DRS-5002");    // another person
        assertThat(click(as("ann", "masked"), "/api/v1/views/portfolio-trades/BOOK-RATES-1", "trades", 0, List.of(token), 403))
                .contains("DRS-5002");                                                // another screen
        mvc.perform(get(DESK + "/panels/trades/children").param("sutra", "book-desk").param("sel", token).header("Authorization", as("bob", "full")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSourceOfAKindTheReaderMayNotOpenIsNeverFetched() throws Exception {
        JsonNode r = json.readTree(click(as("nolimits"), DESK, "trades", 0, List.of(), 200));
        assertThat(panel(r.path("panels"), "limit").path("denied").asText()).isEqualTo("no access to credit-limit");
        assertThat(panel(r.path("panels"), "limit").path("data").isMissingNode()).isTrue();
        assertThat(field(panel(r.path("panels"), "counterparty"), "Name")).isNotBlank();
    }

    @Test
    void badClicksAreRefused() throws Exception {
        assertThat(click(as("full"), DESK, "nope", 0, List.of(), 400)).contains("no panel 'nope'");
        assertThat(click(as("full"), DESK, "trades", 99999, List.of(), 400)).contains("shows no row 99999");
        assertThat(click(as("full"), DESK, "trades", 0, List.of("tok_forged"), 403)).contains("DRS-5002");
        mvc.perform(get(DESK).param("sutra", "ir-curve").header("Authorization", as("full"))).andExpect(status().isBadRequest());
    }

    /** The Sutra designer's saved page: {@code ?design=<id>} draws the view, its events and its children with the reader's own Design. */
    @Test
    void aDesignOfTheReaderDrawsTheViewAndItsEventsAndNoOneElsesDoes() throws Exception {
        String text = new String(getClass().getResourceAsStream("/book-desk-design.sutra.yaml").readAllBytes(), StandardCharsets.UTF_8);
        String made = mvc.perform(post("/api/v1/builder/designs").header("Authorization", as("designer", "full")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("name", "My page · portfolio-trades", "kind", "portfolio-trades", "sutra", text))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String id = json.readTree(made).path("id").asText();
        JsonNode v = json.readTree(mvc.perform(get(DESK).param("design", id).header("Authorization", as("designer", "full"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(v.path("provenance").path("sutra").asText()).isEqualTo("my-book-page");
        assertThat(v.path("panels")).extracting(p -> p.path("id").asText()).containsExactly("trades", "counterparty");
        String body = json.writeValueAsString(java.util.Map.of("panel", "trades", "event", "open", "row", 0, "selections", List.of()));
        JsonNode r = json.readTree(mvc.perform(post(DESK + "/events").param("design", id).header("Authorization", as("designer", "full"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(r.path("panels")).extracting(p -> p.path("id").asText()).containsExactly("counterparty");
        assertThat(r.path("children").get(0).path("place").asText()).isEqualTo("detail-right");
        mvc.perform(get(DESK).param("design", id).header("Authorization", as("someone-else", "full"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/views/trade/MX-20000001").param("design", id).header("Authorization", as("designer", "full")))
                .andExpect(status().isBadRequest());
    }

    /** {@code refresh:} on the Sutra reaches every top-level panel of the view model, and the panels an event answers (RACHANA_REFERENCE.md 9). */
    @Test
    void refreshIsOnEveryPanelOfTheViewAndOfAnEventsAnswer() throws Exception {
        String text = new String(getClass().getResourceAsStream("/book-desk-design.sutra.yaml").readAllBytes(), StandardCharsets.UTF_8)
                + "\nrefresh: { every: 30, user-can-pause: false }\n";
        String made = mvc.perform(post("/api/v1/builder/designs").header("Authorization", as("refresher", "full")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("name", "My page · portfolio-trades", "kind", "portfolio-trades", "sutra", text))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String id = json.readTree(made).path("id").asText();
        JsonNode v = json.readTree(mvc.perform(get(DESK).param("design", id).header("Authorization", as("refresher", "full"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(v.path("panels")).allSatisfy(p -> {
            assertThat(p.path("refresh").path("every").asInt()).isEqualTo(30);
            assertThat(p.path("refresh").path("userCanPause").asBoolean(true)).isFalse();
        });
        String body = json.writeValueAsString(java.util.Map.of("panel", "trades", "event", "open", "row", 0, "selections", List.of()));
        JsonNode r = json.readTree(mvc.perform(post(DESK + "/events").param("design", id).header("Authorization", as("refresher", "full"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(r.path("panels").get(0).path("refresh").path("every").asInt()).isEqualTo(30);
        JsonNode plain = json.readTree(mvc.perform(get(DESK).param("sutra", "book-desk").header("Authorization", as("full"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(plain.path("panels")).allSatisfy(p -> assertThat(p.has("refresh")).isFalse());     // a Sutra without refresh: none
    }
}
