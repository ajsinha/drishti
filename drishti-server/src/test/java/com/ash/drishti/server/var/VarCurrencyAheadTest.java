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

import com.ash.drishti.compute.var.VarCalc;
import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
 * The listed reporting currencies built ahead and the per-trade-currency totals (VAR_GUIDE.md section 15.11), over the scenario-PV
 * fixture ({@link VarPvFixture}: a EUR and a CAD trade beside USD P&amp;L vectors): after the USD book loads, EUR and CAD are built in the
 * background into their own hot files, JPY (no FX on the date) is said not built; the page's currencies list the listed ones, the
 * ready ones and every currency with FX; a node's VaR in EUR from the totals equals the book converted trade by trade; the "In other
 * currencies" answer; a trade's currency and its base PV in it.
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
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.risk-lake.settings.layout.scenario-pv.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,BASE_PV,VALUATION_DATE,BASE_VALUATION_DATE,TRADE_STATUS,"
                + "MATURITY_DATE,STATUS,SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS",
        "drishti.sources.connectors.risk-lake.settings.layout.scenario-pv.vectors=SCENARIO_PV",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.scenario-pv=risk-lake",
        "drishti.sources.routes.fx-scenario=risk-lake", "drishti.sources.routes.fx-spot=risk-lake",
        "drishti.var.official.enabled=false", "drishti.var.official.on-start=false",
        "drishti.var.currencies=EUR,CAD,JPY", "drishti.var.currency-retention-dates=2",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VarCurrencyAheadTest {

    static Path lake;
    static Path hot;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-ahead-lake-" + System.nanoTime())).toAbsolutePath();
        hot = lake.resolve("hot");
        VarLakeFixture.write(lake, VarLakeFixture.DAY.minusDays(1), 1.0, false, false);
        VarPvFixture.write(lake, VarLakeFixture.DAY.minusDays(1));
        VarLakeFixture.write(lake);
        VarPvFixture.write(lake, VarLakeFixture.DAY);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.tier.dir", hot::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    final ObjectMapper json = new ObjectMapper();

    private String rita() {
        return "Bearer " + tokens.mint("rita", List.of("risk"), 300);
    }

    private JsonNode call(String url, Map<String, String> params) throws Exception {
        var req = get(url).param("asOf", VarLakeFixture.DAY.toString()).header("Authorization", rita());
        for (Map.Entry<String, String> e : params.entrySet()) {
            req = req.param(e.getKey(), e.getValue());
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode ask(String url, String scope, String ccy) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("scope", scope);
        if (ccy != null) {
            p.put("ccy", ccy);
        }
        return call(url, p);
    }

    @Test
    @Order(1)
    void theListedCurrenciesAreBuiltAheadAfterTheUsdBook() throws Exception {
        assertThat(svc.props().currencies()).containsExactly("USD", "EUR", "CAD", "JPY");
        assertThat(svc.props().currencyRetentionDates()).isEqualTo(2);
        ask("/api/v1/var/scope", "BANK", null);                       // the USD book loads; the listed currencies follow in the background
        String day = VarLakeFixture.DAY.toString();
        long until = System.currentTimeMillis() + 30_000;
        Map<String, String> ahead = svc.builtAhead();
        while (System.currentTimeMillis() < until && (!done(ahead, day + "|EUR") || !done(ahead, day + "|CAD") || !done(ahead, day + "|JPY"))) {
            Thread.sleep(100);
            ahead = svc.builtAhead();
        }
        assertThat(ahead.get(day + "|EUR")).as(ahead.toString()).startsWith("built ahead in");
        assertThat(ahead.get(day + "|CAD")).startsWith("built ahead in");
        assertThat(ahead.get(day + "|JPY")).startsWith("not built: ").contains("no JPY FX");
        // each its own hot file, beside the USD book's
        for (String c : List.of("EUR", "CAD")) {
            try (Stream<Path> s = Files.list(hot.resolve(day + "-" + c))) {
                assertThat(s.anyMatch(p -> p.getFileName().toString().endsWith(".vhot"))).as(c).isTrue();
            }
        }
        assertThat(Files.exists(hot.resolve(day + "-JPY"))).isFalse();
        JsonNode cur = json.readTree(mvc.perform(get("/api/v1/var/currencies").param("asOf", day).header("Authorization", rita()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(cur.get("listed").toString()).isEqualTo("[\"USD\",\"EUR\",\"CAD\",\"JPY\"]");
        assertThat(cur.at("/ready/EUR").asBoolean()).isTrue();
        assertThat(cur.at("/ready/JPY").asBoolean()).isFalse();
        assertThat(cur.get("available").toString()).contains("GBP").contains("CAD").doesNotContain("JPY");
        JsonNode st = json.readTree(mvc.perform(get("/api/v1/var/status").header("Authorization", rita())).andReturn().getResponse()
                .getContentAsString());
        assertThat(st.at("/currencies/listed").toString()).contains("EUR");
        assertThat(st.at("/tier/currencies/files").asInt()).isGreaterThanOrEqualTo(2);
    }

    private static boolean done(Map<String, String> ahead, String key) {
        String s = ahead.get(key);
        return s != null && !s.equals("queued") && !s.equals("building");
    }

    @Test
    @Order(2)
    void aNodesVarInEurFromTheTotalsEqualsTheBookConvertedTradeByTrade() {
        VarCalc calc = svc.calc();
        VarDate eur = svc.date(VarLakeFixture.DAY, "EUR");
        for (String node : eur.book().nodes().keySet()) {
            VarService.Scoped s = svc.scoped(null, "NODE:" + node, VarLakeFixture.DAY, "EUR");
            double[] traded = eur.book().aggregate(node);                 // every trade converted on its own, then summed
            for (int j = 0; j < traded.length; j++) {
                assertThat(s.vector()[j]).as(node + " " + j).isCloseTo(traded[j], within(0.005));
            }
            assertThat(s.measures().var()).as(node).isCloseTo(calc.measures(traded).var(), within(0.005));
            assertThat(s.measures().es()).as(node).isCloseTo(calc.measures(traded).es(), within(0.005));
        }
    }

    @Test
    @Order(3)
    void theScopeInOtherCurrenciesIsOneRowPerListedCurrency() throws Exception {
        JsonNode t = ask("/api/v1/var/scope/currencies", "DESK:DESK-Y", "EUR");
        assertThat(t.get("currency").asText()).isEqualTo("EUR");
        JsonNode rows = t.get("rows");
        assertThat(rows.size()).isEqualTo(4);                            // USD, EUR, CAD, JPY
        Map<String, JsonNode> by = new LinkedHashMap<>();
        rows.forEach(r -> by.put(r.get("currency").asText(), r));
        assertThat(by.get("EUR").get("shown").asBoolean()).isTrue();
        assertThat(by.get("EUR").get("var").asDouble()).isEqualTo(ask("/api/v1/var/scope", "DESK:DESK-Y", "EUR").get("var").asDouble());
        assertThat(by.get("USD").get("var").asDouble()).isEqualTo(ask("/api/v1/var/scope", "DESK:DESK-Y", null).get("var").asDouble());
        assertThat(by.get("CAD").get("var").asDouble()).isEqualTo(ask("/api/v1/var/scope", "DESK:DESK-Y", "CAD").get("var").asDouble());
        // the USD VaR in EUR at today's rate (1.20 USD per EUR), and its difference from the EUR VaR
        double usdInEur = by.get("USD").get("var").asDouble() / 1.20;
        assertThat(by.get("USD").get("varInPage").asDouble()).isCloseTo(usdInEur, within(1.0));
        assertThat(by.get("USD").get("difference").asDouble()).isCloseTo(usdInEur - by.get("EUR").get("var").asDouble(), within(1.0));
        assertThat(by.get("JPY").get("note").asText()).contains("no JPY scenario FX");
        assertThat(t.get("tradeCurrencies").toString()).contains("EUR").contains("CAD").contains("USD");
        // a page in a currency not listed adds its row
        assertThat(ask("/api/v1/var/scope/currencies", "BANK", "GBP").get("rows").size()).isEqualTo(5);
    }

    @Test
    @Order(4)
    void aTradeSaysItsCurrencyAndItsBasePvInIt() throws Exception {
        VarDate usd = svc.date(VarLakeFixture.DAY);
        Map<String, Object> p1 = new LinkedHashMap<>();
        VarTradeLinks.currency(usd, usd.book().tradeOrdinal("P1"), p1);
        assertThat(p1).containsEntry("TRADE_CURRENCY", "EUR").containsEntry("TRADE_CURRENCY_FROM", "its scenario PVs")
                .containsEntry("BASE_PV", "EUR 1,000.00");
        Map<String, Object> t1 = new LinkedHashMap<>();
        VarTradeLinks.currency(usd, usd.book().tradeOrdinal("T1"), t1);
        assertThat(String.valueOf(t1.get("TRADE_CURRENCY_FROM"))).startsWith("the trade's CURRENCY");
        // the contributions by trade carry it as a column
        JsonNode parts = ask("/api/v1/var/scope/contributions?by=trade", "BOOK:BOOK-3", "EUR");
        boolean seen = false;
        for (JsonNode r : parts.get("rows")) {
            if (r.get("key").asText().equals("P2")) {
                assertThat(r.get("tradeCurrency").asText()).isEqualTo("CAD");
                seen = true;
            }
        }
        assertThat(seen).isTrue();
    }
}
