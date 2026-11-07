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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.compute.var.VarMath;
import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The stress window selection (VAR_GUIDE.md section 18.9). A date's lake holds a normal set (40 days), a stress set (30 days from
 * 2008-09-01) and the long-history P&amp;L of two nodes (300 business days from 2008-01-01). With windows of 30 days: every window's figure
 * is the VaR of its own days by the engine's rank rule; the current window is the stress set's; the worst window (a loss in January
 * 2008) is 20% above it, so the alert is raised at the 5% threshold; the candidates never overlap; today's VaR is the last 40 days';
 * the proposal freezes the analysis, its proposer may not decide it, another person approves it and the tool names the approval beside
 * the current window; a role that does not see every node is refused; an unknown node and a short window are refused in words.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.kinds[4]=pnl-history",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-history.columns=HIERARCHY,NODE_ID,LEVEL,CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-history.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.pnl-history=risk-lake",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false", "drishti.var.currencies=USD",
        "drishti.var.stress-window.length=30",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.security.roles.viewer.kinds[0]=trade",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarStressWindowApiTest {

    static final LocalDate DAY = VarLakeFixture.DAY;
    static final int DAYS = 300;
    static Path lake;
    static List<String> history;
    static double[] bank;
    static double[] desk;

    static List<String> days(LocalDate from, int n) {
        List<String> out = new ArrayList<>();
        for (LocalDate d = from; out.size() < n; d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() < 6) {
                out.add(d.toString());
            }
        }
        return out;
    }

    static Map<String, Object> row(String node, String level, double[] pnl) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("HISTORY_ID", "PNLH-" + node);
        d.put("BUSINESS_DATE", DAY.toString());
        d.put("HIERARCHY", "MGMT");
        d.put("NODE_ID", node);
        d.put("NAME", node.substring(5));
        d.put("LEVEL", level);
        d.put("CURRENCY", "USD");
        d.put("FIRST_DAY", history.get(0));
        d.put("LAST_DAY", history.get(DAYS - 1));
        d.put("DAY_COUNT", DAYS);
        d.put("HISTORY_DATES", history);
        d.put("PNL", pnl);
        d.put("SYNTHETIC", true);
        return d;
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-stress-window-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake, DAY, 1.0, false, false);
        history = days(LocalDate.of(2008, 1, 1), DAYS);
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(11);
        bank = new double[DAYS];
        desk = new double[DAYS];
        for (int j = 0; j < DAYS; j++) {
            desk[j] = Math.round(rnd.nextGaussian() * 1000);
            bank[j] = desk[j] + Math.round(rnd.nextGaussian() * 1000);
        }
        int at = history.indexOf("2008-09-01");
        bank[at + 5] = -50_000;                                          // the stress set's window: its worst day
        bank[10] = -60_000;                                              // January 2008: 20% worse
        desk[at + 3] = -20_000;
        Path work = Files.createDirectories(lake.resolve("_work"));
        VarLakeFixture.table(lake, work, "pnl-history", "HISTORY_ID", List.of(row("MGMT~BANK", "bank", bank), row("MGMT~DESK-X", "desk", desk)),
                List.of("HIERARCHY", "NODE_ID", "LEVEL", "CURRENCY"), "PNL");
        List<Map<String, Object>> sets = List.of(
                Map.of("SCENARIO_SET_ID", "HS-40", "BUSINESS_DATE", DAY.toString(), "SCENARIO_DATES", days(LocalDate.of(2026, 7, 1), 40)),
                Map.of("SCENARIO_SET_ID", "HS-STRESS-T", "SET_TYPE", "STRESS", "SYNTHETIC", true, "BUSINESS_DATE", DAY.toString(),
                        "SCENARIO_DATES", days(LocalDate.of(2008, 9, 1), 30), "WINDOW_START", "2008-09-01", "SCENARIO_COUNT", 30));
        VarLakeFixture.table(lake, work, "scenario-set", "SCENARIO_SET_ID", sets, List.of(), null);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
        r.add("drishti.var.tier.dir", () -> lake.resolve("hot").toString());
        r.add("drishti.data.dir", () -> lake.resolve("data").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    String bearer(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    JsonNode analyse(String scope) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/var/stress-window").param("scope", scope).param("asOf", DAY.toString())
                .header("Authorization", bearer("rita", "risk"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    static double var(double[] v, int from, int n) {
        return VarMath.var(Arrays.copyOfRange(v, from, from + n), 0.99);
    }

    @Test
    void everyWindowTheWorstTheCurrentTheCandidatesAndTheAlert() throws Exception {
        JsonNode a = analyse("BANK");
        int at = history.indexOf("2008-09-01");
        assertThat(a.at("/node/id").asText()).isEqualTo("MGMT~BANK");
        assertThat(a.at("/history/days").asInt()).isEqualTo(DAYS);
        assertThat(a.at("/history/synthetic").asBoolean()).isTrue();
        assertThat(a.at("/length").asInt()).isEqualTo(30);
        JsonNode series = a.at("/series/var");
        assertThat(series.size()).isEqualTo(DAYS - 30 + 1);
        for (int s : new int[] {0, 17, at, DAYS - 30}) {
            assertThat(series.get(s).asDouble()).isEqualTo(var(bank, s, 30));
        }
        assertThat(a.at("/current/first").asText()).isEqualTo("2008-09-01");
        assertThat(a.at("/current/var").asDouble()).isEqualTo(50_000.0);
        assertThat(a.at("/current/worstDay").asText()).isEqualTo(history.get(at + 5));
        assertThat(a.at("/stressSet/id").asText()).isEqualTo("HS-STRESS-T");
        assertThat(a.at("/worst/first").asText()).isEqualTo(history.get(0));            // ties: the earliest window holding day 10
        assertThat(a.at("/worst/var").asDouble()).isEqualTo(60_000.0);
        assertThat(a.at("/alert/raised").asBoolean()).isTrue();
        assertThat(a.at("/alert/gap").asDouble()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(a.at("/today/days").asInt()).isEqualTo(40);
        assertThat(a.at("/today/var").asDouble()).isEqualTo(var(bank, DAYS - 40, 40));
        List<Integer> starts = new ArrayList<>();
        boolean current = false;
        for (JsonNode c : a.at("/candidates")) {
            current |= c.get("current").asBoolean();
            if (!c.get("current").asBoolean() || c.get("worst").asBoolean()) {
                starts.add(history.indexOf(c.get("first").asText()));
            }
        }
        assertThat(current).isTrue();
        for (int i = 0; i < starts.size(); i++) {
            for (int j = i + 1; j < starts.size(); j++) {
                assertThat(Math.abs(starts.get(i) - starts.get(j))).isGreaterThanOrEqualTo(30);
            }
        }
        assertThat(a.at("/byNode").size()).isEqualTo(2);
        assertThat(a.at("/byNode/0/id").asText()).isEqualTo("MGMT~BANK");
        assertThat(a.at("/byNode/1/current").asDouble()).isEqualTo(var(desk, at, 30));
        assertThat(analyse("DESK:X").at("/node/id").asText()).isEqualTo("MGMT~DESK-X");
    }

    @Test
    void refusedInWords() throws Exception {
        mvc.perform(get("/api/v1/var/stress-window").param("scope", "DESK:NOPE").param("asOf", DAY.toString())
                .header("Authorization", bearer("rita", "risk"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/var/stress-window").param("scope", "BANK").param("length", "10").param("asOf", DAY.toString())
                .header("Authorization", bearer("rita", "risk"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/var/stress-window").param("scope", "BANK").param("asOf", DAY.toString())
                .header("Authorization", bearer("vic", "viewer"))).andExpect(status().isForbidden());
    }

    @Test
    void proposeFreezeAndAnotherPersonApproves() throws Exception {
        String body = "{\"scope\":\"BANK\",\"hierarchy\":\"MGMT\",\"date\":\"" + DAY + "\",\"first\":\"2008-09-01\",\"note\":\"keep 2008\"}";
        JsonNode rec = json.readTree(mvc.perform(post("/api/v1/var/stress-window/proposals").contentType(MediaType.APPLICATION_JSON)
                .content(body).header("Authorization", bearer("rita", "risk"))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
        String id = rec.get("id").asText();
        assertThat(id).startsWith("SWP-");
        assertThat(rec.get("status").asText()).isEqualTo("PROPOSED");
        assertThat(rec.at("/proposed/first").asText()).isEqualTo("2008-09-01");
        assertThat(rec.at("/history/dataVersion").asText()).isNotBlank();
        assertThat(rec.get("proposedBy").asText()).isEqualTo("rita");
        String decide = "{\"comment\":\"ok\"}";
        mvc.perform(post("/api/v1/approvals/stress-window/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content(decide)
                .header("Authorization", bearer("rita", "risk"))).andExpect(status().isForbidden());
        JsonNode done = json.readTree(mvc.perform(post("/api/v1/approvals/stress-window/" + id + "/approve")
                .contentType(MediaType.APPLICATION_JSON).content(decide).header("Authorization", bearer("sam", "risk")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(done.get("status").asText()).isEqualTo("approved");
        assertThat(done.get("decidedBy").asText()).isEqualTo("sam");
        mvc.perform(post("/api/v1/approvals/stress-window/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content(decide)
                .header("Authorization", bearer("tom", "risk"))).andExpect(status().isConflict());
        assertThat(analyse("BANK").at("/approval/by").asText()).isEqualTo("sam");
        JsonNode list = json.readTree(mvc.perform(get("/api/v1/var/stress-window/proposals").header("Authorization", bearer("rita", "risk")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.get("count").asInt()).isGreaterThanOrEqualTo(1);
    }
}
