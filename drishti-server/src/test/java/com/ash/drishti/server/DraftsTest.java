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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Forms and drafts (SUTRA_NEXT.md 10) on the pack screens that carry a {@code form:}: the book desk's trades (edit beside, new in a
 * drawer) and a credit limit's buckets (edited in the cells). The view model carries each form as the viewer may use it (a masked
 * field marked, a field of another role locked, the lookup's choices read as the viewer); {@code /api/v1/drafts} keeps drafts per
 * person, checks them again with the same rules (an error refuses, a warning is kept), and refuses a masked or a locked field.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=trading,counterparty-risk",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=COUNTERPARTY",
        "drishti.security.roles.trader.kinds[0]=*", "drishti.security.roles.trader.raw=true",
        "drishti.security.roles.middle-office.kinds[0]=*", "drishti.security.roles.middle-office.raw=true",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false"})
@AutoConfigureMockMvc
class DraftsTest {

    static final String DESK = "/api/v1/views/portfolio-trades/BOOK-RATES-2";
    static final String LIMIT = "/api/v1/views/credit-limit/LIM-ALDERSHOT";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    private final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode read(String url, String auth, String sutra) throws Exception {
        var req = get(url).header("Authorization", auth);
        if (sutra != null) {
            req = req.param("sutra", sutra);
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode panel(JsonNode view, String id) {
        for (JsonNode p : view.path("panels")) {
            if (id.equals(p.path("id").asText())) {
                return p;
            }
        }
        throw new AssertionError("no panel " + id);
    }

    private static JsonNode field(JsonNode form, String id) {
        for (JsonNode f : form.path("fields")) {
            if (id.equals(f.path("id").asText())) {
                return f;
            }
        }
        throw new AssertionError("no field " + id);
    }

    private String send(String method, String url, String auth, Object body, int expect) throws Exception {
        var req = switch (method) {
            case "POST" -> post(url);
            case "PUT" -> put(url);
            default -> delete(url);
        };
        req = req.header("Authorization", auth);
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(req).andExpect(status().is(expect)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> edit(String record, Map<String, Object> values, Map<String, Object> was) {
        return Map.of("kind", "portfolio-trades", "id", "BOOK-RATES-2", "sutra", "book-desk", "panel", "trades", "mode", "edit",
                "records", List.of(Map.of("record", record, "values", values, "was", was)));
    }

    @Test
    void theViewCarriesTheFormAsTheViewerMayUseIt() throws Exception {
        JsonNode trader = panel(read(DESK, as("tia", "trader"), "book-desk"), "trades").path("form");
        assertThat(trader.path("edit").asText()).isEqualTo("beside");
        assertThat(trader.path("new").asText()).isEqualTo("drawer");
        assertThat(field(trader, "trade_status").path("locked").asText()).isEqualTo("locked: you can view, not change");
        assertThat(field(trader, "notional").has("locked")).isFalse();
        assertThat(field(trader, "notional").path("col").asInt()).isEqualTo(3);
        assertThat(field(trader, "counterparty").path("options")).isNotEmpty();
        assertThat(field(trader, "counterparty").path("options").get(0).path("carry").has("LIMIT")).isTrue();
        assertThat(trader.path("rows")).isNotEmpty();
        assertThat(trader.path("rows").get(0).path("values").path("trade_id").asText()).isNotBlank();
        JsonNode mo = panel(read(DESK, as("mo", "middle-office"), "book-desk"), "trades").path("form");
        assertThat(field(mo, "trade_status").has("locked")).isFalse();
        JsonNode masked = panel(read(DESK, as("max", "masked"), "book-desk"), "trades").path("form");
        assertThat(field(masked, "counterparty").path("masked").asBoolean()).isTrue();
        assertThat(field(masked, "counterparty").has("options")).as("a masked lookup offers nothing").isFalse();
        assertThat(masked.path("rows").get(0).path("values").has("counterparty")).isFalse();
        assertThat(masked.path("rows").get(0).path("masked").toString()).contains("counterparty");
        JsonNode cells = panel(read(LIMIT, as("tia", "trader"), null), "buckets").path("form");
        assertThat(cells.path("edit").asText()).isEqualTo("cells");
        assertThat(cells.path("rows")).hasSize(5);
    }

    @Test
    void draftsAreKeptPerPersonAndCheckedAgain() throws Exception {
        String tia = as("tom", "trader");
        JsonNode saved = json.readTree(send("POST", "/api/v1/drafts", tia,
                edit("T-1", Map.of("notional", 25_000_000), Map.of("trade_id", "T-1", "notional", 20_000_000)), 201));
        String id = saved.path("draft").asText();
        assertThat(id).startsWith("d-");
        assertThat(saved.path("changes").asInt()).isEqualTo(1);
        assertThat(saved.path("warnings")).isEmpty();
        JsonNode mine = json.readTree(mvc.perform(get("/api/v1/drafts").param("kind", "portfolio-trades").param("id", "BOOK-RATES-2")
                .header("Authorization", tia)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(mine).hasSize(1);
        JsonNode warned = json.readTree(send("PUT", "/api/v1/drafts/" + id, tia,
                edit("T-1", Map.of("notional", 40_000_000, "comment", "client hedge"), Map.of("trade_id", "T-1", "notional", 20_000_000)), 200));
        assertThat(warned.path("warnings").get(0).path("message").asText()).startsWith("Above the desk limit of 30,000,000");
        String other = as("ola", "trader");
        mvc.perform(get("/api/v1/drafts/" + id).header("Authorization", other)).andExpect(status().isNotFound());
        assertThat(json.readTree(mvc.perform(get("/api/v1/drafts").header("Authorization", other)).andReturn().getResponse().getContentAsString())).isEmpty();
        send("DELETE", "/api/v1/drafts/" + id, tia, null, 204);
        mvc.perform(get("/api/v1/drafts/" + id).header("Authorization", tia)).andExpect(status().isNotFound());
    }

    @Test
    void anErrorRefusesTheDraftInWords() throws Exception {
        String tia = as("tia", "trader");
        JsonNode e = json.readTree(send("POST", "/api/v1/drafts", tia, edit("T-1", Map.of("notional", -5), Map.of("notional", 1)), 422));
        assertThat(e.path("issues").get(0).path("message").asText()).isEqualTo("Notional is at least 0");
        e = json.readTree(send("POST", "/api/v1/drafts", tia, edit("T-1", Map.of("notional", 40_000_000), Map.of("notional", 1)), 422));
        assertThat(e.path("issues").get(0).path("message").asText()).startsWith("Say why the trade is above the desk limit");
        assertThat(e.path("warnings").get(0).path("severity").asText()).isEqualTo("warn");
        Map<String, Object> cells = Map.of("kind", "credit-limit", "id", "LIM-ALDERSHOT", "sutra", "credit-limit", "panel", "buckets", "mode", "cells",
                "records", List.of(Map.of("record", "0-1Y", "values", Map.of("limit", 100), "was", Map.of("bucket", "0-1Y", "limit", 337_000_000, "used", 191_080_181))));
        e = json.readTree(send("POST", "/api/v1/drafts", tia, cells, 422));
        assertThat(e.path("issues").get(0).path("message").asText()).startsWith("The new limit is below what's already used");
        assertThat(e.path("issues").get(0).path("record").asText()).isEqualTo("0-1Y");
    }

    @Test
    void aMaskedOrLockedFieldIsRefused() throws Exception {
        String body = send("POST", "/api/v1/drafts", as("max", "masked"),
                edit("T-1", Map.of("counterparty", "CP-X"), Map.of("notional", 1)), 403);
        assertThat(body).contains("Counterparty is hidden for you");
        assertThat(send("POST", "/api/v1/drafts", as("tia", "trader"), edit("T-1", Map.of("trade_status", "VALIDATED"), Map.of("trade_status", "PENDING")), 403))
                .contains("locked: you can view, not change");
        send("POST", "/api/v1/drafts", as("mo", "middle-office"), edit("T-1", Map.of("trade_status", "VALIDATED"), Map.of("trade_status", "PENDING")), 201);
        assertThat(send("POST", "/api/v1/drafts", as("tia", "trader"), edit("T-1", Map.of("trade_id", "T-2"), Map.of("trade_id", "T-1")), 403))
                .contains("read-only");
    }

    @Test
    void aNewRecordFromTheDrawer() throws Exception {
        Map<String, Object> values = new java.util.HashMap<>(Map.of("counterparty", "CP-ALDERSHOT", "product_type", "IRS", "notional", 10_000_000,
                "currency", "USD", "maturity_date", "2031-01-15"));
        Map<String, Object> draft = Map.of("kind", "portfolio-trades", "id", "BOOK-RATES-2", "sutra", "book-desk", "panel", "trades", "mode", "new",
                "step", "what", "records", List.of(Map.of("values", values)));
        JsonNode d = json.readTree(send("POST", "/api/v1/drafts", as("tia", "trader"), draft, 201));
        assertThat(d.path("step").asText()).isEqualTo("what");
        values.remove("notional");
        JsonNode e = json.readTree(send("POST", "/api/v1/drafts", as("tia", "trader"), draft, 422));
        assertThat(e.path("issues").get(0).path("message").asText()).isEqualTo("Notional is required");
        Map<String, Object> early = Map.of("kind", "portfolio-trades", "id", "BOOK-RATES-2", "sutra", "book-desk", "panel", "trades", "mode", "new",
                "step", "who", "records", List.of(Map.of("values", Map.of("counterparty", "CP-ALDERSHOT"))));
        send("POST", "/api/v1/drafts", as("tia", "trader"), early, 201);          // a draft saved at the first step is checked up to it
    }

    @Test
    void moneyDatetimeEntityAndDefaults() throws Exception {
        JsonNode form = panel(read(DESK, as("tia", "trader"), "book-desk"), "trades").path("form");
        assertThat(field(form, "notional").path("type").asText()).isEqualTo("money");
        assertThat(field(form, "notional").path("currency").asText()).isEqualTo("currency");
        assertThat(field(form, "executed_at").path("tz").asText()).isEqualTo("Europe/London");
        assertThat(field(form, "upfront_fee").path("default").asText()).isEqualTo("=notional * 0.001");
        JsonNode le = field(form, "legal_entity");
        assertThat(le.path("entity").path("kind").asText()).isEqualTo("legal-entity");
        assertThat(le.path("options")).isNotEmpty();
        assertThat(le.path("options").get(0).path("value").asText()).as("the entity's own id").startsWith("LE-");
        assertThat(le.path("options").get(0).path("label").asText()).doesNotStartWith("LE-");
        assertThat(form.path("rows").get(0).path("values").path("legal_entity").asText()).startsWith("LE-");
        String tia = as("tia", "trader");
        assertThat(send("POST", "/api/v1/drafts", tia, edit("T-1", Map.of("notional", "25m"), Map.of("notional", 20_000_000)), 201)).contains("\"25m\"");
        JsonNode e = json.readTree(send("POST", "/api/v1/drafts", tia, edit("T-1", Map.of("notional", "lots"), Map.of("notional", 1)), 422));
        assertThat(e.path("issues").get(0).path("message").asText()).isEqualTo("Notional is an amount (1,000,000, 1.5m or 250k)");
        e = json.readTree(send("POST", "/api/v1/drafts", tia, edit("T-1", Map.of("executed_at", "tomorrow"), Map.of()), 422));
        assertThat(e.path("issues").get(0).path("message").asText()).isEqualTo("Executed is a date and time (2026-12-31T17:00)");
        Map<String, Object> values = new java.util.HashMap<>(Map.of("counterparty", "CP-ALDERSHOT", "product_type", "IRS", "notional", "10m",
                "currency", "USD", "maturity_date", "2031-01-15", "executed_at", "2026-10-23T14:05", "legal_entity", "LE-NY"));
        Map<String, Object> draft = Map.of("kind", "portfolio-trades", "id", "BOOK-RATES-2", "sutra", "book-desk", "panel", "trades", "mode", "new",
                "step", "check", "records", List.of(Map.of("values", values)));
        JsonNode d = json.readTree(send("POST", "/api/v1/drafts", tia, draft, 201));
        assertThat(d.path("records").get(0).path("values").path("upfront_fee").decimalValue()).as("the default worked out: 10m * 0.001")
                .isEqualByComparingTo("10000");
        values.put("upfront_fee", 2_500);
        d = json.readTree(send("POST", "/api/v1/drafts", tia, draft, 201));
        assertThat(d.path("records").get(0).path("values").path("upfront_fee").asInt()).as("a value given stays").isEqualTo(2_500);
    }

    private JsonNode lookup(String auth, String query) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/forms/lookup?" + query).header("Authorization", auth)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("results");
    }

    @Test
    void anEntityFieldSearchesLiveAsTheCallerAndAnUnknownIdIsRefused() throws Exception {
        String tia = as("tia", "trader");
        JsonNode le = field(panel(read(DESK, tia, "book-desk"), "trades").path("form"), "legal_entity");
        assertThat(le.path("entity").path("label").asText()).isEqualTo("NAME");
        String first = le.path("options").get(0).path("label").asText();
        JsonNode found = lookup(tia, "kind=legal-entity&pack=core&q=" + java.net.URLEncoder.encode(first.substring(0, Math.min(6, first.length())), StandardCharsets.UTF_8));
        assertThat(found).isNotEmpty();
        assertThat(found.get(0).path("id").asText()).startsWith("LE-");
        assertThat(lookup(tia, "kind=legal-entity&q=LE-&limit=1")).hasSize(1);
        assertThat(lookup(tia, "kind=legal-entity&q=LE-&limit=9999").size()).isLessThanOrEqualTo(50);
        assertThat(lookup(tia, "kind=legal-entity&q=")).isEmpty();
        assertThat(lookup(tia, "kind=legal-entity&q=no-such-entity-anywhere")).isEmpty();
        // a masked value is never offered nor matched: COUNTERPARTY is masked for the masked role, not for the trader
        assertThat(lookup(tia, "kind=credit-limit&q=ALDERSHOT&value=COUNTERPARTY&label=COUNTERPARTY_NAME")).isNotEmpty();
        assertThat(lookup(as("max", "masked"), "kind=credit-limit&q=ALDERSHOT&value=COUNTERPARTY&label=COUNTERPARTY_NAME")).isEmpty();
        mvc.perform(get("/api/v1/forms/lookup?kind=Bad Kind&q=x").header("Authorization", tia)).andExpect(status().isBadRequest());
        Map<String, Object> values = new java.util.HashMap<>(Map.of("counterparty", "CP-ALDERSHOT", "product_type", "IRS", "notional", "10m",
                "currency", "USD", "maturity_date", "2031-01-15", "legal_entity", "LE-NOWHERE"));
        Map<String, Object> draft = Map.of("kind", "portfolio-trades", "id", "BOOK-RATES-2", "sutra", "book-desk", "panel", "trades", "mode", "new",
                "step", "check", "records", List.of(Map.of("values", values)));
        JsonNode e = json.readTree(send("POST", "/api/v1/drafts", tia, draft, 422));
        assertThat(e.path("issues").get(0).path("field").asText()).isEqualTo("legal_entity");
        assertThat(e.path("issues").get(0).path("message").asText()).isEqualTo("Booked in: no legal entity 'LE-NOWHERE' was found");
        values.put("legal_entity", found.get(0).path("id").asText());
        send("POST", "/api/v1/drafts", tia, draft, 201);
    }
}
