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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The quick wins of docs/architecture/COMPUTED_ATTRIBUTES.md (2.9.7) on the packs' samples: the derived kinds nset-mtm and cpty-mtm (net MtM,
 * gross parts, live trades, netting benefit, uncollateralised exposure, by product group) equal to the stored totals they will replace, shown
 * on the netting-set and counterparty pages beside the stored figures; stress-scenario-pnl adding the desks' results with each desk's limit
 * use against its positive limit (F5); and the "check" panels of the collateral balance, credit limit, SA-CCR and stress-result pages.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=market-risk,counterparty-risk"})
@AutoConfigureMockMvc
class ComputedAttributesPagesTest {

    static final Path PACKS = Path.of("").toAbsolutePath().getParent().resolve("config/packs");
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;

    private JsonNode raw(String kind, String id) throws Exception {
        return JSON.readTree(mvc.perform(get("/api/v1/entities/" + kind + "/" + id + "/raw")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private JsonNode panel(String view, String id) throws Exception {
        JsonNode v = JSON.readTree(mvc.perform(get("/api/v1/views/" + view)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        for (JsonNode p : v.path("panels")) {
            if (p.path("id").asText().equals(id)) {
                return p;
            }
        }
        throw new AssertionError(view + " has no panel " + id);
    }

    private static List<JsonNode> samples(String pack, String kind) throws Exception {
        return Files.readAllLines(PACKS.resolve(pack).resolve("samples").resolve(kind + ".jsonl")).stream().filter(l -> !l.isBlank()).map(l -> {
            try {
                return JSON.readTree(l);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    @Test
    void theNettingSetsTotalsDerivedFromTheTradeValuationsEqualTheStoredOnes() throws Exception {
        JsonNode stored = samples("counterparty-risk", "netting-set").stream().filter(n -> n.path("NETTING_SET_ID").asText().equals("NS-ALDERSHOT-FRA"))
                .findFirst().orElseThrow();
        JsonNode d = raw("nset-mtm", "NS-ALDERSHOT-FRA");
        assertThat(d.path("NET_MTM").asDouble()).isEqualTo(stored.path("NET_MTM").asDouble());
        assertThat(d.path("TRADE_COUNT").asInt()).isEqualTo(stored.path("TRADE_COUNT").asInt());
        double pos = 0;
        double neg = 0;
        for (JsonNode t : d.path("TRADES")) {
            if (t.path("LIVE").asBoolean()) {
                pos += Math.max(t.path("MTM").asDouble(), 0);
                neg += Math.min(t.path("MTM").asDouble(), 0);
            }
        }
        assertThat(d.path("GROSS_POSITIVE_MTM").asDouble()).isEqualTo(pos);
        assertThat(d.path("GROSS_NEGATIVE_MTM").asDouble()).isEqualTo(neg);
        assertThat(d.path("CE_UNCOLLATERALISED").asDouble()).isEqualTo(Math.max(stored.path("NET_MTM").asDouble(), 0));
        assertThat(d.path("NETTING_BENEFIT").asDouble()).isEqualTo(pos - Math.max(stored.path("NET_MTM").asDouble(), 0));
        assertThat(d.path("CURRENCY_NOTE").asText()).startsWith("USD");
        assertThat(d.path("BY_PRODUCT_GROUP").get(0).path("label").asText()).isNotBlank();
        assertThat(panel("netting-set/NS-ALDERSHOT-FRA", "computed").path("empty").asBoolean()).isFalse();
        assertThat(panel("netting-set/NS-ALDERSHOT-FRA", "byProductGroup").path("empty").asBoolean()).isFalse();
        assertThat(panel("netting-set/NS-ALDERSHOT-FRA", "computed").toString()).doesNotContain("\"error\"");
        // every sample netting set reconciles: the stored net MtM is the derived one plus the trades past their maturity date on the page's
        // business date (Live, decision 3: a live status past maturity is in no derived total, the stored figure still counts it)
        // (a set may differ where the stored copy disagrees with the valuations: a member whose valuation's status is not live, F11)
        List<JsonNode> sets = samples("counterparty-risk", "netting-set");
        int reconciled = 0;
        for (JsonNode s : sets) {
            JsonNode c = raw("nset-mtm", s.path("NETTING_SET_ID").asText());
            double served = 0;
            for (JsonNode t : c.path("TRADES")) {
                if (t.path("LIVE").asBoolean()) {
                    served += raw("trade-valuation", "TVAL-" + t.path("TRADE_ID").asText()).path("MTM").asDouble();
                }
            }
            assertThat(c.path("NET_MTM").asDouble()).as(s.path("NETTING_SET_ID").asText()).isEqualTo(served);     // the valuations as served
            if (c.path("NET_MTM").asDouble() + c.path("PAST_MATURITY_MTM").asDouble() == s.path("NET_MTM").asDouble()) {
                reconciled++;
            }
        }
        assertThat(reconciled).isGreaterThanOrEqualTo(sets.size() * 9 / 10);
    }

    @Test
    void theCounterpartysTotalsAddItsSetsAndTheExposureNeverNetsAcrossThem() throws Exception {
        JsonNode stored = samples("core", "counterparty").stream().filter(n -> n.path("COUNTERPARTY_ID").asText().equals("CP-ALDERSHOT"))
                .findFirst().orElseThrow();
        JsonNode d = raw("cpty-mtm", "CP-ALDERSHOT");
        assertThat(d.path("NET_MTM").asDouble()).isEqualTo(stored.path("NET_MTM").asDouble());
        double ce = 0;
        for (JsonNode s : stored.path("NETTING_SETS")) {
            ce += Math.max(s.path("NET_MTM").asDouble(), 0);
        }
        assertThat(d.path("CE_UNCOLLATERALISED").asDouble()).isEqualTo(ce);
        assertThat(d.path("NETTING_SET_COUNT").asInt()).isEqualTo(stored.path("NETTING_SETS").size());
        assertThat(panel("counterparty/CP-ALDERSHOT", "computed").path("empty").asBoolean()).isFalse();
        assertThat(panel("counterparty/CP-ALDERSHOT", "computedSets").path("empty").asBoolean()).isFalse();
    }

    @Test
    void theBanksStressPnlAddsTheDesksAndEachDeskHasItsLimitUse() throws Exception {
        List<JsonNode> results = samples("market-risk", "stress-result");
        Map<String, Double> bySc = results.stream().collect(Collectors.groupingBy(r -> r.path("SCENARIO").asText(),
                Collectors.summingDouble(r -> r.path("PNL").asDouble())));
        assertThat(results).allSatisfy(r -> assertThat(r.path("LIMIT").asDouble()).isPositive());            // F5: every limit positive
        String scn = bySc.keySet().iterator().next();
        JsonNode d = raw("stress-scenario-pnl", scn);
        assertThat(d.path("PNL").asDouble()).isEqualTo(bySc.get(scn));
        long breaches = 0;
        for (JsonNode r : results) {
            if (r.path("SCENARIO").asText().equals(scn) && Math.max(-r.path("PNL").asDouble(), 0) / r.path("LIMIT").asDouble() > 1) {
                breaches++;
            }
        }
        assertThat(d.path("BREACHES").asLong()).isEqualTo(breaches);
        assertThat(panel("stress-scenario/" + scn, "bank").path("empty").asBoolean()).isFalse();
        assertThat(panel("stress-scenario/" + scn, "desks").path("empty").asBoolean()).isFalse();
    }

    @Test
    void theCheckPanelsShowOnTheirPages() throws Exception {
        String str = samples("market-risk", "stress-result").get(0).path("RESULT_ID").asText();
        for (String view : List.of("collateral-balance/COLL-ALDERSHOT-FRA", "credit-limit/LIM-ALDERSHOT", "sa-ccr/SACCR-ALDERSHOT-FRA",
                "stress-result/" + str)) {
            JsonNode p = panel(view, "checks");
            assertThat(p.path("empty").asBoolean()).as(view).isFalse();
            assertThat(p.toString()).as(view).doesNotContain("\"error\"");
        }
    }
}
