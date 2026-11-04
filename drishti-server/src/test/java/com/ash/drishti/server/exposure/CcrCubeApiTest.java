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
package com.ash.drishti.server.exposure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The CCR cube (EXPOSURE_GUIDE.md section 30) over the exposure engine's tiny lake whose paths are known by hand (ExposureLakeFixture), with
 * two tenor buckets, (0, 0.3] holding t = 0.2 and (0.3, 1] holding t = 0.4 and 1.0, PFE at 75% (the third of four paths) and CP-1's stored
 * limit of 2.2m. By hand, after NS-X's CSA (thresholds 1m, lagged): NS-X's exposures are {2,0,1,1}, {3,1,2,0}, {1,0,2,0} (m) at the three
 * dates and NS-Y's {1,0,0,0}, {0,2,0,0}, {0,0,1,0}; CP-1 (both) is their sum per path, {3,0,1,1}, {3,3,2,0}, {1,0,3,0}: PFE 75% 1, 3, 1, so
 * the peak is 3m at t = 0.4, more than NS-X's peak 2m plus NS-Y's 0 (the total is the quantile of the summed paths, never a sum of peaks);
 * EPE 1.25m = NS-X's 0.95m + NS-Y's 0.30m (EPE adds); in the buckets 1m and 3m, a use of 1/2.2 and 3/2.2 (a breach), headroom −0.8m.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.ccr-lake.plugin=delta",
        "drishti.sources.connectors.ccr-lake.kinds[0]=simulation-run", "drishti.sources.connectors.ccr-lake.kinds[1]=exposure-path",
        "drishti.sources.connectors.ccr-lake.kinds[2]=netting-set", "drishti.sources.connectors.ccr-lake.kinds[3]=csa",
        "drishti.sources.connectors.ccr-lake.kinds[4]=collateral-balance", "drishti.sources.connectors.ccr-lake.kinds[5]=counterparty",
        "drishti.sources.connectors.ccr-lake.kinds[6]=counterparty-group", "drishti.sources.connectors.ccr-lake.kinds[7]=credit-limit",
        "drishti.sources.connectors.ccr-lake.settings.domain=risk",
        "drishti.sources.connectors.ccr-lake.settings.refresh-seconds=1",
        "drishti.sources.connectors.ccr-lake.settings.layout.exposure-path.columns=TRADE_ID,NETTING_SET,COUNTERPARTY,LEGAL_ENTITY,DESK,TT_BOOK,"
                + "ASSET_CLASS,PRODUCT,SOURCE_SYSTEM,MATURITY_DATE,NOTIONAL,GRID_INDEX,GRID_DATE,RUN_ID,BATCH_ID,STATUS",
        "drishti.sources.connectors.ccr-lake.settings.layout.exposure-path.vectors=MTM",
        "drishti.sources.routes.simulation-run=ccr-lake", "drishti.sources.routes.exposure-path=ccr-lake",
        "drishti.sources.routes.netting-set=ccr-lake", "drishti.sources.routes.csa=ccr-lake", "drishti.sources.routes.collateral-balance=ccr-lake",
        "drishti.sources.routes.counterparty=ccr-lake", "drishti.sources.routes.counterparty-group=ccr-lake",
        "drishti.sources.routes.credit-limit=ccr-lake",
        "drishti.ccr.exposure.official.on-start=false", "drishti.ccr.exposure.close-grace-seconds=0",
        "drishti.ccr.exposure.limit-confidence=0.75",
        "drishti.var.official.on-start=false",
        "drishti.engines.ccr-cube.enabled=true", "drishti.engines.fva.enabled=true", "drishti.ccr.fva.funding-spread=0.005",
        "drishti.ccr.fva.discount-rate=0", "drishti.ccr.fva.require-risk-neutral=false",
        "drishti.cube.ccr.buckets.SHORT=0-0.3", "drishti.cube.ccr.buckets.LONG=0.3-1",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.security.roles.agg.kinds[0]=*", "drishti.security.roles.agg.raw=true", "drishti.ccr.exposure.access.agg[CP-1]=aggregate",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class CcrCubeApiTest {

    static final double M = 1_000_000;
    static final Offset<Double> CENT = Offset.offset(0.005);

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        Path lake = Files.createDirectories(Path.of("target", "ccr-cube-lake-" + System.nanoTime())).toAbsolutePath();
        ExposureLakeFixture.write(lake);
        r.add("drishti.sources.connectors.ccr-lake.settings.root", lake::toString);
        r.add("drishti.ccr.exposure.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    private String as(String role) {
        return "Bearer " + tokens.mint("rita", List.of(role), 300);
    }

    private JsonNode call(String auth, MockHttpServletRequestBuilder r, Object body, int expect) throws Exception {
        if (body != null) {
            r = r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult res = mvc.perform(r.header("Authorization", auth)).andReturn();
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(expect);
        String t = res.getResponse().getContentAsString();
        return t.isEmpty() ? json.createObjectNode() : json.readTree(t);
    }

    private static Map<String, Object> view(List<String> rows, List<String> cols, Map<String, String> values) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("source", "ccr");
        v.put("date", ExposureLakeFixture.DAY.toString());
        v.put("rows", rows);
        v.put("columns", cols);
        List<Map<String, String>> vs = new ArrayList<>();
        values.forEach((m, as) -> vs.add(Map.of("measure", m, "as", as)));
        v.put("values", vs);
        v.put("options", Map.of("q", "0.75"));
        return v;
    }

    private JsonNode query(String auth, Map<String, Object> view) throws Exception {
        return call(auth, post("/api/v1/pivot/query"), Map.of("view", view), 200);
    }

    private static String key(JsonNode list, String kindOrValue) {
        for (JsonNode e : list) {
            if (kindOrValue.equals(e.path("kind").asText()) || kindOrValue.equals(e.path("value").asText())) {
                return e.get("key").asText();
            }
        }
        throw new AssertionError("no " + kindOrValue + " in " + list);
    }

    private static JsonNode cell(JsonNode a, String row, String col) {
        return a.get("cells").get(key(a.get("rows"), row) + "|" + key(a.get("columns"), col));
    }

    @Test
    void theCatalogueOffersTheDimensionsTheMeasuresAndLandingA() throws Exception {
        JsonNode cat = call(as("risk"), get("/api/v1/pivot/catalogue").param("source", "ccr").param("date", ExposureLakeFixture.DAY.toString()), null, 200);
        assertThat(cat.toString()).contains("TENOR_BUCKET").contains("COUNTERPARTY").contains("NETTING_SET").contains("pfe").contains("limit_use")
                .contains("fva").contains("defaultView");
    }

    @Test
    void aCounterpartysPeakPfeIsTheQuantileOfItsSummedPathsNeverASumOfPeaks() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("pfe()", "PFE");
        values.put("epe()", "EPE");
        values.put("nettingSets()", "Sets");
        JsonNode a = query(as("risk"), view(List.of("COUNTERPARTY", "NETTING_SET"), List.of(), values));
        JsonNode cp = cell(a, "CP-1", "grand");
        assertThat(cp.at("/values/PFE").asDouble()).isCloseTo(3 * M, CENT);
        assertThat(cp.at("/values/EPE").asDouble()).isCloseTo(1.25 * M, CENT);
        assertThat(cp.at("/values/Sets").asInt()).isEqualTo(2);
        assertThat(cp.get("scope").asText()).isEqualTo("CPTY:CP-1");
        JsonNode bank = cell(a, "grand", "grand");
        assertThat(bank.at("/values/PFE").asDouble()).isCloseTo(3 * M, CENT);                    // the bank is CP-1's two sets here
        JsonNode exp = call(as("risk"), get("/api/v1/exposure/scope").param("scope", "CPTY:CP-1").param("q", "0.75")
                .param("asOf", ExposureLakeFixture.DAY.toString()), null, 200);
        assertThat(exp.toString()).contains("3000000");                                             // the explorer's own figure for the scope
    }

    @Test
    void theLimitBoardByTenorBucket() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("pfe()", "PFE");
        values.put("limit_use()", "Use");
        values.put("headroom()", "Headroom");
        values.put("epe()", "EPE");
        JsonNode a = query(as("risk"), view(List.of("COUNTERPARTY"), List.of("TENOR_BUCKET"), values));
        JsonNode shortB = cell(a, "CP-1", "SHORT");
        JsonNode longB = cell(a, "CP-1", "LONG");
        assertThat(shortB.at("/values/PFE").asDouble()).isCloseTo(1 * M, CENT);
        assertThat(longB.at("/values/PFE").asDouble()).isCloseTo(3 * M, CENT);
        assertThat(shortB.at("/values/Use").asDouble()).isCloseTo(1 / 2.2, Offset.offset(1e-9));
        assertThat(longB.at("/values/Use").asDouble()).isCloseTo(3 / 2.2, Offset.offset(1e-9));
        assertThat(longB.at("/values/EPE").isNull()).as("EPE is a whole-life figure: not by bucket").isTrue();
        JsonNode row = cell(a, "CP-1", "grand");
        assertThat(row.at("/values/Headroom").asDouble()).isCloseTo(-0.8 * M, CENT);
        assertThat(row.at("/values/EPE").asDouble()).isCloseTo(1.25 * M, CENT);
    }

    @Test
    void theHeadlineNamesTheBreachAndTheFanCarriesTheLimitLine() throws Exception {
        Map<String, Object> v = view(List.of("COUNTERPARTY"), List.of("TENOR_BUCKET"), Map.of("pfe()", "PFE"));
        JsonNode h = call(as("risk"), post("/api/v1/pivot/ccr/headline"), Map.of("view", v), 200);
        assertThat(h.get("peakPfe").asDouble()).isCloseTo(3 * M, CENT);
        assertThat(h.get("epe").asDouble()).isCloseTo(1.25 * M, CENT);
        assertThat(h.get("overCount").asInt()).isEqualTo(1);
        assertThat(h.at("/over/0/counterparty").asText()).isEqualTo("CP-1");
        assertThat(h.at("/over/0/bucket").asText()).isEqualTo("LONG");
        JsonNode f = call(as("risk"), get("/api/v1/pivot/ccr/fan").param("scope", "CPTY:CP-1").param("q", "0.75")
                .param("asOf", ExposureLakeFixture.DAY.toString()), null, 200);
        assertThat(f.get("points")).hasSize(3);
        assertThat(f.at("/points/1/pfe").asDouble()).isCloseTo(3 * M, CENT);
        assertThat(f.at("/points/1/limit").asDouble()).isCloseTo(2.2 * M, CENT);
        assertThat(f.get("minHeadroom").asDouble()).isCloseTo(-0.8 * M, CENT);
        assertThat(f.at("/points/0/bucket").asText()).isEqualTo("SHORT");
    }

    @Test
    void eadAndFvaAreTheirEnginesSumsAndAnAggregateViewerGetsOnlyTheBank() throws Exception {
        JsonNode a = query(as("risk"), view(List.of("NETTING_SET"), List.of(), Map.of("fva()", "FVA")));
        assertThat(cell(a, "NS-X", "grand").at("/values/FVA").asDouble()).isCloseTo(-1_750.00, CENT);   // FvaApiTest's figures by hand
        assertThat(cell(a, "grand", "grand").at("/values/FVA").asDouble()).isCloseTo(-3_250.00, CENT);
        MvcResult refused = mvc.perform(post("/api/v1/pivot/query").header("Authorization", as("agg")).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("view", view(List.of("NETTING_SET"), List.of(), Map.of("pfe()", "PFE")))))).andReturn();
        assertThat(refused.getResponse().getStatus()).as("a dimension refused to a viewer of the sets in aggregate").isIn(400, 403);
        assertThat(refused.getResponse().getContentAsString()).contains("NETTING_SET");
        JsonNode total = query(as("agg"), view(List.of(), List.of(), Map.of("pfe()", "PFE")));
        assertThat(total.get("cells").size()).isEqualTo(1);
    }

    @Test
    void anUnknownOptionIsRefusedInWords() throws Exception {
        Map<String, Object> v = view(List.of("COUNTERPARTY"), List.of(), Map.of("pfe()", "PFE"));
        v.put("options", Map.of("horizon", "10d"));
        JsonNode x = call(as("risk"), post("/api/v1/pivot/query"), Map.of("view", v), 400);
        assertThat(x.toString()).contains("not an option of the CCR cube");
    }
}
