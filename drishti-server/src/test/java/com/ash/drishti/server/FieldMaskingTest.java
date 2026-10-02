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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.alerts.AlertEngine;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Field masks mean the same everywhere (QA 2026-10-01 SEC-03, SEC-04, SEC-07, SEC-14): a role without {@code raw} sees the
 * fields in {@code drishti.security.redact} as {@code •••} in views (strip, title, tables, panel records, values derived
 * from them, the live stream, monitors), in Impact and in the type-ahead, and cannot find an entity through them; a role
 * with {@code raw} sees the values.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=trading,counterparty-risk",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=trader,counterparty,mtm",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true"})
@AutoConfigureMockMvc
class FieldMaskingTest {

    static final String MASK = "•••";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired AlertEngine alerts;
    private final ObjectMapper json = new ObjectMapper();

    private String as(String role) {
        return "Bearer " + tokens.mint("u-" + role, List.of(role), 300);
    }

    private JsonNode read(String url, String role, String... params) throws Exception {
        var req = get(url).header("Authorization", as(role));
        for (int i = 0; i + 1 < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode cell(JsonNode cells, String label) {
        for (JsonNode c : cells) {
            if (label.equals(c.path("label").asText())) {
                return c;
            }
        }
        throw new AssertionError("no cell " + label + " in " + cells);
    }

    private static JsonNode panel(JsonNode view, String id) {
        for (JsonNode p : view.path("panels")) {
            if (id.equals(p.path("id").asText())) {
                return p;
            }
        }
        throw new AssertionError("no panel " + id);
    }

    @Test
    void viewsMaskTheStripTitleTablesRecordsAndDerivedValues() throws Exception {
        JsonNode full = read("/api/v1/views/trade/MX-20000001", "full");
        assertThat(cell(full.path("strip"), "MTM (USD)").path("text").asText()).isEqualTo("+1,875,863");
        assertThat(full.path("title").path("with").path("text").asText()).isEqualTo("Meridian Reinsurance Ltd");

        JsonNode view = read("/api/v1/views/trade/MX-20000001", "masked");
        String text = view.toString();
        // (the cash flows' PVs, not masked here, still add up to the MTM: a mask hides the fields it names, nothing else)
        assertThat(text).doesNotContain("Meridian", "CP-MERIDIAN-RE", "\"id\":\"" + MASK);   // nor a link to a masked id
        JsonNode mtm = cell(view.path("strip"), "MTM (USD)");
        assertThat(mtm.path("text").asText()).isEqualTo(MASK);
        assertThat(mtm.path("tone").asText(null)).isNull();                  // the sign of a masked value is not shown either
        assertThat(view.path("title").path("with").path("text").asText()).isEqualTo(MASK);
        assertThat(view.path("title").path("with").has("link") && !view.path("title").path("with").path("link").isNull()).isFalse();
        assertThat(cell(panel(view, "terms").path("data").path("fields"), "Counterparty").path("text").asText()).isEqualTo(MASK);

        JsonNode ns = read("/api/v1/views/netting-set/NS-MERIDIAN-RE-NY", "masked");
        JsonNode table = panel(ns, "trades").path("data");
        int mtmColumn = 4;
        for (JsonNode row : table.path("rows")) {
            assertThat(row.path("cells").get(mtmColumn).path("text").asText()).isEqualTo(MASK);
        }
        assertThat(table.path("total").path("cells").get(mtmColumn).path("text").asText()).isEqualTo(MASK);   // never added up
        assertThat(panel(ns, "byAsset").path("data").path("bars").size()).isZero();
        assertThat(ns.toString()).doesNotContain("Meridian Reinsurance", "\"id\":\"" + MASK, "\"text\":\"" + MASK + "\",\"link\"");

        JsonNode records = read("/api/v1/views/netting-set/NS-MERIDIAN-RE-NY/panels/trades/records", "masked");
        assertThat(records.toString()).contains(MASK);
        int at = -1;
        for (int i = 0; i < records.path("fields").size(); i++) {
            if ("mtm".equals(records.path("fields").get(i).path("name").asText())) {
                at = i;
            }
        }
        assertThat(at).isNotNegative();
        for (JsonNode r : records.path("rows")) {
            assertThat(r.get(at).asText()).isEqualTo(MASK);
        }
        JsonNode fullRecords = read("/api/v1/views/netting-set/NS-MERIDIAN-RE-NY/panels/trades/records", "full");
        assertThat(fullRecords.toString()).doesNotContain(MASK);
    }

    @Test
    void theLiveStreamAndMonitorsCarryMaskedValues() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/views/trade/MX-20000001/stream").header("Authorization", as("masked"))).andReturn();
        String body = "";
        for (int i = 0; i < 100 && !body.contains("event:view"); i++) {
            Thread.sleep(50);
            body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        }
        assertThat(body).contains("event:view", "{\"label\":\"MTM (USD)\",\"text\":\"" + MASK + "\"").doesNotContain("Meridian");

        mvc.perform(put("/api/v1/me/monitors/Watch").header("Authorization", as("masked")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"entities\":[{\"kind\":\"trade\",\"id\":\"MX-20000001\"}]}")).andExpect(status().is2xxSuccessful());
        JsonNode rows = read("/api/v1/me/monitors/Watch", "masked");
        assertThat(rows.toString()).contains(MASK).doesNotContain("Meridian", "1,875,863");
    }

    @Test
    void impactNeitherListsWhatOnlyAMaskedFieldTiesInNorShowsAMaskedMeasure() throws Exception {
        JsonNode full = read("/api/v1/impact/counterparty/CP-MERIDIAN-RE", "full");
        assertThat(kinds(full)).contains("trade");

        JsonNode masked = read("/api/v1/impact/counterparty/CP-MERIDIAN-RE", "masked");
        assertThat(kinds(masked)).doesNotContain("trade", "netting-set");          // tied in only through the masked counterparty
        assertThat(masked.toString()).doesNotContain("MX-20000001");

        JsonNode ns = read("/api/v1/impact/netting-set/NS-MERIDIAN-RE-NY", "masked");
        JsonNode trades = group(ns, "trade");
        assertThat(trades.path("items").size()).isPositive();
        for (JsonNode item : trades.path("items")) {
            assertThat(item.path("measure").asText()).isEqualTo(MASK);
        }
        assertThat(trades.path("total").asText()).isEqualTo(MASK);
        JsonNode nsFull = read("/api/v1/impact/netting-set/NS-MERIDIAN-RE-NY", "full");
        assertThat(group(nsFull, "trade").path("total").asText()).isNotEqualTo(MASK).isNotEmpty();
    }

    private static List<String> kinds(JsonNode impact) {
        List<String> out = new ArrayList<>();
        impact.path("groups").forEach(g -> out.add(g.path("kind").asText()));
        return out;
    }

    private static JsonNode group(JsonNode impact, String kind) {
        for (JsonNode g : impact.path("groups")) {
            if (kind.equals(g.path("kind").asText())) {
                return g;
            }
        }
        throw new AssertionError("no " + kind + " group in " + impact);
    }

    @Test
    void typeAheadNeitherShowsNorMatchesMaskedValues() throws Exception {
        JsonNode full = read("/api/v1/command/suggest", "full", "q", "TRD Meridian Reinsurance", "limit", "50");
        assertThat(full.toString()).contains("MX-20000001");
        assertThat(read("/api/v1/command/suggest", "full", "q", "TRD MX-2000000").toString()).contains("Meridian");

        JsonNode byId = read("/api/v1/command/suggest", "masked", "q", "TRD MX-2000000");
        assertThat(byId.size()).isPositive();
        assertThat(byId.toString()).doesNotContain("Meridian");
        JsonNode byName = read("/api/v1/command/suggest", "masked", "q", "TRD Meridian Reinsurance", "limit", "50");
        assertThat(byName.toString()).doesNotContain("MX-20000001");
        assertThat(read("/api/v1/command/suggest", "masked", "q", "Meridian Reinsurance", "limit", "50").toString())
                .doesNotContain("\"kind\":\"trade\"");
    }

    @Test
    void alertsAndPhrasesCannotProbeAMaskedField() throws Exception {
        String user = "u-masked";
        mvc.perform(put("/api/v1/me/alerts/rules/Probe").header("Authorization", as("masked")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"trade\",\"id\":\"MX-20000001\",\"when\":\"$.mtm > 0\",\"severity\":\"info\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/me/alerts/rules/Shown").header("Authorization", as("masked")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"trade\",\"id\":\"MX-20000001\",\"when\":\"$.notional > 0\",\"severity\":\"info\","
                        + "\"message\":\"MTM ${fmt($.mtm, 'signed0')} with ${$.counterparty.name}\"}"))
                .andExpect(status().isOk());
        long deadline = System.currentTimeMillis() + 5000;
        while (alerts.events(user, 10).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        Thread.sleep(400);
        assertThat(alerts.events(user, 50)).isNotEmpty().allSatisfy(e -> {   // the rule on mtm never fires
            assertThat(e.rule()).isEqualTo("Shown");
            assertThat(e.message()).isEqualTo("MTM " + MASK + " with " + MASK);
        });

        JsonNode full = read("/api/v1/phrase", "full", "text", "trades of trader TRDR-ASHAH");
        JsonNode masked = read("/api/v1/phrase", "masked", "text", "trades of trader TRDR-ASHAH");
        assertThat(full.path("query").asText()).contains("TRDR-ASHAH");
        assertThat(masked.path("query").asText()).doesNotContain("TRDR-ASHAH");
    }

    @Test
    void aPivotOnAFieldUnderAMaskedParentGroupsUnderTheMask() throws Exception {
        JsonNode cube = json.readTree(mvc.perform(post("/api/v1/search/pivot/TRD").header("Authorization", as("masked"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rows\":[\"counterparty.name\"],\"values\":[{\"field\":\"notional\",\"agg\":\"sum\"}],\"documents\":true}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(cube.path("rowKeys").size()).isEqualTo(1);
        assertThat(cube.path("rowKeys").get(0).get(0).asText()).isEqualTo(MASK);
        assertThat(cube.path("masked").toString()).contains("counterparty.name");
    }
}
