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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.dist.XlsxFormulas;
import com.ash.drishti.server.playground.LibreOfficeRecalc;
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
import java.util.Random;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The VaR Playground through the server (VAR_GUIDE.md section 3.10) on a lake of 611 trades over 509 scenarios: scenario PVs in EUR and
 * GBP and USD P&amp;L vectors; one position, a desk of 50 (in EUR) and a desk of 560 (500 shown and Rest of scope). The grid against the
 * VaR engine's own answer, the export recalculated by headless LibreOffice against it to the cent, the ids, the limits and the refusals,
 * the estimate and the empty template.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.var.scenario-window=all", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy", "drishti.sources.connectors.risk-lake.kinds[3]=scenario-pv",
        "drishti.sources.connectors.risk-lake.kinds[4]=fx-scenario",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.risk-lake.settings.layout.scenario-pv.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,BASE_PV,VALUATION_DATE,BASE_VALUATION_DATE,TRADE_STATUS,"
                + "MATURITY_DATE,STATUS,SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS",
        "drishti.sources.connectors.risk-lake.settings.layout.scenario-pv.vectors=SCENARIO_PV",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.scenario-pv=risk-lake", "drishti.sources.routes.fx-scenario=risk-lake",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false", "drishti.var.currencies=USD",
        "drishti.var.hot-dates=1", "drishti.var.playground.max-ids=3",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarPlaygroundApiTest {

    static final LocalDate DAY = VarLakeFixture.DAY;
    static final int N = 509;
    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-playground-lake-" + System.nanoTime())).toAbsolutePath();
        write(lake);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
    }

    static List<String> dates() {
        List<String> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2024, 9, 2);
        while (out.size() < N) {
            if (d.getDayOfWeek().getValue() < 6) {
                out.add(d.toString());
            }
            d = d.plusDays(1);
        }
        return out;
    }

    /** The books: BOOK-ONE (1 trade, EUR scenario PVs), BOOK-FIFTY (50: EUR and GBP PVs, USD P&amp;L), BOOK-MANY (560, mostly P&amp;L). */
    static void write(Path root) throws Exception {
        Path work = Files.createDirectories(root.resolve("_work"));
        Random r = new Random(3);
        double[] f = new double[N];
        for (int j = 0; j < N; j++) {
            f[j] = r.nextGaussian() * (j % 101 == 17 ? 5 : 1);
        }
        List<Map<String, Object>> pnl = new ArrayList<>();
        List<Map<String, Object>> pv = new ArrayList<>();
        int[] counts = {1, 50, 560};
        String[] books = {"BOOK-ONE", "BOOK-FIFTY", "BOOK-MANY"};
        String[] desks = {"DESK-ONE", "DESK-FIFTY", "DESK-MANY"};
        for (int b = 0; b < 3; b++) {
            for (int i = 0; i < counts[b]; i++) {
                String id = "T-" + b + "-" + i;
                double scale = (0.3 + r.nextDouble()) * 1e6;
                double beta = r.nextGaussian();
                boolean isPv = b == 0 || (b == 1 ? i % 3 != 2 : i % 7 == 0);
                String ccy = isPv ? (i % 2 == 0 ? "EUR" : "GBP") : "USD";
                List<Double> v = new ArrayList<>();
                double base = scale * (r.nextBoolean() ? 1 : -1);
                for (int j = 0; j < N; j++) {
                    double move = scale * 0.01 * (beta * f[j] + 0.4 * r.nextGaussian());
                    v.add(isPv ? base + move : Math.round(move * 100) / 100.0);
                }
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("TRADE_ID", id);
                d.put("BUSINESS_DATE", DAY.toString());
                d.put("SCENARIO_SET_ID", "HS-509");
                d.put("BATCH_ID", "B-" + DAY + "-01");
                d.put("LEGAL_ENTITY", "LE-A");
                d.put("DIVISION", "DIV-M");
                d.put("DESK", desks[b]);
                d.put("TT_BOOK", books[b]);
                d.put("PORTFOLIO", books[b]);
                d.put("PRODUCT_GROUP", "Rates");
                d.put("PRODUCT_TYPE", "IRS");
                d.put("CURRENCY", ccy);
                d.put("COUNTERPARTY", "CP-1");
                d.put("DIRECTION", "LONG");
                d.put("QUANTITY", 1.0);
                d.put("TRADE_STATUS", "VALIDATED");
                d.put("MATURITY_DATE", "2030-01-15");
                d.put("STATUS", "ok");
                if (isPv) {
                    d.put("VALUE_TYPE", "PV");
                    d.put("GRAIN", "TRADE");
                    d.put("SOURCE_SYSTEM", "ENDUR");
                    d.put("SCENARIO_PV_ID", "SPV-" + id);
                    d.put("BASE_PV", base);
                    d.put("VALUATION_DATE", DAY.toString());
                    d.put("BASE_VALUATION_DATE", DAY.toString());
                    d.put("SCENARIO_PV", v);
                    pv.add(d);
                } else {
                    d.put("VECTOR_ID", "PNL-" + id);
                    d.put("PV", base);
                    d.put("REPORTING_CCY", "USD");
                    d.put("PNL", v);
                    pnl.add(d);
                }
            }
        }
        VarLakeFixture.table(root, work, "pnl-vector", "VECTOR_ID", pnl, List.of("TRADE_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK", "PORTFOLIO",
                "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY", "COUNTERPARTY", "DIRECTION", "QUANTITY", "PV", "TRADE_STATUS", "MATURITY_DATE", "STATUS",
                "REPORTING_CCY", "SCENARIO_SET_ID", "BATCH_ID"), "PNL");
        VarLakeFixture.table(root, work, "scenario-pv", "SCENARIO_PV_ID", pv, List.of("TRADE_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK",
                "PORTFOLIO", "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY", "COUNTERPARTY", "DIRECTION", "QUANTITY", "BASE_PV", "VALUATION_DATE",
                "BASE_VALUATION_DATE", "TRADE_STATUS", "MATURITY_DATE", "STATUS", "SCENARIO_SET_ID", "BATCH_ID"), "SCENARIO_PV");
        VarLakeFixture.table(root, work, "scenario-set", "SCENARIO_SET_ID",
                List.of(Map.of("SCENARIO_SET_ID", "HS-509", "BUSINESS_DATE", DAY.toString(), "SCENARIO_DATES", dates())), List.of(), null);
        List<Map<String, Object>> fx = new ArrayList<>();
        fx.add(rate("EUR", 1.0835, r));
        fx.add(rate("GBP", 1.2994, r));
        VarLakeFixture.table(root, work, "fx-scenario", "FX_SCENARIO_ID", fx, List.of("CURRENCY", "RATE", "SCENARIO_SET_ID"), null);
        List<Map<String, Object>> nodes = new ArrayList<>();
        nodes.add(node("MGMT~BANK", "bank", null, List.of(books)));
        for (int b = 0; b < 3; b++) {
            nodes.add(node("MGMT~" + desks[b], "desk", "MGMT~BANK", List.of(books[b])));
        }
        VarLakeFixture.table(root, work, "risk-hierarchy", "NODE_ID", nodes, List.of("NODE_ID", "LIMIT_VAR"), null);
    }

    static Map<String, Object> rate(String ccy, double base, Random r) {
        List<Double> rates = new ArrayList<>();
        for (int j = 0; j < N; j++) {
            rates.add(base * (1 + 0.005 * r.nextGaussian()));
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("FX_SCENARIO_ID", "FXS-" + ccy + "-HS-509");
        d.put("BUSINESS_DATE", DAY.toString());
        d.put("SCENARIO_SET_ID", "HS-509");
        d.put("CURRENCY", ccy);
        d.put("QUOTE", "USD per 1 " + ccy);
        d.put("RATE", base);
        d.put("RATES", rates);
        return d;
    }

    static Map<String, Object> node(String id, String level, String parent, List<String> books) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("NODE_ID", id);
        m.put("HIERARCHY", "MGMT");
        m.put("LEVEL", level);
        m.put("PARENT_ID", parent);
        m.put("NAME", id.substring(5));
        m.put("BOOKS", books);
        m.put("LIMIT_VAR", 1e9);
        m.put("BUSINESS_DATE", DAY.toString());
        return m;
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    private MockHttpServletRequestBuilder req(String path, String... params) {
        var q = get(path).param("asOf", DAY.toString()).header("Authorization", "Bearer " + tokens.mint("rita", List.of("risk"), 300));
        for (int i = 0; i < params.length; i += 2) {
            q = q.param(params[i], params[i + 1]);
        }
        return q;
    }

    private JsonNode ask(String path, ResultMatcher expect, String... params) throws Exception {
        return json.readTree(mvc.perform(req(path, params)).andExpect(expect).andReturn().getResponse().getContentAsString());
    }

    private byte[] download(String path, String... params) throws Exception {
        MvcResult started = mvc.perform(req(path, params)).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    void theGridOfADeskInEurIsTheEnginesScope() throws Exception {
        JsonNode g = ask("/api/v1/var/playground", status().isOk(), "scope", "DESK:DESK-FIFTY", "ccy", "EUR");
        JsonNode h = ask("/api/v1/var/scope", status().isOk(), "scope", "DESK:DESK-FIFTY", "ccy", "EUR");
        assertThat(g.get("currency").asText()).isEqualTo("EUR");
        assertThat(g.get("shown").asInt()).isEqualTo(50);
        assertThat(g.get("truncated").asBoolean()).isFalse();                // a rounding column at most (no positions)
        assertThat(g.get("scenarios")).hasSize(N);
        assertThat(Math.abs(g.at("/figures/var").asDouble() - h.get("var").asDouble())).isLessThanOrEqualTo(0.5);
        assertThat(Math.abs(g.at("/figures/var").asDouble() - g.at("/engine/var").asDouble())).isLessThanOrEqualTo(0.0100001);          // both rounded to the cent in the answer
        for (JsonNode c : g.get("checks")) {
            assertThat(c.get("ok").asBoolean()).as(c.get("name").asText()).isTrue();
        }
        JsonNode first = g.get("positions").get(0);
        assertThat(first.get("pnl")).hasSize(N);
        assertThat(Math.abs(first.get("componentVar").asDouble() - first.get("engineComponentVar").asDouble())).isLessThanOrEqualTo(0.0100001);
        assertThat(g.at("/formulas/pnl").asText()).contains("INDEX(FX!", "Config!$I${C}", "Data!{D}");
        assertThat(g.at("/export/choices")).hasSize(5);
        assertThat(g.at("/export/choices/4/label").asText()).isEqualTo("All (50)");
        java.util.Set<String> forms = new java.util.TreeSet<>();
        g.get("positions").forEach(p -> forms.add(p.get("form").asText() + ":" + p.get("currency").asText()));
        assertThat(forms).contains("PV:EUR", "PV:GBP", "PnL:USD");
        JsonNode pvView = ask("/api/v1/var/playground", status().isOk(), "scope", "DESK:DESK-FIFTY", "view", "pv");
        assertThat(pvView.at("/positions/0/pv")).hasSize(N);
        JsonNode fxView = ask("/api/v1/var/playground", status().isOk(), "scope", "DESK:DESK-FIFTY", "view", "fx");
        assertThat(fxView.at("/fx/EUR/rates")).hasSize(N);
    }

    @Test
    void aScopeOverFiveHundredShowsFiveHundredAndRestOfScope() throws Exception {
        JsonNode g = ask("/api/v1/var/playground", status().isOk(), "scope", "DESK:DESK-MANY", "horizon", "10d", "method", "sum");
        assertThat(g.get("shown").asInt()).isEqualTo(500);
        assertThat(g.get("total").asInt()).isEqualTo(560);
        assertThat(g.at("/rest/count").asInt()).isEqualTo(60);
        assertThat(g.at("/lens/method").asText()).isEqualTo("SUM");
        JsonNode h = ask("/api/v1/var/scope", status().isOk(), "scope", "DESK:DESK-MANY", "horizon", "10d", "method", "sum");
        assertThat(Math.abs(g.at("/figures/headlineVar").asDouble() - h.get("var").asDouble())).isLessThanOrEqualTo(0.5);
        for (JsonNode c : g.get("checks")) {
            assertThat(c.get("ok").asBoolean()).as(c.get("name").asText()).isTrue();
        }
    }

    @Test
    void theExportOfFiveHundredAndRestRecalculatesToTheEngine() throws Exception {
        byte[] xlsx = download("/api/v1/var/playground.xlsx", "scope", "DESK:DESK-MANY", "count", "500");
        assertThat(XlsxFormulas.scan(xlsx)).isEmpty();
        JsonNode h = ask("/api/v1/var/scope", status().isOk(), "scope", "DESK:DESK-MANY");
        Assumptions.assumeTrue(LibreOfficeRecalc.available(), "LibreOffice is not installed here");
        Path work = Files.createDirectories(Path.of("target", "playground-api-" + System.nanoTime()));
        Path saved = LibreOfficeRecalc.recalc(LibreOfficeRecalc.write(work, "many.xlsx", xlsx), work);
        Map<String, Object> main = LibreOfficeRecalc.cells(saved, "Main");
        assertThat(main.get("B3")).isEqualTo(VarPlaygroundBook.ALL_PASS);
        assertThat(Math.abs((Double) main.get("F7") - h.get("var").asDouble())).isLessThanOrEqualTo(0.5);
        for (int r = 5; r <= 15; r++) {
            assertThat(Math.abs((Double) main.get("F" + r) - (Double) main.get("G" + r))).as(String.valueOf(main.get("E" + r))).isLessThan(0.005);
        }
        Map<String, Object> config = LibreOfficeRecalc.cells(saved, "Config");
        assertThat(config.get("A502")).isEqualTo("Rest of scope");
    }

    @Test
    void idsCountsAndTheRefusals() throws Exception {
        JsonNode g = ask("/api/v1/var/playground", status().isOk(), "ids", "T-0-0,T-1-0");
        assertThat(g.get("shown").asInt()).isEqualTo(2);
        ask("/api/v1/var/playground", status().isBadRequest(), "ids", "T-1-1,T-1-2,T-1-3,T-1-4");
        ask("/api/v1/var/playground", status().isBadRequest());
        ask("/api/v1/var/playground.xlsx", status().isBadRequest(), "scope", "DESK:DESK-ONE", "count", "lots");
        JsonNode est = ask("/api/v1/var/playground/estimate", status().isOk(), "scope", "BANK");
        assertThat(est.get("positions").asInt()).isEqualTo(611);
        assertThat(est.get("choices")).hasSize(5);
        assertThat(est.at("/choices/1/default").asBoolean()).isTrue();
        assertThat(est.at("/choices/1/apps").toString()).contains("Excel", "Google Sheets");
        byte[] one = download("/api/v1/var/playground.xlsx", "scope", "DESK:DESK-ONE", "ccy", "EUR");
        assertThat(XlsxFormulas.scan(one)).isEmpty();
        byte[] blank = download("/api/v1/var/playground/template", "positions", "5", "scenarios", "40");
        assertThat(XlsxFormulas.scan(blank)).isEmpty();
        assertThat(new String(blank, 0, 2)).isEqualTo("PK");
    }
}
