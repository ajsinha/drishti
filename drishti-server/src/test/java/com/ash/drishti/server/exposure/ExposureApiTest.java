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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The exposure engine's API over a tiny lake whose answers are known by hand (ExposureLakeFixture: EXP.md section 15's worked example in
 * USD millions, t = 0.2, 0.4, 1.0): a netting set's profile and figures with lagged CSA collateral, a counterparty's two sets summed per
 * path (EE additive, PFE not), a group, a desk cutting a set, a trade alone, contributions adding up, the distribution, a set the run
 * does not cover (said so), entitlements by counterparty and the name mask, captures and compare, CSV, the official record and its
 * published tables, the what-if proxy and the paths method's refusal, the hot file reopened, the computed kind exposure-result and the
 * netting-set page's figures.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.ccr-lake.plugin=delta",
        "drishti.sources.connectors.ccr-lake.kinds[0]=simulation-run", "drishti.sources.connectors.ccr-lake.kinds[1]=exposure-path",
        "drishti.sources.connectors.ccr-lake.kinds[2]=netting-set", "drishti.sources.connectors.ccr-lake.kinds[3]=csa",
        "drishti.sources.connectors.ccr-lake.kinds[4]=collateral-balance", "drishti.sources.connectors.ccr-lake.kinds[5]=counterparty",
        "drishti.sources.connectors.ccr-lake.kinds[6]=counterparty-group",
        "drishti.sources.connectors.ccr-lake.settings.domain=risk",
        "drishti.sources.connectors.ccr-lake.settings.refresh-seconds=1",
        "drishti.sources.connectors.ccr-lake.settings.layout.exposure-path.columns=TRADE_ID,NETTING_SET,COUNTERPARTY,LEGAL_ENTITY,DESK,TT_BOOK,"
                + "ASSET_CLASS,PRODUCT,SOURCE_SYSTEM,MATURITY_DATE,NOTIONAL,GRID_INDEX,GRID_DATE,RUN_ID,BATCH_ID,STATUS",
        "drishti.sources.connectors.ccr-lake.settings.layout.exposure-path.vectors=MTM",
        "drishti.sources.routes.simulation-run=ccr-lake", "drishti.sources.routes.exposure-path=ccr-lake",
        "drishti.sources.routes.netting-set=ccr-lake", "drishti.sources.routes.csa=ccr-lake", "drishti.sources.routes.collateral-balance=ccr-lake",
        "drishti.sources.routes.counterparty=ccr-lake", "drishti.sources.routes.counterparty-group=ccr-lake",
        "drishti.sources.routes.exposure-result=exposure-engine",
        "drishti.ccr.exposure.official.on-start=false", "drishti.ccr.exposure.close-grace-seconds=0", "drishti.var.official.on-start=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact[0]=NAME",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.agg.kinds[0]=*", "drishti.security.roles.agg.raw=true", "drishti.ccr.exposure.access.agg[CP-1]=aggregate",
        "drishti.security.roles.other.kinds[0]=*", "drishti.security.roles.other.raw=true", "drishti.ccr.exposure.access.other[CP-2]=trades",
        "drishti.security.roles.curves.kinds[0]=curve",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExposureApiTest {

    static Path lake;
    static final double M = ExposureLakeFixture.M;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "exposure-lake-" + System.nanoTime())).toAbsolutePath();
        ExposureLakeFixture.write(lake);
        r.add("drishti.sources.connectors.ccr-lake.settings.root", lake::toString);
        r.add("drishti.ccr.exposure.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired ExposureService service;
    @Autowired ExposureFigures figures;
    @Autowired com.ash.drishti.packs.ConnectorFiles connectors;
    @Autowired com.ash.drishti.server.connectors.ConnectorManager manager;
    final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode get(String url, String auth, String... params) throws Exception {
        var req = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).header("Authorization", auth)
                .param("asOf", ExposureLakeFixture.DAY.toString());
        for (int i = 0; i < params.length; i += 2) {
            req.param(params[i], params[i + 1]);
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode scope(String scope, String... more) throws Exception {
        String[] p = new String[more.length + 4];
        p[0] = "scope";
        p[1] = scope;
        p[2] = "q";
        p[3] = "0.75";
        System.arraycopy(more, 0, p, 4, more.length);
        return get("/api/v1/exposure/scope", as("rita", "risk"), p);
    }

    @Test
    @Order(1)
    void theWorkedExampleOfTheContract() throws Exception {
        JsonNode x = scope("NSET:NS-X");
        assertThat(x.get("kind").asText()).isEqualTo("netting-set");
        assertThat(x.get("label").asText()).isEqualTo("NS-X · Alpha Fund");
        assertThat(x.get("runId").asText()).isEqualTo("XR-2026-09-30-01");
        JsonNode prof = x.get("profile");
        assertThat(prof).hasSize(3);
        assertThat(List.of(prof.get(0).get("ee").asLong(), prof.get(1).get("ee").asLong(), prof.get(2).get("ee").asLong()))
                .containsExactly(1_000_000L, 1_500_000L, 750_000L);
        assertThat(List.of(prof.get(0).get("pfe").asLong(), prof.get(1).get("pfe").asLong(), prof.get(2).get("pfe").asLong()))
                .containsExactly(1_000_000L, 2_000_000L, 1_000_000L);
        assertThat(prof.get(2).get("eee").asLong()).isEqualTo(1_500_000L);
        assertThat(prof.get(1).get("eeUncoll").asLong()).isEqualTo(2_250_000L);
        assertThat(prof.get(2).get("ene").asLong()).isEqualTo(-750_000L);
        assertThat(x.get("epe").asLong()).isEqualTo(950_000L);                    // 1.0 x 0.2 + 1.5 x 0.2 + 0.75 x 0.6
        assertThat(x.get("eepe").asLong()).isEqualTo(1_400_000L);                 // 1.0 x 0.2 + 1.5 x 0.2 + 1.5 x 0.6
        assertThat(x.get("peakPfe").asLong()).isEqualTo(2_000_000L);
        assertThat(x.get("peakPfeDate").asText()).isEqualTo(ExposureLakeFixture.DAY.plusDays(146).toString());
        assertThat(x.get("eadImm").asLong()).isEqualTo(1_960_000L);
        assertThat(x.get("ce").asLong()).isZero();
        assertThat(x.at("/provenance/intake/rejected").asInt()).isEqualTo(1);
        assertThat(x.at("/provenance/intake/rejections").toString()).contains("DRS-5444 C #1: the vector has 3 values");
        assertThat(x.at("/provenance/intake/otherRuns").asInt()).isEqualTo(1);
        assertThat(x.at("/provenance/intake/setsWithoutPaths").asInt()).isEqualTo(1);
        assertThat(x.at("/provenance/tier").asText()).contains("hot file");
        // without collateral the peak doubles: 0, 1, 4, 4 at t2
        assertThat(scope("NSET:NS-X", "collateral", "none").get("peakPfe").asLong()).isEqualTo(4_000_000L);
    }

    @Test
    @Order(2)
    void aCounterpartySumsItsSetsPerPathAndPfeIsNotAdditive() throws Exception {
        JsonNode cp = scope("CPTY:CP-1");
        JsonNode prof = cp.get("profile");
        assertThat(List.of(prof.get(0).get("ee").asLong(), prof.get(1).get("ee").asLong(), prof.get(2).get("ee").asLong()))
                .containsExactly(1_250_000L, 2_000_000L, 1_000_000L);
        assertThat(cp.get("peakPfe").asLong()).isEqualTo(3_000_000L);              // [3, 3, 2, 0] at t2, not 2m + 0
        assertThat(scope("NSET:NS-Y").get("peakPfe").asLong()).isZero();
        assertThat(cp.get("byNettingSet")).hasSize(2);
        assertThat(cp.get("byNettingSet").get(0).get("nettingSet").asText()).isEqualTo("NS-X");
        long sum = 0;
        for (JsonNode r : cp.get("byNettingSet")) {
            sum += r.get("pfeContribution").asLong();
        }
        assertThat(sum).isBetween(2_999_999L, 3_000_001L);
        assertThat(scope("GROUP:GRP-1").get("peakPfe").asLong()).isEqualTo(3_000_000L);
        assertThat(scope("LE:A").get("epe").asLong()).isEqualTo(cp.get("epe").asLong());
        assertThat(scope("BANK").get("eepe").asLong()).isEqualTo(cp.get("eepe").asLong());
        // a desk cuts NS-X: trade A alone with NS-X's CSA, plus NS-Y whole
        JsonNode desk = scope("DESK:A & CPTY:CP-1");
        assertThat(desk.get("flags").toString()).contains("partial");
        assertThat(desk.at("/provenance/partialSets").asInt()).isEqualTo(1);
        // a trade alone: uncollateralised
        JsonNode t = scope("TRD:a");
        assertThat(t.get("flags").toString()).contains("standalone");
        assertThat(t.get("profile").get(1).get("ee").asLong()).isEqualTo(Math.round((5 + 4) / 4.0 * M));
    }

    @Test
    @Order(3)
    void contributionsAddUpAndTheDistributionIsTheExposureAtADate() throws Exception {
        JsonNode c = get("/api/v1/exposure/scope/contributions", as("rita", "risk"), "scope", "NSET:NS-X", "q", "0.75", "figure", "EE", "date",
                ExposureLakeFixture.DAY.plusDays(146).toString());
        assertThat(c.get("by").asText()).isEqualTo("trade");
        long sum = 0;
        for (JsonNode r : c.get("rows")) {
            sum += r.get("value").asLong();
        }
        assertThat(sum).isBetween(1_499_999L, 1_500_001L);
        JsonNode u = get("/api/v1/exposure/scope/contributions", as("rita", "risk"), "scope", "NSET:NS-X", "collateral", "none", "figure", "EE",
                "date", ExposureLakeFixture.DAY.plusDays(146).toString());
        assertThat(u.get("rows").toString()).contains("\"key\":\"A\"").contains("\"value\":2000000").contains("\"value\":250000");
        JsonNode pfe = get("/api/v1/exposure/scope/contributions", as("rita", "risk"), "scope", "CPTY:CP-1", "by", "netting-set", "figure", "PFE");
        assertThat(pfe.get("total").asLong()).isEqualTo(get("/api/v1/exposure/scope", as("rita", "risk"), "scope", "CPTY:CP-1").get("peakPfe").asLong());
        assertThat(pfe.get("rows").get(0).has("incremental")).isTrue();
        JsonNode d = get("/api/v1/exposure/scope/distribution", as("rita", "risk"), "scope", "NSET:NS-X", "q", "0.75", "date", "2026-12-12");
        assertThat(d.get("mean").asLong()).isEqualTo(1_000_000L);
        assertThat(d.get("zeroShare").asDouble()).isEqualTo(0.25);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exposure/scope/distribution").param("scope",
                "NSET:NS-X").param("date", "2026-10-01").header("Authorization", as("rita", "risk"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DRS-5440"));
        String csv = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exposure/scope/profile")
                .param("scope", "NSET:NS-X").param("q", "0.75").param("format", "csv").header("Authorization", as("rita", "risk"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("tenor,date,t,ee,ene,eee,pfe,pfe_99,ee_uncoll\n").contains(",0.4,1500000,-500000,1500000,2000000,");
        JsonNode all = get("/api/v1/exposure/scope/profile", as("rita", "risk"), "scope", "NSET:NS-X", "dates", "all");
        assertThat(all.get("profile")).hasSize(6);
    }

    @Test
    @Order(4)
    void whatIsNotCoveredIsSaidAndBadParametersAreRefused() throws Exception {
        var res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exposure/scope").param("scope",
                "NSET:NS-Z").header("Authorization", as("rita", "risk"))).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertThat(res).contains("no exposure paths for the netting set NS-Z on 2026-09-30");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exposure/scope").param("scope", "NSET:NS-X")
                .param("q", "0.3").header("Authorization", as("rita", "risk"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-5440"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exposure/scope").param("scope", "POS:X")
                .header("Authorization", as("rita", "risk"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-5440"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exposure/scope").param("scope", "NSET:NS-X")
                .param("run", "XR-OTHER").header("Authorization", as("rita", "risk"))).andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exposure/scope").param("scope", "NSET:NS-X")
                .header("Authorization", as("cy", "curves"))).andExpect(status().isForbidden());
    }

    @Test
    @Order(5)
    void entitlementsByCounterpartyAndTheNameMask() throws Exception {
        JsonNode agg = get("/api/v1/exposure/scope", as("ann", "agg"), "scope", "CPTY:CP-1", "q", "0.75");
        assertThat(agg.get("peakPfe").asLong()).isEqualTo(3_000_000L);           // the figures, as everyone sees them
        assertThat(agg.get("byNettingSet")).isEmpty();                            // but no netting set below the counterparty
        JsonNode parts = get("/api/v1/exposure/scope/contributions", as("ann", "agg"), "scope", "CPTY:CP-1", "by", "trade");
        assertThat(parts.get("rows")).isEmpty();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exposure/scope").param("scope", "TRD:A")
                .header("Authorization", as("ann", "agg"))).andExpect(status().isForbidden());
        // a role that covers CP-2 only sees nothing of CP-1: the scope is empty for them
        JsonNode other = get("/api/v1/exposure/scope", as("ola", "other"), "scope", "BANK");
        assertThat(other.at("/provenance/hiddenSets").asInt()).isEqualTo(2);
        assertThat(other.get("epe").asLong()).isZero();
        JsonNode masked = get("/api/v1/exposure/scope", as("mo", "masked"), "scope", "NSET:NS-X");
        assertThat(masked.get("label").asText()).isEqualTo("NS-X · CP-1");
    }

    @Test
    @Order(6)
    void capturesCompareAndCsv() throws Exception {
        String rita = as("rita", "risk");
        JsonNode cap = json.readTree(mvc.perform(post("/api/v1/exposure/captures").header("Authorization", rita).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\": \"CPTY:CP-1\", \"note\": \"before\", \"q\": 0.75}").param("asOf", ExposureLakeFixture.DAY.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String id = cap.get("id").asText();
        assertThat(id).startsWith("XCAP-20260930-");
        assertThat(cap.get("peakPfe").asLong()).isEqualTo(3_000_000L);
        JsonNode back = get("/api/v1/exposure/captures/" + id, rita);
        assertThat(back.get("members").toString()).contains("\"A\"").contains("\"C\"");
        assertThat(back.get("profile")).hasSize(3);
        assertThat(get("/api/v1/exposure/captures", rita, "scope", "CPTY:CP-1")).hasSize(1);
        JsonNode cmp = get("/api/v1/exposure/compare", rita, "a", id, "b", "now");
        assertThat(cmp.at("/change/peakPfe").asDouble()).isZero();
        String csv = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exposure/captures/" + id + "/csv")
                .header("Authorization", rita)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("# id: " + id).contains("# note: before").contains("tenor,date,t,ee");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/exposure/captures/" + id)
                .header("Authorization", as("ola", "other"))).andExpect(status().isNotFound());
    }

    @Test
    @Order(7)
    void theOfficialRecordIsPublishedAndTheWhatIfProxy() throws Exception {
        String ada = as("ada", "admin");
        JsonNode run = json.readTree(mvc.perform(post("/api/v1/exposure/official").header("Authorization", ada).param("asOf",
                ExposureLakeFixture.DAY.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(run.get("status").asText()).as(run.toString()).isEqualTo("captured");
        JsonNode again = json.readTree(mvc.perform(post("/api/v1/exposure/official").header("Authorization", ada).param("asOf",
                ExposureLakeFixture.DAY.toString())).andReturn().getResponse().getContentAsString());
        assertThat(again.get("status").asText()).isEqualTo("unchanged");
        JsonNode rec = get("/api/v1/exposure/official", ada);
        assertThat(rec.at("/scopes/NSET:NS-X/epe").asLong()).isEqualTo(950_000L);
        assertThat(rec.at("/scopes/CPTY:CP-1/peakPfe95").asLong()).isPositive();
        assertThat(rec.at("/scopes/GROUP:GRP-1").isMissingNode()).isFalse();
        assertThat(lake.resolve("risk-results/exposure-official-cpty/_delta_log")).exists();
        assertThat(lake.resolve("risk-results/exposure-official/_delta_log")).exists();
        mvc.perform(post("/api/v1/exposure/official").header("Authorization", as("rita", "risk"))).andExpect(status().isForbidden());
        // the what-if proxy: a candidate of its own, uncollateralised; the paths method is the Python engine's
        String body = "{\"scope\": \"CPTY:CP-1\", \"method\": \"addon\", \"q\": 0.95, \"candidates\": [{\"tradeId\": \"NEW-1\", \"assetClass\": \"FX\","
                + " \"notional\": 10000000, \"maturityDate\": \"2027-09-30\", \"mtm\": 0}]}";
        JsonNode w = json.readTree(mvc.perform(post("/api/v1/exposure/what-if").header("Authorization", as("rita", "risk")).param("asOf",
                ExposureLakeFixture.DAY.toString()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(w.get("flags").toString()).contains("proxy");
        // at 1Y: 1.645 x 0.04 x 10m x sqrt(1) = 658k more PFE at that date
        assertThat(w.at("/incremental/profile/2/incrementalPfe").asLong()).isBetween(657_000L, 659_000L);
        mvc.perform(post("/api/v1/exposure/what-if").header("Authorization", as("rita", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("addon", "paths"))).andExpect(status().is(501)).andExpect(jsonPath("$.code").value("DRS-5446"));
        mvc.perform(post("/api/v1/exposure/what-if").header("Authorization", as("rita", "risk")).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("\"mtm\": 0}", "\"mtm\": 0, \"paths\": {\"runId\": \"XR-OTHER\"}}"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DRS-5442"));
    }

    @Test
    @Order(8)
    void theHotFileOpensAgainAndTheTreeAndStatusSayWhatIsLoaded() throws Exception {
        String ada = as("ada", "admin");
        JsonNode st = get("/api/v1/exposure/status", ada);
        assertThat(st.at("/tier/mode").asText()).isEqualTo("mapped");
        String hot = st.at("/loaded/0/hotFile").asText();
        assertThat(hot).startsWith("2026-09-30/run-").endsWith(".xhot");
        long reopened = st.at("/tier/reopened").asLong();
        mvc.perform(post("/api/v1/exposure/reload").header("Authorization", ada)).andExpect(status().isOk());
        assertThat(scope("NSET:NS-X").get("epe").asLong()).isEqualTo(950_000L);
        assertThat(get("/api/v1/exposure/status", ada).at("/tier/reopened").asLong()).isGreaterThan(reopened);
        JsonNode tree = get("/api/v1/exposure/tree", as("rita", "risk"), "q", "0.75");
        assertThat(tree.toString()).contains("\"id\":\"GRP-1\"").contains("NSET:NS-X").contains("\"noPaths\":true");
        assertThat(tree.get("nettingSetsKnown").asInt()).isEqualTo(3);
        JsonNode ret = get("/api/v1/exposure/retention", as("rita", "risk"));
        assertThat(ret.get("dates").get(0).get("tradePaths").asText()).isEqualTo("keep");
        try (var files = Files.walk(service.loader().tier().root())) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("."))).as("no hidden files").isEmpty();
        }
    }

    @Test
    @Order(9)
    void exposureResultIsAnOrdinaryComputedKindAndTheNettingSetPageShowsItsFigures() throws Exception {
        connectors.write("exposure-engine", """
                plugin: reactive
                mode: reactive
                kinds:
                  exposure-result:
                    compute: exposure-result
                    params:
                      q: { type: decimal, default: 0.95, min: 0.5, max: 0.9999 }
                      collateral: { type: enum, options: [lagged, current, none], default: lagged }
                      run: { type: text, default: "" }
                    result: { required: [SCOPE, STATUS, PROFILE], types: { EPE: number, PEAK_PFE: number } }
                engine: { type: java, class: com.ash.drishti.server.exposure.ExposureResultEngine, version: "1.0" }
                triggers:
                  - { on: data-version, kinds: [exposure-path], affects: all }
                limits: { wait: 5s }
                """);
        manager.scan();
        try {
            String body = "{\"id\": \"NSET:NS-X\", \"asOf\": \"" + ExposureLakeFixture.DAY + "\", \"params\": {\"q\": 0.75}, \"wait\": 10000}";
            JsonNode a = json.readTree(mvc.perform(post("/api/v1/compute/exposure-engine/exposure-result").header("Authorization", as("rita", "risk"))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(a.get("state").asText()).as(a.toString()).isEqualTo("ready");
            JsonNode r = a.get("result");
            assertThat(r.get("EPE").asLong()).isEqualTo(950_000L);
            assertThat(r.get("PEAK_PFE").asLong()).isEqualTo(2_000_000L);
            assertThat(r.get("PROFILE")).hasSize(3);
            assertThat(r.get("PROFILE").get(1).get("PFE_99").isNumber()).isTrue();
            assertThat(r.at("/PROVENANCE/RUN_ID").asText()).isEqualTo("XR-2026-09-30-01");
            JsonNode z = json.readTree(mvc.perform(post("/api/v1/compute/exposure-engine/exposure-result").header("Authorization", as("rita", "risk"))
                    .contentType(MediaType.APPLICATION_JSON).content(body.replace("NS-X", "NS-Z"))).andReturn().getResponse().getContentAsString());
            assertThat(z.at("/result/STATUS").asText()).as(z.toString()).isEqualTo("no-paths");
            assertThat(z.at("/result/NOTE").asText()).contains("no exposure paths for the netting set NS-Z");
        } finally {
            connectors.delete("exposure-engine");
            manager.scan();
        }
        com.ash.drishti.api.EntityDocument stored = new com.ash.drishti.api.EntityDocument(com.ash.drishti.api.EntityRef.of("netting-set", "NS-Z"),
                com.ash.drishti.api.DataNode.of(java.util.Map.of("NETTING_SET_ID", "NS-Z")),
                new com.ash.drishti.api.Provenance("ccr-lake", 1, java.time.Instant.now(), false, ExposureLakeFixture.DAY));
        com.ash.drishti.api.EntityDocument[] seen = new com.ash.drishti.api.EntityDocument[1];
        com.ash.drishti.server.net.NetCaller.with(new com.ash.drishti.server.net.NetCaller.Caller(
                new com.ash.drishti.server.security.Principal("rita", List.of("risk")), null, "t"),
                () -> seen[0] = figures.augment(stored, com.ash.drishti.api.AsOf.of(ExposureLakeFixture.DAY)));
        JsonNode f = json.readTree(json.writeValueAsString(seen[0].data().unwrap())).get(ExposureFigures.FIELD);
        assertThat(f.get("STATUS").asText()).isEqualTo("no-paths");
        assertThat(f.get("NOTE").asText()).contains("NS-Z").contains("2026-09-30");
        com.ash.drishti.api.EntityDocument x = new com.ash.drishti.api.EntityDocument(com.ash.drishti.api.EntityRef.of("netting-set", "NS-X"),
                com.ash.drishti.api.DataNode.of(java.util.Map.of("NETTING_SET_ID", "NS-X")), stored.provenance());
        com.ash.drishti.server.net.NetCaller.with(new com.ash.drishti.server.net.NetCaller.Caller(
                new com.ash.drishti.server.security.Principal("rita", List.of("risk")), null, "t"),
                () -> seen[0] = figures.augment(x, com.ash.drishti.api.AsOf.of(ExposureLakeFixture.DAY)));
        JsonNode fx = json.readTree(json.writeValueAsString(seen[0].data().unwrap())).get(ExposureFigures.FIELD);
        assertThat(fx.get("STATUS").asText()).isEqualTo("ok");
        assertThat(fx.get("EPE").asLong()).isEqualTo(950_000L);
        assertThat(fx.get("NOTE").asText()).contains("EXP NSET:NS-X <GO>");
    }
}
