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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controls in the view pipeline: the request's {@code ctl.<id>} values reach the panels' {@code where}, {@code limit} and
 * {@code title} on the server, options come from the record as the caller may see it, and only the panels asked for are built.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=trading,counterparty-risk",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=trader,counterparty,mtm",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true"})
@AutoConfigureMockMvc
class ControlsApiTest {

    static final String MASK = "•••";
    static final String IRS = "/api/v1/views/trade/MX-20000001";
    static final String NETTING = "/api/v1/views/netting-set/NS-MERIDIAN-RE-NY";

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode read(String url, String role, String... params) throws Exception {
        var req = get(url).header("Authorization", "Bearer " + tokens.mint("u-" + role, List.of(role), 300));
        for (int i = 0; i + 1 < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode panel(JsonNode view, String id) {
        for (JsonNode p : view.path("panels")) {
            if (id.equals(p.path("id").asText())) {
                return p;
            }
        }
        throw new AssertionError("no panel " + id + " in " + view.path("panels").size() + " panels");
    }

    private static JsonNode control(JsonNode view, String id) {
        for (JsonNode c : view.path("controls")) {
            if (id.equals(c.path("id").asText())) {
                return c;
            }
        }
        throw new AssertionError("no control " + id);
    }

    private static List<String> values(JsonNode control) {
        List<String> out = new ArrayList<>();
        control.path("options").forEach(o -> out.add(o.path("value").asText()));
        return out;
    }

    private JsonNode schedule() throws Exception {
        return json.readTree(Files.readString(Path.of("../config/packs/trading/samples/trade/MX-20000001.json"))).path("schedule");
    }

    @Test
    void theViewCarriesItsControlsWithOptionsAndTheDefaults() throws Exception {
        JsonNode view = read(IRS, "full");
        assertThat(view.path("controls")).hasSize(2);
        JsonNode legs = control(view, "legs");
        assertThat(legs.path("kind").asText()).isEqualTo("multi-select");
        assertThat(legs.path("placement").asText()).isEqualTo("header");
        assertThat(legs.path("panel").asText()).isEqualTo("schedule");
        assertThat(values(legs)).containsExactly("1", "2");
        assertThat(legs.path("values")).hasSize(2);                                // default: all
        assertThat(legs.path("remember").asBoolean()).isTrue();
        assertThat(legs.path("reads")).extracting(JsonNode::asText).contains("schedule");
        JsonNode when = control(view, "when");
        assertThat(when.path("kind").asText()).isEqualTo("date-range");
        assertThat(when.path("value").asText()).isEmpty();
        assertThat(when.path("options").size()).isGreaterThan(5);                  // presets, resolved on the calendar
        assertThat(when.path("options").get(0).path("value").asText()).matches("\\d{4}-\\d{2}-\\d{2}\\.\\.\\d{4}-\\d{2}-\\d{2}");
        assertThat(panel(view, "schedule").path("data").path("rows")).hasSize(schedule().size());
    }

    @Test
    void aControlFiltersTheRowsOnTheServerWithTheSameExpression() throws Exception {
        int leg1 = 0;
        int inRange = 0;
        int both = 0;
        for (JsonNode r : schedule()) {
            boolean one = r.path("leg").asInt() == 1;
            boolean in = r.path("payDate").asText().compareTo("2026-10-01") >= 0 && r.path("payDate").asText().compareTo("2027-03-31") <= 0;
            leg1 += one ? 1 : 0;
            inRange += in ? 1 : 0;
            both += one && in ? 1 : 0;
        }
        assertThat(leg1).isBetween(1, schedule().size() - 1);
        assertThat(panel(read(IRS, "full", "ctl.legs", "1"), "schedule").path("data").path("rows")).hasSize(leg1);
        JsonNode ranged = read(IRS, "full", "ctl.when", "2026-10-01..2027-03-31");
        assertThat(panel(ranged, "schedule").path("data").path("rows")).hasSize(inRange);
        assertThat(panel(ranged, "schedule").path("title").asText()).isEqualTo("Cashflows · paid 2026-10-01..2027-03-31");
        assertThat(control(ranged, "when").path("value").asText()).isEqualTo("2026-10-01..2027-03-31");
        assertThat(panel(read(IRS, "full", "ctl.legs", "1", "ctl.when", "2026-10-01..2027-03-31"), "schedule").path("data").path("rows")).hasSize(both);
        assertThat(panel(read(IRS, "full"), "schedule").path("title").asText()).isEqualTo("Cashflows");
    }

    @Test
    void aValueThatIsNotAnOptionFallsBackToTheDefaultAndAnOpenRangeHoldsEverything() throws Exception {
        assertThat(panel(read(IRS, "full", "ctl.legs", "99"), "schedule").path("data").path("rows")).hasSize(schedule().size());
        assertThat(control(read(IRS, "full", "ctl.legs", "99"), "legs").path("values")).hasSize(2);
        assertThat(panel(read(IRS, "full", "ctl.when", "garbage"), "schedule").path("data").path("rows")).hasSize(schedule().size());
        assertThat(panel(read(IRS, "full", "ctl.when", "2027-01-01.."), "schedule").path("data").path("rows").size()).isLessThan(schedule().size());
    }

    @Test
    void aRelativeRangeIsResolvedOnTheCalendar() throws Exception {
        JsonNode view = read(IRS, "full", "ctl.when", "next-90d");
        String[] range = control(view, "when").path("value").asText().split("\\.\\.");
        assertThat(range).hasSize(2);
        assertThat(LocalDate.parse(range[1]).toEpochDay() - LocalDate.parse(range[0]).toEpochDay()).isEqualTo(89);
        String[] month = control(read(IRS, "full", "ctl.when", "this-month"), "when").path("value").asText().split("\\.\\.");
        assertThat(LocalDate.parse(month[0]).getDayOfMonth()).isEqualTo(1);
        assertThat(LocalDate.parse(month[1]).plusDays(1).getDayOfMonth()).isEqualTo(1);
    }

    @Test
    void onlyTheNamedPanelsAreBuilt() throws Exception {
        JsonNode view = read(IRS, "full", "ctl.legs", "2", "panels", "schedule");
        assertThat(view.path("panels")).hasSize(1);
        assertThat(view.path("panels").get(0).path("id").asText()).isEqualTo("schedule");
        assertThat(view.path("controls")).hasSize(2);                               // the controls always come back (their options may have moved)
        assertThat(read(IRS, "full", "panels", "terms,schedule").path("panels")).hasSize(2);
    }

    @Test
    void topNLimitsTheRowsAndASelectSetsTheDropdown() throws Exception {
        JsonNode view = read(NETTING, "full");
        JsonNode trades = panel(view, "trades");
        int all = trades.path("data").path("rows").size();
        assertThat(control(view, "top").path("value").asText()).isEqualTo("25");
        assertThat(all).isLessThanOrEqualTo(25);
        JsonNode five = read(NETTING, "full", "ctl.top", "10");
        assertThat(panel(five, "trades").path("data").path("rows").size()).isLessThanOrEqualTo(10);
        assertThat(view.path("selects")).hasSize(1);
        assertThat(view.path("selects").get(0).path("by").asText()).isEqualTo("asset");
        String asset = values(control(view, "asset")).get(0);
        JsonNode filtered = read(NETTING, "full", "ctl.asset", asset);
        JsonNode rows = panel(filtered, "trades").path("data").path("rows");
        assertThat(rows.size()).isBetween(1, all);
        assertThat(panel(filtered, "trades").path("title").asText()).isEqualTo("Member trades · " + asset);
        rows.forEach(r -> assertThat(r.path("cells").get(1).path("text").asText()).isNotEmpty());
        assertThat(control(filtered, "asset").path("value").asText()).isEqualTo(asset);
        assertThat(control(filtered, "asset").path("reads")).extracting(JsonNode::asText).contains("trades");
    }

    @Test
    void aSliderCarriesItsBoundsFromTheDataAndFiltersTheRowsOnTheServer() throws Exception {
        JsonNode view = read(NETTING, "full");
        JsonNode slider = control(view, "minNotional");
        assertThat(slider.path("kind").asText()).isEqualTo("slider");
        assertThat(slider.path("min").asText()).isEqualTo("0");
        assertThat(slider.path("max").asDouble()).isGreaterThan(1_000_000);               // the largest member notional, read from the data
        assertThat(slider.path("step").asText()).isEqualTo("1000000");
        assertThat(slider.path("range").asBoolean()).isFalse();
        assertThat(slider.path("value").asText()).isEqualTo("0");
        assertThat(slider.path("reads")).extracting(JsonNode::asText).contains("trades");
        int all = panel(view, "trades").path("data").path("rows").size();
        JsonNode high = read(NETTING, "full", "ctl.minNotional", slider.path("max").asText());
        int few = panel(high, "trades").path("data").path("rows").size();
        assertThat(few).isBetween(1, all - 1);
        assertThat(control(high, "minNotional").path("value").asText()).isEqualTo(slider.path("max").asText());
        assertThat(control(read(NETTING, "full", "ctl.minNotional", "-5"), "minNotional").path("value").asText()).isEqualTo("0");
        assertThat(control(read(NETTING, "full", "ctl.minNotional", "123456789012"), "minNotional").path("value").asText()).isEqualTo(slider.path("max").asText());
        assertThat(panel(read(NETTING, "full", "ctl.minNotional", "not-a-number"), "trades").path("data").path("rows")).hasSize(all);
    }

    @Test
    void optionsNeverListAMaskedValueToAReaderWithoutRaw() throws Exception {
        String yaml = """
                rachana: 1
                sutra: ctl-mask
                version: 1
                match: { kind: deal }
                controls:
                  - { id: cp, kind: dropdown, options: "$.trades[*].counterparty" }
                  - { id: prod, kind: dropdown, options: "$.trades[*].product" }
                panels:
                  - id: t
                    kind: table
                    rows: $.trades
                    where: "ctl.cp == '' || @.counterparty == ctl.cp"
                    columns: [{ label: Product, bind: "@.product" }]
                """;
        String doc = "{\"trades\":[{\"counterparty\":\"ACME\",\"product\":\"Swap\"},{\"counterparty\":\"GLOBEX\",\"product\":\"Cap\"}]}";
        String body = json.writeValueAsString(java.util.Map.of("yaml", yaml, "kind", "deal", "document", json.readTree(doc)));
        for (String role : List.of("masked", "full")) {
            String token = "Bearer " + tokens.mint("u-" + role, List.of(role), 300);
            JsonNode view = json.readTree(mvc.perform(post("/api/v1/studio/preview").param("ctl.cp", "ACME").header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8));
            assertThat(values(control(view, "prod"))).containsExactly("Swap", "Cap");
            if (role.equals("masked")) {
                assertThat(values(control(view, "cp"))).isEmpty();
                assertThat(view.toString()).doesNotContain("ACME", "GLOBEX");
                assertThat(control(view, "cp").path("value").asText()).isEmpty();           // the value asked for is not an option: dropped
                assertThat(panel(view, "t").path("data").path("rows")).hasSize(2);
            } else {
                assertThat(values(control(view, "cp"))).containsExactly("ACME", "GLOBEX");
                assertThat(control(view, "cp").path("value").asText()).isEqualTo("ACME");
                assertThat(panel(view, "t").path("data").path("rows")).hasSize(1);
            }
        }
        assertThat(MASK).isNotEmpty();
    }

    @Test
    void aMaskedConditionNeverMatchesARow() throws Exception {
        String yaml = """
                rachana: 1
                sutra: ctl-mask2
                version: 1
                match: { kind: deal }
                controls:
                  - { id: q, kind: search }
                panels:
                  - id: t
                    kind: table
                    rows: $.trades
                    where: "contains(@.counterparty, ctl.q)"
                    columns: [{ label: Product, bind: "@.product" }]
                """;
        String body = json.writeValueAsString(java.util.Map.of("yaml", yaml, "kind", "deal",
                "document", json.readTree("{\"trades\":[{\"counterparty\":\"ACME\",\"product\":\"Swap\"}]}")));
        String token = "Bearer " + tokens.mint("u-masked", List.of("masked"), 300);
        JsonNode view = json.readTree(mvc.perform(post("/api/v1/studio/preview").param("ctl.q", "ACM").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(panel(view, "t").path("data").path("rows")).isEmpty();                   // a search cannot probe a masked field
    }
}
