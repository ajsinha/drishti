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
package com.ash.drishti.banking.exposure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * The pre-deal service (EXPOSURE_GUIDE.md section 18.9) over the hand-made exposure lake (ExposureLakeFixture) with CP-1's credit limit on
 * its dates: {@code DAY} a limit far above the exposure; {@code BOOKED} (the next day) the same book with the copy {@code N} of trade A
 * (half of it) booked in NS-X; {@code TIGHT} a limit below the exposure; {@code BUCKETED} a wide limit whose 3M-1Y bucket is tight;
 * {@code EEPE} a limit on a measure the engine does not compute; {@code NO_LIMIT} before every limit. The what-if equals the full recompute with the trade booked, figure by figure and date by date; approve, decline (the limit and
 * a bucket), refer (a proxy, no limit, a measure not computed, no exposure); two people with different entitlements, at the same time;
 * the command's grammar; every check audited.
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
        "drishti.sources.routes.exposure-result=exposure-engine",
        "drishti.ccr.exposure.official.on-start=false", "drishti.ccr.exposure.close-grace-seconds=0", "drishti.var.official.on-start=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.security.roles.agg.kinds[0]=*", "drishti.security.roles.agg.raw=true", "drishti.ccr.exposure.access.agg[CP-1]=aggregate",
        "drishti.security.roles.other.kinds[0]=*", "drishti.security.roles.other.raw=true", "drishti.ccr.exposure.access.other[CP-2]=trades",
        "drishti.security.roles.curves.kinds[0]=curve",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class ExposurePreDealApiTest {

    static final LocalDate DAY = ExposureLakeFixture.DAY;
    static final LocalDate BOOKED = DAY.plusDays(1);
    static final LocalDate TIGHT = DAY.minusDays(1);
    static final LocalDate BUCKETED = DAY.minusDays(2);
    static final LocalDate EEPE = DAY.minusDays(5);
    static final LocalDate NO_LIMIT = DAY.minusDays(6);
    static final double HALF = 0.5;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        Path lake = Files.createDirectories(Path.of("target", "predeal-lake-" + System.nanoTime())).toAbsolutePath();
        ExposureLakeFixture.write(lake, DAY, 1.0);
        ExposureLakeFixture.write(lake, BOOKED, 1.0, HALF);
        ExposureLakeFixture.write(lake, TIGHT, 1.0);
        ExposureLakeFixture.write(lake, BUCKETED, 1.0);
        ExposureLakeFixture.write(lake, EEPE, 1.0);
        ExposureLakeFixture.write(lake, NO_LIMIT, 1.0);                           // before every limit (a limit is read as of the date)
        ExposureLakeFixture.limit(lake, DAY, 1e12, "PFE 95 peak", Map.of());
        ExposureLakeFixture.limit(lake, TIGHT, 1.0, "PFE 95 peak", Map.of());
        Map<String, Double> buckets = new LinkedHashMap<>();
        buckets.put("0-3M", 1e12);
        buckets.put("3M-1Y", 1.0);
        buckets.put("1Y+", 1e12);
        ExposureLakeFixture.limit(lake, BUCKETED, 1e12, "PFE 95 peak", buckets);
        ExposureLakeFixture.limit(lake, EEPE, 1e12, "CVA capital", Map.of());
        r.add("drishti.sources.connectors.ccr-lake.settings.root", lake::toString);
        r.add("drishti.ccr.exposure.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired AuditLog audit;
    final ObjectMapper json = new ObjectMapper();

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode check(String auth, LocalDate day, String command) throws Exception {
        return check(auth, day, json.writeValueAsString(Map.of("command", command)), status().isOk());
    }

    private JsonNode check(String auth, LocalDate day, String body, ResultMatcher expect) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/risk/exposure/predeal").header("Authorization", auth).param("asOf", day.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(expect).andReturn().getResponse().getContentAsString());
    }

    private JsonNode scope(String auth, LocalDate day, String scope) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/risk/exposure/scope").header("Authorization", auth).param("asOf", day.toString())
                .param("scope", scope).param("q", "0.95")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void theWhatIfEqualsTheFullRecomputeWithTheTradeBooked() throws Exception {
        String rita = as("rita", "risk");
        JsonNode x = check(rita, DAY, "PREDEAL CP-1 LIKE A 5M");                       // A's notional is 10m: half of A, into A's netting set
        assertThat(x.get("decision").asText()).as(x.toString()).isEqualTo("approve");
        assertThat(x.at("/method/id").asText()).isEqualTo("paths");
        assertThat(x.at("/method/exact").asBoolean()).isTrue();
        assertThat(x.at("/template/tradeId").asText()).isEqualTo("A");
        assertThat(x.at("/template/factor").asDouble()).isEqualTo(HALF);
        assertThat(x.at("/nettingSet/id").asText()).isEqualTo("NS-X");
        assertThat(x.at("/nettingSet/how").asText()).isEqualTo("the template's");
        assertThat(x.get("command").asText()).isEqualTo("PREDEAL CP-1 LIKE A 5M");
        JsonNode booked = scope(rita, BOOKED, "CPTY:CP-1");                            // the same book with N = A x 0.5 booked in NS-X
        JsonNode before = scope(rita, DAY, "CPTY:CP-1");
        for (String f : List.of("epe", "eepe", "peakPfe", "peakEe", "ce")) {
            assertThat(x.at("/exposure/with/" + f).asLong()).as(f).isEqualTo(booked.get(f).asLong());
            assertThat(x.at("/exposure/base/" + f).asLong()).as(f).isEqualTo(before.get(f).asLong());
        }
        JsonNode prof = x.at("/exposure/incremental/profile");
        assertThat(prof).hasSize(booked.get("profile").size());
        for (int i = 0; i < prof.size(); i++) {
            assertThat(prof.get(i).get("ee").asLong()).as("EE " + i).isEqualTo(booked.at("/profile/" + i + "/ee").asLong());
            assertThat(prof.get(i).get("pfe").asLong()).as("PFE " + i).isEqualTo(booked.at("/profile/" + i + "/pfe").asLong());
            assertThat(prof.get(i).get("eeBase").asLong()).as("EE before " + i).isEqualTo(before.at("/profile/" + i + "/ee").asLong());
        }
        assertThat(booked.get("peakPfe").asLong()).isNotEqualTo(before.get("peakPfe").asLong());     // the trade moves the figure
        assertThat(x.at("/limit/after").asLong()).isEqualTo(booked.get("peakPfe").asLong());
        assertThat(x.at("/limit/increment").asLong()).isEqualTo(booked.get("peakPfe").asLong() - before.get("peakPfe").asLong());
        assertThat(x.at("/limit/useAfter").asDouble()).isEqualTo(booked.get("peakPfe").asDouble() / 1e12);
        assertThat(x.at("/saCcr/status").asText()).isEqualTo("under-construction");
        assertThat(x.at("/saCcr/release").asText()).isEqualTo("2.9.11.k");
        // the same request as fields, and the same trade the other way round (REVERSE) netting against A
        JsonNode f = check(rita, DAY, json.writeValueAsString(Map.of("counterparty", "CP-1", "template", Map.of("copyOf", "A"), "notional", "5m")),
                status().isOk());
        assertThat(f.at("/exposure/with")).isEqualTo(x.at("/exposure/with"));
        JsonNode rev = check(rita, DAY, "PREDEAL CP-1 LIKE A 10M REVERSE IN NS-X");
        assertThat(rev.at("/template/factor").asDouble()).isEqualTo(-1.0);
        assertThat(rev.at("/template/side").asText()).isEqualTo("reverse");
        assertThat(rev.at("/nettingSet/how").asText()).isEqualTo("named");
        assertThat(rev.at("/limit/increment").asLong()).isEqualTo(rev.at("/exposure/with/peakPfe").asLong() - before.get("peakPfe").asLong());
        assertThat(rev.at("/exposure/with")).isNotEqualTo(x.at("/exposure/with"));
    }

    @Test
    void limitsDeclineAndApproveOnlyOnExactData() throws Exception {
        String rita = as("rita", "risk");
        JsonNode tight = check(rita, TIGHT, "PREDEAL CP-1 LIKE A 5M");
        assertThat(tight.get("decision").asText()).as(tight.toString()).isEqualTo("decline");
        assertThat(tight.at("/limit/status").asText()).isEqualTo("breach");
        assertThat(tight.get("reason").asText()).contains("over the limit");
        assertThat(tight.at("/limit/headroomAfter").asLong()).isNegative();
        JsonNode bucket = check(rita, BUCKETED, "PREDEAL CP-1 LIKE A 5M");
        assertThat(bucket.get("decision").asText()).as(bucket.toString()).isEqualTo("decline");
        assertThat(bucket.get("reason").asText()).contains("3M-1Y");
        assertThat(bucket.at("/limit/buckets")).hasSize(3);
        assertThat(bucket.at("/limit/buckets/0/status").asText()).isEqualTo("within");
        assertThat(bucket.at("/limit/buckets/1/status").asText()).isEqualTo("breach");
        assertThat(bucket.at("/limit/buckets/2/status").asText()).isEqualTo("within");
        assertThat(bucket.at("/limit/useAfter").asDouble()).isLessThan(1.0);                         // the total fits: the bucket declines
    }

    @Test
    void anythingLessThanExactDataRefersNeverApproves() throws Exception {
        String rita = as("rita", "risk");
        // a proxy: refers even with a limit a thousand times the exposure
        JsonNode proxy = check(rita, DAY, "PREDEAL CP-1 RATES 10M 1Y");
        assertThat(proxy.get("decision").asText()).as(proxy.toString()).isEqualTo("refer");
        assertThat(proxy.get("reason").asText()).contains("proxy").contains("never approves");
        assertThat(proxy.at("/method/id").asText()).isEqualTo("addon");
        assertThat(proxy.at("/method/exact").asBoolean()).isFalse();
        assertThat(proxy.at("/template/assetClass").asText()).isEqualTo("RATES");
        assertThat(proxy.at("/template/maturity").asText()).isEqualTo(DAY.plusYears(1).toString());
        assertThat(proxy.at("/limit/useAfter").asDouble()).isLessThan(0.01);
        // a tight limit under the proxy: still refer (a proxy neither approves nor declines)
        assertThat(check(rita, TIGHT, "PREDEAL CP-1 FX 10M 2027-09-30").get("decision").asText()).isEqualTo("refer");
        // no credit limit on record
        JsonNode none = check(rita, NO_LIMIT, "PREDEAL CP-1 LIKE A 5M");
        assertThat(none.get("decision").asText()).isEqualTo("refer");
        assertThat(none.get("reason").asText()).contains("no credit limit");
        assertThat(none.get("limit").isNull()).isTrue();
        // a limit on a measure the engine does not compute
        JsonNode other = check(rita, EEPE, "PREDEAL CP-1 LIKE A 5M");
        assertThat(other.get("decision").asText()).isEqualTo("refer");
        assertThat(other.get("reason").asText()).contains("CVA capital");
        // no exposure for the counterparty on the date (CP-2's netting set has no paths): refer, with the reason
        JsonNode cp2 = check(rita, DAY, "PREDEAL CP-2 RATES 10M 1Y");
        assertThat(cp2.get("decision").asText()).as(cp2.toString()).isEqualTo("refer");
        assertThat(cp2.get("reason").asText()).contains("no exposure");
    }

    @Test
    void twoPeopleAtOnceEachWithTheirOwnEntitlements() throws Exception {
        String rita = as("rita", "risk");
        String alan = as("alan", "agg");                                    // CP-1 in aggregate only: no trade of it
        String owen = as("owen", "other");                                  // CP-2 only
        JsonNode alone = check(rita, DAY, "PREDEAL CP-1 LIKE A 5M");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<JsonNode>> jobs = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                jobs.add(() -> check(rita, DAY, "PREDEAL CP-1 LIKE A 5M"));
                jobs.add(() -> check(alan, DAY, "PREDEAL CP-1 RATES 10M 1Y"));
            }
            List<Future<JsonNode>> done = pool.invokeAll(jobs);
            for (int i = 0; i < done.size(); i++) {
                JsonNode a = done.get(i).get();
                if (i % 2 == 0) {
                    assertThat(a.get("decision").asText()).isEqualTo("approve");
                    assertThat(a.get("exposure")).isEqualTo(alone.get("exposure"));
                } else {
                    assertThat(a.get("decision").asText()).isEqualTo("refer");
                    assertThat(a.at("/template/tradeId").isNull()).isTrue();
                }
            }
        } finally {
            pool.shutdownNow();
        }
        // a template is a trade the person sees trade by trade; a counterparty they do not see at all is refused
        check(alan, DAY, json.writeValueAsString(Map.of("command", "PREDEAL CP-1 LIKE A 5M")), status().isForbidden());
        check(owen, DAY, json.writeValueAsString(Map.of("command", "PREDEAL CP-1 RATES 10M 1Y")), status().isForbidden());
        // the same for the what-if: a copy of a trade the person does not see trade by trade
        String copy = "{\"scope\": \"CPTY:CP-1\", \"method\": \"paths\", \"candidates\": [{\"tradeId\": \"X\", \"paths\": {\"copyOf\": \"A\"}}]}";
        mvc.perform(post("/api/v1/risk/exposure/what-if").header("Authorization", alan).param("asOf", DAY.toString()).contentType(MediaType.APPLICATION_JSON)
                .content(copy)).andExpect(status().isForbidden());
        // every check audited, by the person who asked
        List<AuditLog.Event> events = audit.recent(500, "CPTY:CP-1").stream().filter(e -> e.action().equals("ccr-predeal")).toList();
        assertThat(events.stream().filter(e -> e.actor().equals("rita"))).hasSizeGreaterThanOrEqualTo(7);
        assertThat(events.stream().filter(e -> e.actor().equals("alan"))).hasSizeGreaterThanOrEqualTo(6);
        assertThat(events.stream().filter(e -> e.actor().equals("alan")).allMatch(e -> e.detail().contains("decision=refer"))).isTrue();
        assertThat(events.get(0).detail()).contains("check=PDC-").contains("command=\"PREDEAL CP-1");
    }

    @Test
    void theCommandGrammar() throws Exception {
        String rita = as("rita", "risk");
        JsonNode a = check(rita, DAY, "predeal cpty cp-1 like a 5m <GO>");
        assertThat(a.get("command").asText()).isEqualTo("PREDEAL CP-1 LIKE A 5M");
        assertThat(check(rita, DAY, "PREDEAL CPTY:CP-1 COMMODITY 1.5BN 18M MTM -2M REVERSE").get("command").asText())
                .isEqualTo("PREDEAL CP-1 COMMODITY 1.5BN 18M MTM -2M REVERSE");
        for (String bad : List.of("PREDEAL", "PREDEAL CP-1", "PREDEAL CP-1 RATES 10M", "PREDEAL CP-1 LIKE A RATES", "PREDEAL CP-1 LIKE A 5M WOBBLE",
                "PREDEAL CP-1 LIKE", "EXP CP-1", "PREDEAL CP-1 LIKE A -5M", "PREDEAL CP-1 RATES 10M 2026-01-01")) {
            check(rita, DAY, json.writeValueAsString(Map.of("command", bad)), status().isBadRequest());
        }
        mvc.perform(post("/api/v1/risk/exposure/predeal").header("Authorization", rita).param("asOf", DAY.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"command\": \"PREDEAL CP-1 LIKE NOPE\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/risk/exposure/predeal").header("Authorization", rita).param("asOf", DAY.toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"command\": \"PREDEAL CP-1 LIKE A IN NS-Z\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-5440"));
        JsonNode g = json.readTree(mvc.perform(get("/api/v1/risk/exposure/predeal/grammar").header("Authorization", rita)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(g.get("word").asText()).isEqualTo("PREDEAL");
        assertThat(g.get("usage").asText()).contains("LIKE <trade>");
        mvc.perform(post("/api/v1/risk/exposure/predeal").contentType(MediaType.APPLICATION_JSON).content("{\"command\": \"PREDEAL CP-1 LIKE A\"}"))
                .andExpect(status().isUnauthorized());
    }
}
