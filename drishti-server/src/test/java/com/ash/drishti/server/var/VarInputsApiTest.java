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
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Inputs in any form and any grain (VAR_GUIDE.md section 15.13). Every PV record says what it is ({@code VALUE_TYPE}, {@code GRAIN}, its
 * id, {@code CURRENCY}, {@code SOURCE_SYSTEM}). The same trades sent per trade on one date and as their positions on another give the
 * same node VaR and ES (P&amp;L vectors and scenario PVs, all four form and grain combinations across the two dates, a system switching
 * grain between them); a supplied position answers for itself, a trade of it degrades with words; a system sending two grains on one date,
 * or a supplied position covering a trade also sent on its own, refuses the date ({@code DRS-5210}); a record without its fields or
 * contradicting itself ({@code DRS-5208}) or its system's pin ({@code DRS-5209}) is refused and listed while the rest loads; an
 * intraday update in another form or grain than the system's morning run is refused and listed.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.kinds[4]=scenario-pv", "drishti.sources.connectors.risk-lake.kinds[5]=fx-scenario",
        "drishti.sources.connectors.risk-lake.kinds[6]=fx-spot",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.refresh-seconds=1",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=VALUE_TYPE,GRAIN,TRADE_ID,POSITION_ID,CURRENCY,SOURCE_SYSTEM,"
                + "LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,COUNTERPARTY,DIRECTION,QUANTITY,PV,"
                + "TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,SCENARIO_SET_ID,BATCH_ID,RUN_KIND,COMPUTED_AT,TRADE_IDS",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.risk-lake.settings.layout.scenario-pv.columns=VALUE_TYPE,GRAIN,TRADE_ID,POSITION_ID,CURRENCY,SOURCE_SYSTEM,"
                + "LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,PRODUCT_GROUP,PRODUCT_TYPE,COUNTERPARTY,DIRECTION,QUANTITY,BASE_PV,VALUATION_DATE,"
                + "BASE_VALUATION_DATE,TRADE_STATUS,MATURITY_DATE,STATUS,SCENARIO_SET_ID,BATCH_ID,RUN_KIND,COMPUTED_AT,TRADE_IDS",
        "drishti.sources.connectors.risk-lake.settings.layout.scenario-pv.vectors=SCENARIO_PV",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.scenario-pv=risk-lake",
        "drishti.sources.routes.fx-scenario=risk-lake", "drishti.sources.routes.fx-spot=risk-lake",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.inputs.PINNED.form=pv", "drishti.var.inputs.PINNED.grain=trade",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false", "drishti.var.currencies=EUR",
        "drishti.var.hot-dates=6",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VarInputsApiTest {

    /** Per trade: MUREX's P&amp;L vectors and ENDUR's scenario PVs, as the fixture's morning run. */
    static final LocalDate TRADES = VarLakeFixture.DAY;
    /** The same trades as their positions (both systems at position grain). */
    static final LocalDate POSITIONS = TRADES.plusDays(1);
    /** MUREX at position grain, ENDUR per trade: the other two combinations on one date. */
    static final LocalDate MIXED_SYSTEMS = TRADES.plusDays(2);
    /** MUREX sends trades and a position on one date: refused. */
    static final LocalDate TWO_GRAINS = TRADES.minusDays(1);
    /** Records that do not say what they are, or contradict themselves or the pin. */
    static final LocalDate BAD_RECORDS = TRADES.minusDays(2);
    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-inputs-lake-" + System.nanoTime())).toAbsolutePath();
        VarIntradayLake.morning(lake, TRADES, POSITIONS, MIXED_SYSTEMS, TWO_GRAINS, BAD_RECORDS);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
        r.add("drishti.var.tier.dir", () -> lake.resolve("hot").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    @Autowired VarOfficial official;
    final ObjectMapper json = new ObjectMapper();

    private JsonNode ask(String url, String scope, LocalDate day, int expect) throws Exception {
        var req = get(url).param("scope", scope).param("asOf", day.toString()).header("Authorization", "Bearer " + tokens.mint("rita",
                List.of("risk"), 300));
        return json.readTree(mvc.perform(req).andExpect(status().is(expect)).andReturn().getResponse().getContentAsString());
    }

    private JsonNode ask(String url, String scope, LocalDate day) throws Exception {
        return ask(url, scope, day, 200);
    }

    /** Writes a date's two tables again (a date's whole run), waits until the lake shows it, and drops the date if it was loaded. */
    private void rewrite(LocalDate day, List<Map<String, Object>> vectors, List<Map<String, Object>> pvs) throws Exception {
        String before = svc.loader().version(day);
        VarIntradayLake.writeVectors(lake, vectors, "overwrite-dates");
        VarIntradayLake.writePvs(lake, pvs, "overwrite-dates");
        long until = System.currentTimeMillis() + 20_000;
        while (svc.loader().version(day).equals(before) && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        svc.reload(day);
    }

    /** One position's record from its trades' records of the same form: the vectors (or the PVs and base PVs) summed. */
    private static Map<String, Object> position(String id, List<Map<String, Object>> members, String vector) {
        Map<String, Object> first = members.get(0);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("VALUE_TYPE", first.get("VALUE_TYPE"));
        p.put("GRAIN", "POSITION");
        p.put("POSITION_ID", id);
        first.forEach((k, v) -> {
            if (!k.equals("TRADE_ID") && !k.equals("GRAIN")) {
                p.putIfAbsent(k, v);
            }
        });
        double[] sum = new double[VarLakeFixture.N];
        double qty = 0;
        double base = 0;
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> m : members) {
            double[] v = (double[]) m.get(vector);
            for (int j = 0; j < sum.length; j++) {
                sum[j] += v[j];
            }
            qty += ((Number) m.get("QUANTITY")).doubleValue();
            base += m.get("BASE_PV") instanceof Number n ? n.doubleValue() : 0;
            ids.add((String) m.get("TRADE_ID"));
        }
        p.put(vector, sum);
        p.put("QUANTITY", qty);
        if (p.containsKey("BASE_PV")) {
            p.put("BASE_PV", base);
        }
        p.put(vector.equals("PNL") ? "VECTOR_ID" : "SCENARIO_PV_ID", (vector.equals("PNL") ? "PV-" : "SPV-") + id);
        p.put("TRADE_IDS", ids);
        return p;
    }

    /** The trades' records of a date grouped as the engine derived their positions on {@link #TRADES}: one record a position. */
    private List<Map<String, Object>> asPositions(List<Map<String, Object>> rows, String vector, LocalDate day) {
        VarBook b = svc.date(TRADES).book();
        Map<String, Map<String, Object>> byTrade = new HashMap<>();
        rows.forEach(r -> byTrade.put((String) r.get("TRADE_ID"), r));
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> placed = new java.util.HashSet<>();
        for (int p = 0; p < b.positionCount(); p++) {
            List<Map<String, Object>> members = new ArrayList<>();
            b.positionMembers(p).forEach(o -> {
                Map<String, Object> m = byTrade.get(b.tradeId(o));
                if (m != null) {
                    members.add(m);
                }
            });
            if (!members.isEmpty()) {
                out.add(position("POS-" + b.positionId(p), members, vector));
                members.forEach(m -> placed.add((String) m.get("TRADE_ID")));
            }
        }
        // the records the morning run left out (not live, rejected) are not sent: a system sends one grain on one date
        out.forEach(r -> r.put("BUSINESS_DATE", day.toString()));
        return out;
    }

    private void sameNodes(LocalDate a, LocalDate e) throws Exception {
        for (String node : svc.date(e).book().nodes().keySet()) {
            JsonNode x = ask("/api/v1/var/scope/path?size=1", "NODE:" + node, a);
            JsonNode y = ask("/api/v1/var/scope/path?size=1", "NODE:" + node, e);
            assertThat(x.get("var").asDouble()).as(node + " " + a).isCloseTo(y.get("var").asDouble(), within(0.01));
            assertThat(x.get("es").asDouble()).as(node + " " + a).isCloseTo(y.get("es").asDouble(), within(0.01));
            for (int j = 0; j < x.get("series").size(); j++) {
                assertThat(x.get("series").get(j).asDouble()).as(node + " " + j).isCloseTo(y.get("series").get(j).asDouble(), within(0.01));
            }
        }
    }

    @Test
    @Order(1)
    void theSameTradesAsTheirPositionsGiveTheSameNodesInAllFourCombinations() throws Exception {
        JsonNode trades = ask("/api/v1/var/scope", "BANK", TRADES);
        assertThat(trades.at("/provenance/inputsBySystem/MUREX/form").asText()).isEqualTo("pnl");
        assertThat(trades.at("/provenance/inputsBySystem/MUREX/grain").asText()).isEqualTo("trade");
        assertThat(trades.at("/provenance/inputsBySystem/ENDUR/form").asText()).isEqualTo("pv");
        // POSITIONS: both systems at position grain (PNL/POSITION, PV/POSITION); MIXED_SYSTEMS: MUREX by position, ENDUR by trade
        List<Map<String, Object>> v = VarIntradayLake.morningVectors(POSITIONS);
        List<Map<String, Object>> p = VarIntradayLake.morningPvs(POSITIONS);
        rewrite(POSITIONS, asPositions(v, "PNL", POSITIONS), asPositions(p, "SCENARIO_PV", POSITIONS));
        rewrite(MIXED_SYSTEMS, asPositions(VarIntradayLake.morningVectors(MIXED_SYSTEMS), "PNL", MIXED_SYSTEMS),
                VarIntradayLake.morningPvs(MIXED_SYSTEMS));
        JsonNode pos = ask("/api/v1/var/scope", "BANK", POSITIONS);
        assertThat(pos.at("/provenance/inputsBySystem/MUREX/grain").asText()).isEqualTo("position");   // MUREX switched grain between dates
        assertThat(pos.at("/provenance/inputsBySystem/ENDUR/grain").asText()).isEqualTo("position");
        assertThat(pos.at("/provenance/inputsBySystem/MUREX/tradesWithoutPv").asInt()).isPositive();
        sameNodes(POSITIONS, TRADES);
        sameNodes(MIXED_SYSTEMS, TRADES);
        // a supplied position answers for itself; a trade of it, by its id, degrades with words; the position page names its trades
        VarBook b = svc.date(POSITIONS).book();
        String some = null;
        for (int i = 0; i < b.positionCount() && some == null; i++) {
            if (VarBook.SUPPLIED.equals(b.positions().text("POSITION_RULE", i))) {
                some = b.positionId(i);
            }
        }
        assertThat(some).startsWith("POS-");
        assertThat(ask("/api/v1/var/scope", "POS:" + some, POSITIONS).get("var").asDouble()).isNotNegative();
        JsonNode trd = ask("/api/v1/var/scope", "TRD:" + some, POSITIONS);
        assertThat(trd.at("/provenance/resolved").toString() + trd.at("/provenance/notices").toString()).contains("position grain");
        assertThat(svc.date(POSITIONS).intake().tradesOf(some)).isNotEmpty();
    }

    @Test
    @Order(2)
    void aSystemSendingTwoGrainsOnOneDateIsRefused() throws Exception {
        List<Map<String, Object>> v = VarIntradayLake.morningVectors(TWO_GRAINS);
        Map<String, Object> t1 = v.stream().filter(r -> r.get("TRADE_ID").equals("T1")).findFirst().orElseThrow();
        v.removeIf(r -> r.get("TRADE_ID").equals("T1"));
        v.add(position("POS-T1", List.of(t1), "PNL"));                     // MUREX: trades, and one position
        rewrite(TWO_GRAINS, v, VarIntradayLake.morningPvs(TWO_GRAINS));
        JsonNode r = ask("/api/v1/var/scope", "BANK", TWO_GRAINS, 422);
        assertThat(r.get("code").asText()).isEqualTo("DRS-5210");
        assertThat(r.get("detail").asText()).contains("MUREX").contains("PNL/POSITION").contains("PNL/TRADE");
    }

    @Test
    @Order(3)
    void recordsThatDoNotSayWhatTheyAreAreRefusedAndListedTheRestLoads() throws Exception {
        List<Map<String, Object>> v = VarIntradayLake.morningVectors(BAD_RECORDS);
        for (Map<String, Object> r : v) {
            switch ((String) r.get("TRADE_ID")) {
                case "T4" -> r.put("GRAIN", null);                         // no GRAIN
                case "T5" -> r.put("POSITION_ID", "POS-X");                // GRAIN TRADE with a POSITION_ID
                case "T9" -> r.put("VALUE_TYPE", "PV");                    // says PV, carries a P&L vector
                case "T3" -> r.put("SOURCE_SYSTEM", "PINNED");             // PINNED is pinned to pv: a P&L vector disagrees
                default -> { }
            }
        }
        rewrite(BAD_RECORDS, v, VarIntradayLake.morningPvs(BAD_RECORDS));
        JsonNode bank = ask("/api/v1/var/scope", "BANK", BAD_RECORDS);
        String why = bank.at("/provenance/rejections").toString();
        assertThat(why).contains("DRS-5208 T4: no GRAIN").contains("DRS-5208 T5: GRAIN TRADE with a POSITION_ID")
                .contains("DRS-5208 T9: VALUE_TYPE PV but the record is a P&L vector").contains("DRS-5209 T3: PINNED is pinned to pv, trade");
        assertThat(bank.get("var").asDouble()).isPositive();                // the rest loads
    }

    @Test
    @Order(4)
    void anIntradayUpdateInAnotherFormOrGrainIsRefusedAndListed() throws Exception {
        ask("/api/v1/var/scope", "BANK", TRADES);
        String before = svc.loader().version(TRADES);
        Map<String, Object> t1 = VarIntradayLake.vector(TRADES, "T1", "BOOK-1", "DESK-X", "LE-A", "USD", 150, VarLakeFixture.scale(VarLakeFixture.U, 150),
                "AMENDED", "INTRADAY", "I-1", TRADES + "T10:00:00Z");
        Map<String, Object> asPosition = position("POS-T5", List.of(VarIntradayLake.vector(TRADES, "T5", "BOOK-3", "DESK-Y", "LE-B", "USD", 60,
                VarLakeFixture.scale(VarLakeFixture.V, 60), "AMENDED", "INTRADAY", "I-1", TRADES + "T10:00:00Z")), "PNL");
        VarIntradayLake.writeVectors(lake, List.of(t1, asPosition), "append");
        long until = System.currentTimeMillis() + 20_000;
        while (svc.loader().version(TRADES).equals(before) && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        official.onLoad("pnl-vector", TRADES);
        JsonNode bank = ask("/api/v1/var/scope", "BANK", TRADES);
        assertThat(bank.at("/intraday/trades").asInt()).isEqualTo(1);                       // T1 applied
        assertThat(bank.at("/intraday/rejections").toString()).contains("DRS-5210").contains("MUREX's morning run sent PNL/TRADE")
                .contains("PNL/POSITION");
        assertThat(ask("/api/v1/var/scope", "TRD:T1", TRADES).get("var").asDouble()).isEqualTo(1500);
    }
}
