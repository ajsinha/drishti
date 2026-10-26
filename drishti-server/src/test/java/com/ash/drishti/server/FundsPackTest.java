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

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.view.ViewModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The funds pack loads beside us-equities and gleif and draws its pages from the committed SEC snapshot read from the lake (the demo
 * connector off; the fund kind in gzipped parts): every advertised example opens a full view by its Sutra; FUND QQQ has the fact sheet
 * and the two tabs, Look-through holdings (holdings linked to their us-equities companies, the derivatives with counterparties, who
 * else holds the largest company) and the ETF against the Nasdaq-100; HELD AAPL lists the funds holding Apple, and USEQ AAPL shows
 * them in its Held by funds tab; IDX SP500 lists the ETFs tracking it; the type-ahead finds a fund by any share class ticker.
 */
class FundsPackTest extends PublicPacksOnLakes {

    static final Path PACK = Path.of("../config/packs/funds");

    @Autowired ViewPipeline pipeline;
    @Autowired com.ash.drishti.engine.command.CommandParser commands;

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
            // a bond fund holds no us-equities company, so it has no linked entity (the refs panel) to show
            assertThat(v.panels()).as(ref.toString()).filteredOn(p -> !"refs".equals(p.id()))
                    .allSatisfy(p -> assertThat(p.empty()).as(ref + "/" + p.id()).isFalse());
        }
    }

    @Test
    void theFundPageIsTheFactSheetWithTheLookThroughAndIndexTabs() throws Exception {
        JsonNode v = view("fund", "QQQ");
        assertThat(v.path("provenance").path("sutra").asText()).isEqualTo("fund");
        JsonNode panels = v.path("panels");
        assertThat(panel(panels, "net-assets").path("data").path("value").path("text").asText()).endsWith("bn");
        JsonNode top = panel(panels, "top-holdings").path("data").path("rows");
        assertThat(top.size()).isEqualTo(10);
        assertThat(top.get(0).path("cells").get(0).path("link").path("kind").asText()).isEqualTo("us-company");
        assertThat(field(panel(panels, "the-fund"), "Index")).isEqualTo("Nasdaq-100");
        JsonNode tabs = panel(panels, "views").path("data").path("tabs");
        assertThat(tabs.size()).isEqualTo(2);
        assertThat(tabs.get(0).path("title").asText()).isEqualTo("Look-through holdings");
        assertThat(tabs.get(1).path("title").asText()).isEqualTo("vs Nasdaq-100");
        assertThat(tabs.get(0).toString()).contains("\"holdings\"").contains("\"derivatives\"").contains("\"also-held-by\"");
        assertThat(tabs.get(1).toString()).contains("\"coverage\"").contains("\"weights\"").contains("\"sector-tilt\"")
                .contains("market capitalisation computed from SEC data");
    }

    @Test
    void aCompanyListsTheFundsThatHoldItAndUseqShowsThemInATab() throws Exception {
        JsonNode held = view("fund-holder", "AAPL");
        assertThat(held.path("provenance").path("sutra").asText()).isEqualTo("fund-holder");
        assertThat(panel(held.path("panels"), "holders").path("data").path("rows").size()).isGreaterThan(20);
        JsonNode useq = view("us-company", "AAPL");
        JsonNode tab = panel(useq.path("panels"), "held-by-funds");
        assertThat(tab.path("data").path("rows").size()).isGreaterThan(20);
        assertThat(tab.path("data").path("rows").get(0).path("cells").get(0).path("link").path("kind").asText()).isEqualTo("fund");
        assertThat(field(panel(useq.path("panels"), "fund-ownership"), "Funds holding it")).contains("of the 200 largest");
    }

    @Test
    void anIndexListsTheEtfsTrackingIt() throws Exception {
        JsonNode idx = view("index-membership", "SP500");
        JsonNode rows = panel(idx.path("panels"), "tracked-by").path("data").path("rows");
        assertThat(rows.size()).isGreaterThanOrEqualTo(5);
        assertThat(view("index-funds", "SP500").path("provenance").path("sutra").asText()).isEqualTo("index-funds");
    }

    @Test
    void theTypeAheadFindsAFundByAnyShareClassTicker() throws Exception {
        assertThat(suggested("FUND VTSAX")).contains("VTI");
        assertThat(suggested("FUND QQQ")).contains("QQQ");
        assertThat(formSearch("funds", "fund", "{'path':'INDEX_ID','op':'in','values':['NDX']}".replace('\'', '"'))).contains("QQQ");
    }
}
