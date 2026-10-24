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

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The us-equities pack loads beside the packs it links to and draws its screens from the committed public data: every example it
 * advertises opens a view built by its Sutra with every panel filled and no missing link; a click on a company's filing opens the
 * filing beside the table with its EDGAR links; a click on an index member opens the company's summary; the price kind ships no data
 * and its connector is off unless a site switches it on.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=core,world-economy,news-and-markets,us-equities",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true"})
@AutoConfigureMockMvc
class UsEquitiesPackTest {

    static final Path PACK = Path.of("../config/packs/us-equities");

    @Autowired ViewPipeline pipeline;
    @Autowired com.ash.drishti.engine.command.CommandParser commands;
    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    private final ObjectMapper json = new ObjectMapper();

    private String auth() {
        return "Bearer " + tokens.mint("u-analyst", List.of("full"), 300);
    }

    private JsonNode view(String kind, String id) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/views/" + kind + "/" + id).header("Authorization", auth())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode click(String kind, String id, String panel, int row) throws Exception {
        String body = json.writeValueAsString(Map.of("panel", panel, "event", "open", "row", row, "selections", List.of()));
        return json.readTree(mvc.perform(post("/api/v1/views/" + kind + "/" + id + "/events").header("Authorization", auth())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
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
    void everyAdvertisedExampleOpensAFullViewByItsSutra() throws Exception {
        JsonNode manifest = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(PACK.resolve("pack.yaml")));
        assertThat(manifest.path("console").path("examples")).isNotEmpty();
        for (JsonNode ex : manifest.path("console").path("examples")) {
            EntityRef ref = commands.require(ex.get(0).asText());
            ViewModel v = pipeline.view(ref);
            assertThat(v.provenance().layout()).as(ref.toString()).startsWith("Sutra ");
            assertThat(v.panels()).as(ref.toString()).allSatisfy(p -> assertThat(p.empty()).as(ref + "/" + p.id()).isFalse());
            assertThat(v.panels().stream().filter(p -> p.data() instanceof PanelData.Links)
                    .flatMap(p -> ((PanelData.Links) p.data()).links().stream()).toList())
                    .as(ref + " links").allSatisfy(l -> assertThat(l.status()).as(ref + " -> " + l.text()).isNotEqualTo("missing"));
        }
    }

    @Test
    void aCompanyPageHasItsFinancialsFilingsKeyFactsAndIndexes() throws Exception {
        JsonNode v = view("us-company", "AAPL");
        assertThat(v.path("provenance").path("sutra").asText()).isEqualTo("us-company");
        assertThat(panel(v.path("panels"), "financials").path("data").path("rows").size()).isGreaterThanOrEqualTo(5);
        assertThat(panel(v.path("panels"), "financials-chart").path("data").path("series").size()).isEqualTo(2);
        assertThat(panel(v.path("panels"), "filings").path("data").path("rows").size()).isGreaterThanOrEqualTo(10);
        assertThat(field(panel(v.path("panels"), "card"), "CIK")).isEqualTo("0000320193");
        assertThat(field(panel(v.path("panels"), "card"), "Indexes")).contains("S&P 500").contains("Nasdaq-100");
        assertThat(v.toString()).doesNotContain("\"picked-filing\"");                  // the filing opens on a click, not before
    }

    @Test
    void aClickOnAFilingOpensItBesideWithItsEdgarLinks() throws Exception {
        JsonNode r = click("us-company", "AAPL", "filings", 0);
        assertThat(r.path("selection").path("payload").path("id").asText()).matches("\\d{10}-\\d{2}-\\d{6}");
        JsonNode kids = r.path("children").get(0);
        assertThat(kids.path("place").asText()).isEqualTo("detail-right");
        JsonNode card = kids.path("panels").get(0);
        assertThat(card.path("id").asText()).isEqualTo("picked-filing");
        assertThat(field(card, "Open on EDGAR")).startsWith("https://www.sec.gov/Archives/edgar/data/320193/");
        assertThat(field(card, "EDGAR filing index")).endsWith("-index.htm");
    }

    @Test
    void aClickOnAnIndexMemberOpensTheCompanyBeside() throws Exception {
        JsonNode v = view("index-membership", "DJIA");
        JsonNode rows = panel(v.path("panels"), "members").path("data").path("rows");
        assertThat(rows.size()).isBetween(25, 40);
        String ticker = rows.get(0).path("cells").get(0).path("text").asText();
        JsonNode r = click("index-membership", "DJIA", "members", 0);
        JsonNode kids = r.path("children").get(0);
        assertThat(kids.path("panels").get(0).path("id").asText()).isEqualTo("member");
        assertThat(field(kids.path("panels").get(0), "Indexes")).contains("Dow Jones");
        assertThat(kids.path("panels").get(0).path("title").asText()).isEqualTo(ticker);
    }

    @Test
    void theSectorDirectoryAndTheFilingsBrowserOpen() throws Exception {
        assertThat(view("sic-sector", "ALL").path("provenance").path("sutra").asText()).isEqualTo("sic-directory");
        assertThat(view("sic-sector", "3674").path("provenance").path("sutra").asText()).isEqualTo("sic-sector");
        JsonNode feed = view("filing-feed", "8-K");
        assertThat(panel(feed.path("panels"), "feed").path("data").path("rows").size()).isPositive();
        JsonNode r = click("filing-feed", "8-K", "feed", 0);
        assertThat(field(r.path("children").get(0).path("panels").get(0), "Form")).isEqualTo("8-K");
    }

    @Test
    void pricesShipNoDataAndTheirConnectorIsOffUnlessASiteSwitchesItOn() throws Exception {
        JsonNode manifest = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(PACK.resolve("pack.yaml")));
        JsonNode c = manifest.path("connectors").path("us-equities-prices");
        assertThat(c.path("enabled").asText()).isEqualTo("${DRISHTI_US_EQUITIES_PRICES_ENABLED:false}");
        assertThat(c.path("kinds").get(0).asText()).isEqualTo("price-history");
        assertThat(manifest.path("routes").path("price-history").asText()).isEqualTo("us-equities-prices");
        try (var files = Files.list(PACK.resolve("samples"))) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith("price-history"));
        }
        assertThat(Files.readString(PACK.resolve("samples/catalog.json"))).doesNotContain("\"price-history\"");
    }
}
