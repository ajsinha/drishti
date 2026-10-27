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

import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * Intraday updates from files (VAR_GUIDE.md section 8.4): after the morning run, small batches appended to the trades' tables
 * ({@code RUN_KIND: INTRADAY}, each its own {@code BATCH_ID}) are applied by their data-load signal without a reload: an amended P&amp;L
 * vector, a cancel, a new trade, an amended scenario-PV trade updated twice (the later {@code COMPUTED_AT} wins), an older update
 * ignored, another scenario set refused ({@code DRS-5203}). The result equals, to the cent, the same final data loaded as one run (on
 * the next day, every node and trade, USD and the currencies built ahead); the answers say {@code intraday}; the official record is the
 * morning run's; a full run of the date drops the layer.
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
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,RUN_KIND,COMPUTED_AT",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.risk-lake.settings.layout.scenario-pv.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,BASE_PV,VALUATION_DATE,BASE_VALUATION_DATE,TRADE_STATUS,"
                + "MATURITY_DATE,STATUS,SCENARIO_SET_ID,BATCH_ID,RUN_KIND,COMPUTED_AT",
        "drishti.sources.connectors.risk-lake.settings.layout.scenario-pv.vectors=SCENARIO_PV",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.scenario-pv=risk-lake",
        "drishti.sources.routes.fx-scenario=risk-lake", "drishti.sources.routes.fx-spot=risk-lake",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false",
        "drishti.var.currencies=EUR,CAD", "drishti.var.hot-dates=4",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VarIntradayApiTest {

    static final LocalDate DAY = VarLakeFixture.DAY;
    /** The final data of {@link #DAY} loaded as one run, on the next day: what the intraday layer must equal. */
    static final LocalDate FULL = DAY.plusDays(1);
    /** The date of the few hundred batches, and the date its final state is loaded on as one run. */
    static final LocalDate MANY = DAY.plusDays(2);
    static final LocalDate MANY_AS_ONE_RUN = DAY.minusDays(1);
    static Path lake;
    static double morningBank;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-intraday-lake-" + System.nanoTime())).toAbsolutePath();
        VarIntradayLake.morning(lake, DAY, FULL, MANY, MANY_AS_ONE_RUN);
        // FULL: the day's final state as one run
        List<Map<String, Object>> v = new ArrayList<>();
        for (Map<String, Object> row : VarIntradayLake.morningVectors(FULL)) {
            if (row.get("TRADE_ID").equals("T1")) {
                row.put("QUANTITY", 150.0);
                row.put("PNL", VarLakeFixture.scale(VarLakeFixture.U, 150));
            }
            if (row.get("TRADE_ID").equals("T2")) {
                row.put("TRADE_STATUS", "CANCELLED");
            }
            v.add(row);
        }
        Map<String, Object> n1 = VarIntradayLake.vector(FULL, "N1", "BOOK-3", "DESK-Y", "LE-B", "USD", -30, VarLakeFixture.scale(VarLakeFixture.V, -30),
                "NEW", "FULL", "B-" + FULL + "-01", FULL + VarIntradayLake.MORNING_AT);
        v.add(n1);
        VarIntradayLake.writeVectors(lake, v, "overwrite-dates");
        List<Map<String, Object>> p = new ArrayList<>();
        p.add(VarIntradayLake.pv(FULL, "P1", "EUR", 1200, VarPvFixture.plus(1200, VarLakeFixture.U, 30), "FULL", "B-" + FULL + "-01",
                FULL + VarIntradayLake.MORNING_AT, "HS-20"));
        p.add(VarIntradayLake.morningPvs(FULL).get(1));
        p.add(VarIntradayLake.pv(FULL, "NP", "CAD", 500, VarPvFixture.plus(500, VarLakeFixture.V, 3), "FULL", "B-" + FULL + "-01",
                FULL + VarIntradayLake.MORNING_AT, "HS-20"));
        VarIntradayLake.writePvs(lake, p, "overwrite-dates");
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
        r.add("drishti.var.tier.dir", () -> lake.resolve("hot").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    @Autowired VarOfficial official;
    final ObjectMapper json = new ObjectMapper();

    private JsonNode ask(String url, String scope, LocalDate day, String ccy) throws Exception {
        var req = get(url).param("scope", scope).param("asOf", day.toString()).header("Authorization", "Bearer " + tokens.mint("rita",
                List.of("risk"), 300));
        if (ccy != null) {
            req = req.param("ccy", ccy);
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** Appends a batch, waits until the lake shows it, and sends its data-load signal. */
    private void batch(String kind, List<Map<String, Object>> rows) throws Exception {
        batch(DAY, kind, rows);
    }

    private void batch(LocalDate day, String kind, List<Map<String, Object>> rows) throws Exception {
        String before = svc.loader().version(day);
        if (kind.equals("pnl-vector")) {
            VarIntradayLake.writeVectors(lake, rows, "append");
        } else {
            VarIntradayLake.writePvs(lake, rows, "append");
        }
        long until = System.currentTimeMillis() + 20_000;
        while (svc.loader().version(day).equals(before) && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        official.onLoad(kind, day);
    }

    @Test
    @Order(1)
    void intradayBatchesFromFilesApplyWithoutAReload() throws Exception {
        JsonNode bank = ask("/api/v1/var/scope", "BANK", DAY, null);
        assertThat(bank.get("intraday").isNull()).isTrue();
        morningBank = bank.get("var").asDouble();
        ask("/api/v1/var/scope", "BANK", DAY, "EUR");                          // the EUR book exists before the updates
        VarDate before = svc.date(DAY);
        String at = DAY + "T1";
        batch("pnl-vector", List.of(
                VarIntradayLake.vector(DAY, "T1", "BOOK-1", "DESK-X", "LE-A", "USD", 150, VarLakeFixture.scale(VarLakeFixture.U, 150), "AMENDED",
                        "INTRADAY", "B-I1", at + "0:00:00Z"),
                VarIntradayLake.vector(DAY, "T2", "BOOK-1", "DESK-X", "LE-A", "USD", -40, null, "CANCELLED", "INTRADAY", "B-I1", at + "0:00:00Z"),
                VarIntradayLake.vector(DAY, "N1", "BOOK-3", "DESK-Y", "LE-B", "USD", -30, VarLakeFixture.scale(VarLakeFixture.V, -30), "NEW",
                        "INTRADAY", "B-I1", at + "0:00:00Z")));
        VarDate after = svc.date(DAY);
        assertThat(after.book().morning()).isSameAs(before.book().morning());   // applied in place: the morning book kept, not reloaded
        JsonNode one = ask("/api/v1/var/scope", "BANK", DAY, null);
        assertThat(one.at("/intraday/updates").asInt()).isEqualTo(3);
        assertThat(one.at("/intraday/batches").toString()).contains("B-I1");
        batch("scenario-pv", List.of(
                VarIntradayLake.pv(DAY, "P1", "EUR", 1100, VarPvFixture.plus(1100, VarLakeFixture.U, 20), "INTRADAY", "B-I2", at + "1:00:00Z", "HS-20"),
                VarIntradayLake.pv(DAY, "NP", "CAD", 500, VarPvFixture.plus(500, VarLakeFixture.V, 3), "INTRADAY", "B-I2", at + "1:00:00Z", "HS-20")));
        batch("scenario-pv", List.of(
                VarIntradayLake.pv(DAY, "P1", "EUR", 1200, VarPvFixture.plus(1200, VarLakeFixture.U, 30), "INTRADAY", "B-I3", at + "2:00:00Z", "HS-20")));
        batch("scenario-pv", List.of(                                            // computed before the update already applied: ignored
                VarIntradayLake.pv(DAY, "P1", "EUR", 9999, VarPvFixture.plus(9999, VarLakeFixture.U, 1), "INTRADAY", "B-I4", at + "0:30:00Z", "HS-20")));
        batch("scenario-pv", List.of(                                            // another scenario set: refused
                VarIntradayLake.pv(DAY, "P2", "CAD", 7777, VarPvFixture.plus(7777, VarLakeFixture.V, 1), "INTRADAY", "B-I5", at + "3:00:00Z", "HS-99")));
        JsonNode done = ask("/api/v1/var/scope", "BANK", DAY, null);
        assertThat(done.at("/intraday/trades").asInt()).isEqualTo(5);                // T1, T2, N1, P1, NP
        assertThat(done.at("/intraday/added").asInt()).isEqualTo(2);
        assertThat(done.at("/intraday/cancelled").asInt()).isEqualTo(1);
        assertThat(done.at("/intraday/lastAt").asText()).isEqualTo(at + "2:00:00Z");
        assertThat(done.at("/intraday/olderIgnored").asInt()).isEqualTo(1);
        assertThat(done.at("/intraday/rejections").toString()).contains("DRS-5203").contains("HS-99");
        assertThat(done.at("/provenance/intraday/updates").asInt()).isEqualTo(done.at("/intraday/updates").asInt());
    }

    @Test
    @Order(2)
    void theIncrementalResultEqualsAFullReloadToTheCentInEveryCurrency() throws Exception {
        VarDate full = svc.date(FULL);
        for (String ccy : new String[] {null, "EUR", "CAD"}) {
            for (String node : full.book().nodes().keySet()) {
                JsonNode a = ask("/api/v1/var/scope/path?size=1", "NODE:" + node, DAY, ccy);
                JsonNode e = ask("/api/v1/var/scope/path?size=1", "NODE:" + node, FULL, ccy);
                assertThat(a.get("var").asDouble()).as(node + " " + ccy).isEqualTo(e.get("var").asDouble());
                assertThat(a.get("es").asDouble()).as(node + " " + ccy).isEqualTo(e.get("es").asDouble());
                for (int j = 0; j < a.get("series").size(); j++) {
                    assertThat(a.get("series").get(j).asDouble()).as(node + " " + ccy + " " + j).isEqualTo(e.get("series").get(j).asDouble());
                }
            }
            VarService.Scoped sa = svc.scoped(null, "NODE:MGMT~BANK", DAY, ccy);
            VarService.Scoped se = svc.scoped(null, "NODE:MGMT~BANK", FULL, ccy);
            for (int j = 0; j < sa.vector().length; j++) {
                assertThat(sa.vector()[j]).as("bank " + ccy + " " + j).isCloseTo(se.vector()[j], within(0.005));
            }
            assertThat(sa.measures().var()).isCloseTo(se.measures().var(), within(0.005));
        }
        for (String scope : List.of("TRD:N1", "TRD:NP", "TRD:P1", "TRD:T1", "BOOK:BOOK-3", "DESK:DESK-X - TRD:T1", "POS:" + VarLakeFixture.POS_A)) {
            JsonNode a = ask("/api/v1/var/scope", scope, DAY, null);
            JsonNode e = ask("/api/v1/var/scope", scope, FULL, null);
            assertThat(a.get("var").asDouble()).as(scope).isEqualTo(e.get("var").asDouble());
            assertThat(a.at("/provenance/trades").asInt()).as(scope).isEqualTo(e.at("/provenance/trades").asInt());
        }
        // a cancelled trade is in no scope
        assertThat(ask("/api/v1/var/scope", "TRD:T2", DAY, null).at("/provenance/unknown").toString()).contains("T2");
        // the contributions by trade list the new ones
        assertThat(ask("/api/v1/var/scope/contributions?by=trade", "BOOK:BOOK-3", DAY, null).get("rows").toString()).contains("\"N1\"").contains("\"NP\"");
    }

    @Test
    @Order(3)
    void theOfficialRecordIsTheMorningRun() throws Exception {
        Map<String, Object> r = official.capture(DAY);
        assertThat(r.get("status")).as(r.toString()).isIn("captured", "unchanged", "restated");
        JsonNode rec = json.readTree(mvc.perform(get("/api/v1/var/official").param("asOf", DAY.toString()).header("Authorization", "Bearer "
                + tokens.mint("rita", List.of("risk"), 300))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(rec.at("/nodes/MGMT~BANK/var").asDouble()).isEqualTo(morningBank);
        assertThat(ask("/api/v1/var/scope", "BANK", DAY, null).get("var").asDouble()).isNotEqualTo(morningBank);
        // an intraday signal never restates it
        assertThat(official.capture(DAY).get("status")).isEqualTo("unchanged");
    }

    @Test
    @Order(4)
    void aFullRunOfTheDateDropsTheIntradayLayer() throws Exception {
        String before = svc.loader().version(DAY);
        List<Map<String, Object>> v = VarIntradayLake.morningVectors(DAY);
        v.forEach(row -> {
            row.put("BATCH_ID", "B-" + DAY + "-02");
            row.put("COMPUTED_AT", DAY + "T18:00:00Z");
        });
        VarIntradayLake.writeVectors(lake, v, "overwrite-dates");
        List<Map<String, Object>> p = VarIntradayLake.morningPvs(DAY);
        p.forEach(row -> {
            row.put("BATCH_ID", "B-" + DAY + "-02");
            row.put("COMPUTED_AT", DAY + "T18:00:00Z");
        });
        VarIntradayLake.writePvs(lake, p, "overwrite-dates");
        long until = System.currentTimeMillis() + 20_000;
        while (svc.loader().version(DAY).equals(before) && System.currentTimeMillis() < until) {
            Thread.sleep(200);
        }
        official.onLoad("scenario-pv", DAY);
        JsonNode bank = ask("/api/v1/var/scope", "BANK", DAY, null);
        assertThat(bank.get("intraday").isNull()).isTrue();
        assertThat(bank.get("var").asDouble()).isEqualTo(morningBank);
    }

    @Test
    @Order(5)
    void aFewHundredBatchesOnOneDateKeepOnlyTheLatestPerTradeAndDoNotSlowDown() throws Exception {
        ask("/api/v1/var/scope", "BANK", MANY, null);
        List<String> pool = List.of("T1", "T2", "T3", "T4", "T5", "T9");
        Map<String, VarLakeFixture.Trade> byId = new java.util.HashMap<>();
        VarLakeFixture.trades().forEach(t -> byId.put(t.id(), t));
        Map<String, Double> latest = new java.util.HashMap<>();
        Map<String, String> latestAt = new java.util.HashMap<>();
        java.util.SplittableRandom r = new java.util.SplittableRandom(5);
        int batches = 200;
        long[] ms = new long[batches];
        java.time.Instant t0 = java.time.Instant.parse(MANY + "T08:00:00Z");
        for (int b = 0; b < batches; b++) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int k = 0; k < 3; k++) {
                String id = pool.get(r.nextInt(pool.size()));
                VarLakeFixture.Trade t = byId.get(id);
                double f = 0.5 + r.nextInt(30) / 10.0;
                String at = t0.plusSeconds(b * 10L + k - (r.nextInt(10) == 0 ? 400 : 0)).toString();   // one in ten sent late, older
                rows.add(VarIntradayLake.vector(MANY, id, t.book(), t.desk(), t.le(), t.ccy(), t.qty() * f, VarLakeFixture.scale((double[]) t.v(), f),
                        t.status(), "INTRADAY", "B-M" + b, at));
                if (latestAt.get(id) == null || at.compareTo(latestAt.get(id)) > 0) {
                    latestAt.put(id, at);
                    latest.put(id, f);
                }
            }
            long s0 = System.nanoTime();
            batch(MANY, "pnl-vector", rows);
            ms[b] = (System.nanoTime() - s0) / 1_000_000;
        }
        JsonNode bank = ask("/api/v1/var/scope", "BANK", MANY, null);
        assertThat(bank.at("/intraday/batchCount").asInt()).isEqualTo(batches);
        assertThat(bank.at("/intraday/trades").asInt()).isEqualTo(latest.size());
        // applying does not slow down with the batches applied before (only the current P&L per trade is kept)
        double first = java.util.Arrays.stream(ms, 10, 60).average().orElse(0);
        double last = java.util.Arrays.stream(ms, batches - 50, batches).average().orElse(0);
        System.out.printf("intraday: %d batches, the first 50 applied in %.1f ms each, the last 50 in %.1f ms each (with the lake write)%n", batches,
                first, last);
        assertThat(last).isLessThan(Math.max(3 * first, first + 250));
        // the same final state loaded as one run on another date: equal to the cent
        LocalDate one = MANY_AS_ONE_RUN;

        List<Map<String, Object>> v = new ArrayList<>();
        for (Map<String, Object> row : VarIntradayLake.morningVectors(one)) {
            Double f = latest.get((String) row.get("TRADE_ID"));
            if (f != null) {
                VarLakeFixture.Trade t = byId.get((String) row.get("TRADE_ID"));
                row.put("QUANTITY", t.qty() * f);
                row.put("PNL", VarLakeFixture.scale((double[]) t.v(), f));
            }
            v.add(row);
        }
        VarIntradayLake.writeVectors(lake, v, "overwrite-dates");
        Thread.sleep(1500);
        for (String node : svc.date(MANY).book().nodes().keySet()) {
            JsonNode a = ask("/api/v1/var/scope/path?size=1", "NODE:" + node, MANY, null);
            JsonNode e = ask("/api/v1/var/scope/path?size=1", "NODE:" + node, one, null);
            assertThat(a.get("series").toString()).as(node).isEqualTo(e.get("series").toString());
            assertThat(a.get("var").asDouble()).as(node).isEqualTo(e.get("var").asDouble());
        }
        // a restart rebuilds the same from the lake: the morning run, then every intraday batch at once
        double before = svc.scoped(null, "NODE:MGMT~BANK", MANY, null).measures().var();
        double[] vb = svc.scoped(null, "NODE:MGMT~BANK", MANY, null).vector();
        svc.reload(MANY);
        VarService.Scoped again = svc.scoped(null, "NODE:MGMT~BANK", MANY, null);
        assertThat(again.date().book().intraday().info().trades()).isEqualTo(latest.size());
        assertThat(again.measures().var()).isCloseTo(before, within(0.005));
        for (int j = 0; j < vb.length; j++) {
            assertThat(again.vector()[j]).isCloseTo(vb[j], within(0.005));
        }
    }
}
