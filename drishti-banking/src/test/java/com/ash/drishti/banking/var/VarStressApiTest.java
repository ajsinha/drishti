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
package com.ash.drishti.banking.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.banking.compute.var.VarCalc;
import com.ash.drishti.banking.compute.var.VarLens;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Stressed VaR (VAR_GUIDE.md section 18.8). A date's lake holds two scenario sets: the normal one (40 days) and a stress set
 * ({@code SET_TYPE: STRESS}, 30 days of 2008) whose trades' rows sit in the same {@code pnl-vector} table under their own
 * {@code SCENARIO_SET_ID}. The normal answers are those of the normal rows alone (the stress rows never reach the normal book); the
 * stressed answers ({@code set=stressed}) are the stress rows' own, for the bank and every node, at 1 day and 10 days (√10 and the sum of 21
 * windows); limit use is never shown for stressed VaR and it is not backtested; a date without a stress set answers {@code 404} in words,
 * never zero; another reporting currency and an unknown set are refused; the stress book has its own hot file; the official record carries
 * each node's stressed VaR and ES, and the 12-week average reads them.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=var-limit", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=VALUE_TYPE,GRAIN,TRADE_ID,POSITION_ID,CURRENCY,SOURCE_SYSTEM,"
                + "LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,COUNTERPARTY,DIRECTION,QUANTITY,PV,"
                + "TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,SCENARIO_SET_ID,BATCH_ID,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.var-limit=risk-lake",
        "drishti.var.hierarchies.MGMT.title=Management", "drishti.var.hierarchies.MGMT.levels=DESK > TT_BOOK",
        "drishti.var.hierarchies.MGMT.level-names=desk > book", "drishti.var.hierarchies.MGMT.ids=leaf",
        "drishti.var.hierarchies.MGMT.limits=var-limit", "drishti.var.hierarchies.LEGAL.title=Legal entity",
        "drishti.var.hierarchies.LEGAL.levels=LEGAL_ENTITY", "drishti.var.hierarchies.LEGAL.ids=leaf",
        "drishti.var.hierarchies.LEGAL.limits=var-limit",
        "drishti.sources.routes.portfolio=risk-lake",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false", "drishti.var.currencies=USD",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarStressApiTest {

    /** A date with a normal set of 40 days and a stress set of 30. */
    static final LocalDate DAY = VarLakeFixture.DAY;
    /** A date with its normal set only. */
    static final LocalDate PLAIN = DAY.minusDays(1);
    static final int N = 40;
    static final int S = 30;
    static final String STRESS = "HS-STRESS-T";
    static final String[][] TRADES = {{"T1", "BOOK-1", "DESK-X", "LE-A"}, {"T3", "BOOK-1", "DESK-X", "LE-A"}, {"T4", "BOOK-2", "DESK-X", "LE-A"},
            {"T5", "BOOK-3", "DESK-Y", "LE-B"}};
    static Path lake;
    /** The input tables' files as written, before any load: a load and a capture leave them as they are (compute-first, row B5). */
    static Map<String, Long> inputs;

    /** Every file of the input tables (risk/...), with its size: the hot files, the work folder and the published results left out. */
    static Map<String, Long> tables(Path root) throws java.io.IOException {
        Map<String, Long> out = new java.util.TreeMap<>();
        try (java.util.stream.Stream<Path> s = Files.walk(root.resolve("risk"))) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                out.put(root.relativize(f).toString(), Files.size(f));
            }
        }
        return out;
    }

    static List<String> days(LocalDate from, int n) {
        List<String> out = new ArrayList<>();
        for (LocalDate d = from; out.size() < n; d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() < 6) {
                out.add(d.toString());
            }
        }
        return out;
    }

    /** A trade's vector of a set: whole numbers, the stress set's three times wider. */
    static double[] vector(String trade, String set, int n) {
        java.util.SplittableRandom r = new java.util.SplittableRandom((trade + set).hashCode());
        double[] v = new double[n];
        for (int j = 0; j < n; j++) {
            v[j] = Math.round(r.nextGaussian() * (set.equals(STRESS) ? 3000 : 1000));
        }
        return v;
    }

    static Map<String, Object> row(LocalDate day, String[] t, String set, int n) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("VALUE_TYPE", "PNL");
        d.put("GRAIN", "TRADE");
        d.put("TRADE_ID", t[0]);
        d.put("CURRENCY", "USD");
        d.put("SOURCE_SYSTEM", "MUREX");
        d.put("VECTOR_ID", "PNL-" + t[0] + "-" + set);
        d.put("BUSINESS_DATE", day.toString());
        d.put("SCENARIO_SET_ID", set);
        d.put("BATCH_ID", "B-" + day + "-01");
        d.put("LEGAL_ENTITY", t[3]);
        d.put("DIVISION", "DIV-M");
        d.put("DESK", t[2]);
        d.put("TT_BOOK", t[1]);
        d.put("PORTFOLIO", t[1]);
        d.put("PRODUCT_GROUP", "Rates");
        d.put("PRODUCT_TYPE", "IRS");
        d.put("COUNTERPARTY", "CP-1");
        d.put("DIRECTION", "LONG");
        d.put("QUANTITY", 1.0);
        d.put("PV", 1000.0);
        d.put("TRADE_STATUS", "VALIDATED");
        d.put("MATURITY_DATE", "2030-01-15");
        d.put("STATUS", "ok");
        d.put("REPORTING_CCY", "USD");
        d.put("PNL", vector(t[0], set, n));
        return d;
    }

    static void write(LocalDate day, boolean withStress) throws Exception {
        write(lake, day, withStress);
    }

    /** The date's tables in {@code lake} (VarMrCubeCapTest writes its own lake the same way). */
    static void write(Path lake, LocalDate day, boolean withStress) throws Exception {
        VarLakeFixture.write(lake, day, 1.0, false, false);              // the hierarchy, the portfolios
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String[] t : TRADES) {
            rows.add(row(day, t, "HS-40", N));
            if (withStress) {
                rows.add(row(day, t, STRESS, S));                        // the same table, its own set
            }
        }
        Path work = Files.createDirectories(lake.resolve("_work"));
        VarLakeFixture.table(lake, work, "pnl-vector", "VECTOR_ID", rows, List.of("TRADE_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK",
                "PORTFOLIO", "PRODUCT_GROUP", "PRODUCT_TYPE", "COUNTERPARTY", "DIRECTION", "QUANTITY", "PV", "TRADE_STATUS", "MATURITY_DATE",
                "STATUS", "REPORTING_CCY", "SCENARIO_SET_ID", "BATCH_ID"), "PNL");
        List<Map<String, Object>> sets = new ArrayList<>();
        sets.add(Map.of("SCENARIO_SET_ID", "HS-40", "BUSINESS_DATE", day.toString(), "SCENARIO_DATES", days(LocalDate.of(2026, 7, 1), N)));
        if (withStress) {
            sets.add(Map.of("SCENARIO_SET_ID", STRESS, "SET_TYPE", "STRESS", "SYNTHETIC", true, "DESCRIPTION", "a synthetic stress month",
                    "BUSINESS_DATE", day.toString(), "SCENARIO_DATES", days(LocalDate.of(2008, 9, 1), S)));
        }
        VarLakeFixture.table(lake, work, "scenario-set", "SCENARIO_SET_ID", sets, List.of(), null);
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-stress-lake-" + System.nanoTime())).toAbsolutePath();
        write(PLAIN, false);
        write(DAY, true);
        inputs = tables(lake);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
        r.add("drishti.var.tier.dir", () -> lake.resolve("hot").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    @Autowired VarOfficial official;
    final ObjectMapper json = new ObjectMapper();

    private org.springframework.test.web.servlet.ResultActions call(String url, String scope, LocalDate day) throws Exception {
        return mvc.perform(get(url).param("scope", scope).param("asOf", day.toString()).header("Authorization", "Bearer "
                + tokens.mint("rita", List.of("risk"), 300)));
    }

    private JsonNode ask(String url, String scope, LocalDate day) throws Exception {
        return json.readTree(call(url, scope, day).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** The sum of the trades' vectors of a set under a node (by its books). */
    static double[] sum(String set, int n, List<String> books) {
        double[] v = new double[n];
        for (String[] t : TRADES) {
            if (books.contains(t[1])) {
                double[] x = vector(t[0], set, n);
                for (int j = 0; j < n; j++) {
                    v[j] += x[j];
                }
            }
        }
        return v;
    }

    @Test
    void theNormalAndTheStressedAnswersAreEachTheirOwnSetsRows() throws Exception {
        VarCalc calc = svc.calc();
        List<String> all = List.of("BOOK-1", "BOOK-2", "BOOK-3");
        JsonNode normal = ask("/api/v1/risk/var/scope", "BANK", DAY);
        assertThat(normal.get("scenarios").asInt()).isEqualTo(N);
        assertThat(normal.get("set").asText()).isEqualTo("NORMAL");
        assertThat(normal.get("var").asDouble()).isCloseTo(calc.measures(sum("HS-40", N, all)).var(), within(0.5));
        JsonNode stressed = ask("/api/v1/risk/var/scope?set=stressed", "BANK", DAY);
        VarCalc.Measures m = calc.measures(sum(STRESS, S, all));
        assertThat(stressed.get("scenarios").asInt()).isEqualTo(S);
        assertThat(stressed.get("set").asText()).isEqualTo("STRESSED");
        assertThat(stressed.get("var").asDouble()).isCloseTo(m.var(), within(0.5));
        assertThat(stressed.get("es").asDouble()).isCloseTo(m.es(), within(0.5));
        assertThat(stressed.get("k").asInt()).isEqualTo(1);                                    // ceil(30 x 0.01)
        assertThat(stressed.at("/lens/stressSet/id").asText()).isEqualTo(STRESS);
        assertThat(stressed.at("/lens/stressSet/from").asText()).isEqualTo("2008-09-01");
        assertThat(stressed.at("/lens/stressSet/synthetic").asBoolean()).isTrue();
        assertThat(stressed.at("/lens/label").asText()).isEqualTo("stressed 1-day");
        // every node, and its P&L path, is the stress rows' own
        for (String node : svc.stressed(DAY).book().nodes().keySet()) {
            JsonNode p = ask("/api/v1/risk/var/scope/path?size=1&set=stressed", "NODE:" + node, DAY);
            assertThat(p.get("var").asDouble()).as(node).isCloseTo(calc.measures(sum(STRESS, S, svc.stressed(DAY).book().node(node).books()))
                    .var(), within(0.5));
        }
        // the normal book holds the normal rows only
        assertThat(svc.date(DAY).book().width()).isEqualTo(N);
        assertThat(svc.date(DAY).book().tradeCount()).isEqualTo(svc.stressed(DAY).book().tradeCount());
    }

    @Test
    void tenDaysOnTheStressSetAndNoLimitUseNoBacktest() throws Exception {
        double[] v = sum(STRESS, S, List.of("BOOK-1", "BOOK-2", "BOOK-3"));
        VarCalc calc = svc.calc();
        JsonNode sqrt = ask("/api/v1/risk/var/scope?set=stressed&horizon=10d&method=sqrt10", "BANK", DAY);
        assertThat(sqrt.get("var").asDouble()).isCloseTo(calc.measures(v).var() * VarLens.SQRT10, within(0.5));
        JsonNode sum = ask("/api/v1/risk/var/scope?set=stressed&horizon=10d&method=sum", "BANK", DAY);
        assertThat(sum.get("scenarios").asInt()).isEqualTo(S - 9);
        assertThat(sum.get("var").asDouble()).isCloseTo(calc.measures(VarLens.tenDaySum(v)).var(), within(0.5));
        JsonNode bank = ask("/api/v1/risk/var/scope?set=stressed", "NODE:MGMT~BANK", DAY);
        assertThat(bank.get("limit").isNull()).isFalse();
        assertThat(bank.get("limitComparable").asBoolean()).isFalse();
        assertThat(bank.get("limitUse").isNull()).isTrue();
        assertThat(bank.get("limitNote").asText()).contains("stressed VaR is not compared");
        assertThat(bank.at("/backtest/applicable").asBoolean()).isFalse();
        JsonNode h = ask("/api/v1/risk/var/scope/history?set=stressed", "NODE:MGMT~BANK", DAY);
        assertThat(h.get("rows")).hasSize(S);
        assertThat(h.get("rows").get(0).get("date").asText()).isEqualTo("2008-09-01");
        JsonNode tree = ask("/api/v1/risk/var/tree?set=stressed", "BANK", DAY);
        assertThat(tree.toString()).contains("STRESSED");
    }

    @Test
    void aDateWithoutAStressSetSaysSoAndBadRequestsAreRefusedInWords() throws Exception {
        String none = call("/api/v1/risk/var/scope?set=stressed", "BANK", PLAIN).andExpect(status().isNotFound()).andReturn().getResponse()
                .getContentAsString();
        assertThat(json.readTree(none).get("detail").asText()).contains("no stress scenario set on " + PLAIN);
        assertThat(ask("/api/v1/risk/var/scope", "BANK", PLAIN).get("var").asDouble()).isPositive();          // its normal VaR is there
        String ccy = call("/api/v1/risk/var/scope?set=stressed&ccy=EUR", "BANK", DAY).andExpect(status().isBadRequest()).andReturn().getResponse()
                .getContentAsString();
        assertThat(json.readTree(ccy).get("detail").asText()).contains("answered in USD");
        String bad = call("/api/v1/risk/var/scope?set=crisis", "BANK", DAY).andExpect(status().isBadRequest()).andReturn().getResponse()
                .getContentAsString();
        assertThat(json.readTree(bad).get("detail").asText()).contains("normal or stressed");
    }

    @Test
    void theStressBookHasItsOwnHotFileAndTheOfficialRecordCarriesStressedVar() throws Exception {
        svc.stressed(DAY);
        assertThat(lake.resolve("hot").resolve(DAY + "-" + VarLoader.STRESS_FOLDER)).isDirectory();
        Map<String, Object> out = official.capture(DAY);
        assertThat(out.get("status")).isIn("captured", "restated", "unchanged");
        Map<String, Object> index = svc.official().index(DAY, VarDate.USD).orElseThrow();
        assertThat(index.get("stressSet")).isEqualTo(STRESS);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> nodes = (Map<String, Map<String, Object>>) index.get("nodes");
        VarCalc.Measures m = svc.calc().measures(sum(STRESS, S, List.of("BOOK-1", "BOOK-2", "BOOK-3")));
        assertThat(((Number) nodes.get("MGMT~BANK").get("svar")).doubleValue()).isCloseTo(m.var(), within(1e-6));
        assertThat(((Number) nodes.get("MGMT~BANK").get("ses")).doubleValue()).isCloseTo(m.es(), within(1e-6));
        JsonNode avg = ask("/api/v1/risk/var/scope?set=stressed", "NODE:MGMT~BANK", DAY).get("stressAverage");
        assertThat(avg.get("weeks").asInt()).isEqualTo(12);
        assertThat(avg.get("records").asInt()).isEqualTo(1);
        assertThat(avg.get("average").asDouble()).isCloseTo(m.var(), within(1e-6));
        // a date without a stress set: its record has none, and says why
        official.capture(PLAIN);
        Map<String, Object> plain = svc.official().index(PLAIN, VarDate.USD).orElseThrow();
        assertThat(plain.get("stressSet")).isNull();
        assertThat(String.valueOf(plain.get("stressNote"))).contains("no stress scenario set");
        // compute-first: both sets loaded, every node computed and captured, and not one input table has a file more or less
        svc.date(DAY);
        assertThat(tables(lake)).isEqualTo(inputs);
        try (java.util.stream.Stream<Path> s = Files.list(lake.resolve("risk"))) {
            assertThat(s.map(x -> x.getFileName().toString()).sorted().toList()).containsExactly("pnl-vector", "portfolio", "scenario-set",
                    "var-limit");
        }
    }
}
