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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.banking.compute.var.VarCalc;
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
import org.junit.jupiter.api.io.TempDir;
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
 * Why VaR moved through the server (VAR_GUIDE.md section 3.11) on a lake of two business dates whose differences are known by hand:
 * the window rolls one scenario day, the market scales every trade's P&amp;L by 1.05, T5 matures (no vector on the later date), T6 is new,
 * and BOOK-2 moves from DESK-X to DESK-Y. The states telescope to the change on every node, each step equals its hand-built VaR, the
 * drivers close their steps, the workbook recalculated by LibreOffice equals the engine to the cent.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false", "drishti.var.currencies=USD",
        "drishti.var.hot-dates=2", "drishti.var.confidence=0.95",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarExplainApiTest {

    static final LocalDate D1 = LocalDate.of(2026, 9, 29);
    static final LocalDate D2 = LocalDate.of(2026, 9, 30);
    static final int N = 60;
    static final double MOVE = 1.05;
    /** trade id, book, desk, on D1, on D2, beta */
    record T(String id, String book, String desk1, String desk2, boolean d1, boolean d2, double beta) {}
    static final List<T> TRADES = List.of(
            new T("T1", "BOOK-1", "DESK-X", "DESK-X", true, true, 3.0),
            new T("T2", "BOOK-1", "DESK-X", "DESK-X", true, true, -1.2),
            new T("T3", "BOOK-2", "DESK-X", "DESK-Y", true, true, 2.0),
            new T("T4", "BOOK-3", "DESK-Y", "DESK-Y", true, true, 1.5),
            new T("T5", "BOOK-2", "DESK-X", "DESK-Y", true, false, 2.5),
            new T("T6", "BOOK-1", "DESK-X", "DESK-X", false, true, 1.8));
    static double[] factor;
    static Map<String, double[]> noise = new LinkedHashMap<>();
    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-explain-lake-" + System.nanoTime())).toAbsolutePath();
        write(lake);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
    }

    /** N + 1 weekdays: D1's set is the first N, D2's the last N (the window rolled one day). */
    static List<String> allDays() {
        List<String> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2026, 6, 1);
        while (out.size() < N + 1) {
            if (d.getDayOfWeek().getValue() < 6) {
                out.add(d.toString());
            }
            d = d.plusDays(1);
        }
        return out;
    }

    /** A trade's P&amp;L on a date: beta × the factor plus its own noise, on that date's days; the later date's × MOVE. */
    static double[] vector(T t, boolean later) {
        double[] v = new double[N];
        double[] e = noise.get(t.id());
        for (int j = 0; j < N; j++) {
            int day = later ? j + 1 : j;
            double x = t.beta() * factor[day] * 1000 + e[day] * 100;
            v[j] = Math.round((later ? x * MOVE : x) * 100) / 100.0;
        }
        return v;
    }

    static void write(Path root) throws Exception {
        Path work = Files.createDirectories(root.resolve("_work"));
        Random r = new Random(19);
        factor = new double[N + 1];
        for (int j = 0; j <= N; j++) {
            factor[j] = r.nextGaussian() * (j % 17 == 5 ? 4 : 1);
        }
        for (T t : TRADES) {
            double[] e = new double[N + 1];
            for (int j = 0; j <= N; j++) {
                e[j] = r.nextGaussian();
            }
            noise.put(t.id(), e);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LocalDate day : List.of(D1, D2)) {
            boolean later = day.equals(D2);
            for (T t : TRADES) {
                if (later ? !t.d2() : !t.d1()) {
                    continue;
                }
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("VECTOR_ID", "PV-" + t.id() + "-" + day);
                d.put("TRADE_ID", t.id());
                d.put("BUSINESS_DATE", day.toString());
                d.put("SCENARIO_SET_ID", "HS-" + N);
                d.put("BATCH_ID", "B-" + day + "-01");
                d.put("LEGAL_ENTITY", "LE-A");
                d.put("DIVISION", "DIV-M");
                d.put("DESK", later ? t.desk2() : t.desk1());
                d.put("TT_BOOK", t.book());
                d.put("PORTFOLIO", t.book());
                d.put("PRODUCT_GROUP", "Rates");
                d.put("PRODUCT_TYPE", "IRS");
                d.put("CURRENCY", "USD");
                d.put("COUNTERPARTY", "CP-1");
                d.put("DIRECTION", "LONG");
                d.put("QUANTITY", 1.0);
                d.put("PV", 1000.0);
                d.put("TRADE_STATUS", "VALIDATED");
                d.put("MATURITY_DATE", "2030-01-15");
                d.put("STATUS", "ok");
                d.put("REPORTING_CCY", "USD");
                List<Double> v = new ArrayList<>();
                for (double x : vector(t, later)) {
                    v.add(x);
                }
                d.put("PNL", v);
                rows.add(d);
            }
        }
        VarLakeFixture.table(root, work, "pnl-vector", "VECTOR_ID", rows, List.of("TRADE_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK",
                "PORTFOLIO", "PRODUCT_GROUP", "PRODUCT_TYPE", "CURRENCY", "COUNTERPARTY", "DIRECTION", "QUANTITY", "PV", "TRADE_STATUS",
                "MATURITY_DATE", "STATUS", "REPORTING_CCY", "SCENARIO_SET_ID", "BATCH_ID"), "PNL");
        List<String> all = allDays();
        VarLakeFixture.table(root, work, "scenario-set", "SCENARIO_SET_ID", List.of(
                Map.of("SCENARIO_SET_ID", "HS-" + N, "BUSINESS_DATE", D1.toString(), "SCENARIO_DATES", all.subList(0, N)),
                Map.of("SCENARIO_SET_ID", "HS-" + N, "BUSINESS_DATE", D2.toString(), "SCENARIO_DATES", all.subList(1, N + 1))), List.of(), null);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (LocalDate day : List.of(D1, D2)) {
            boolean later = day.equals(D2);
            nodes.add(node(day, "MGMT~BANK", "bank", null, List.of("BOOK-1", "BOOK-2", "BOOK-3")));
            nodes.add(node(day, "MGMT~DESK-X", "desk", "MGMT~BANK", later ? List.of("BOOK-1") : List.of("BOOK-1", "BOOK-2")));
            nodes.add(node(day, "MGMT~DESK-Y", "desk", "MGMT~BANK", later ? List.of("BOOK-2", "BOOK-3") : List.of("BOOK-3")));
            for (String b : List.of("BOOK-1", "BOOK-2", "BOOK-3")) {
                String parent = b.equals("BOOK-1") || b.equals("BOOK-2") && !later ? "MGMT~DESK-X" : "MGMT~DESK-Y";
                nodes.add(node(day, "MGMT~" + b, "book", parent, List.of(b)));
            }
        }
        VarLakeFixture.table(root, work, "risk-hierarchy", "NODE_ID", nodes, List.of("NODE_ID", "LIMIT_VAR"), null);
    }

    static Map<String, Object> node(LocalDate day, String id, String level, String parent, List<String> books) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("NODE_ID", id);
        m.put("HIERARCHY", "MGMT");
        m.put("LEVEL", level);
        m.put("PARENT_ID", parent);
        m.put("NAME", id.substring(5));
        m.put("BOOKS", books);
        m.put("LIMIT_VAR", day.equals(D1) ? 100_000.0 : 120_000.0);
        m.put("BUSINESS_DATE", day.toString());
        return m;
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path work;

    private MockHttpServletRequestBuilder req(String path, String... params) {
        var q = get(path).header("Authorization", "Bearer " + tokens.mint("rita", List.of("risk"), 300));
        for (int i = 0; i < params.length; i += 2) {
            q = q.param(params[i], params[i + 1]);
        }
        return q;
    }

    private JsonNode ask(String path, ResultMatcher expect, String... params) throws Exception {
        return json.readTree(mvc.perform(req(path, params)).andExpect(expect).andReturn().getResponse().getContentAsString());
    }

    private JsonNode explain(String scope) throws Exception {
        return ask("/api/v1/risk/var/explain", status().isOk(), "scope", scope, "from", D1.toString(), "to", D2.toString());
    }

    static double[] sum(boolean later, java.util.function.Predicate<T> which) {
        double[] acc = new double[N];
        for (T t : TRADES) {
            if (which.test(t)) {
                double[] v = vector(t, later);
                for (int j = 0; j < N; j++) {
                    acc[j] += v[j];
                }
            }
        }
        return acc;
    }

    static double effect(JsonNode a, String key) {
        double s = 0;
        for (JsonNode st : a.get("steps")) {
            if (st.get("key").asText().equals(key)) {
                s += st.get("effect").asDouble();
            }
        }
        return s;
    }

    @Test
    void theStatesTelescopeOnEveryNodeAndEndAtEachDatesVar() throws Exception {
        for (String scope : List.of("BANK", "NODE:MGMT~DESK-X", "NODE:MGMT~DESK-Y", "NODE:MGMT~BOOK-2", "DESK:DESK-X")) {
            JsonNode a = explain(scope);
            JsonNode h1 = ask("/api/v1/risk/var/scope", status().isOk(), "scope", scope, "asOf", D1.toString());
            JsonNode h2 = ask("/api/v1/risk/var/scope", status().isOk(), "scope", scope, "asOf", D2.toString());
            assertThat(a.get("from").asText()).isEqualTo(D1.toString());
            assertThat(a.get("to").asText()).isEqualTo(D2.toString());
            assertThat(a.get("addsUp").asBoolean()).as(scope).isTrue();
            assertThat(a.get("sumOfSteps").asDouble()).as(scope).isCloseTo(a.get("change").asDouble(), within(0.005));
            assertThat(a.get("varFrom").asDouble()).as(scope).isCloseTo(h1.get("var").asDouble(), within(0.5));   // the headline is rounded
            assertThat(a.get("varTo").asDouble()).as(scope).isCloseTo(h2.get("var").asDouble(), within(0.5));   // the headline is rounded
            assertThat(a.get("states")).hasSize(a.get("steps").size() + 1);
            assertThat(a.at("/orderFree/addsUp").asBoolean()).as(scope).isTrue();
            for (JsonNode st : a.get("steps")) {
                assertThat(st.get("varScenarioBefore").isTextual()).isTrue();
                JsonNode d = st.get("drivers");
                if (d.get("kind").asText().equals("children")) {
                    assertThat(d.get("addsUp").asBoolean()).as(scope + " " + st.get("key")).isTrue();
                }
                if (d.has("rest") && d.get("field") != null) {
                    double listed = 0;
                    for (JsonNode r : d.get("rows")) {
                        listed += r.get(d.get("field").asText()).asDouble();
                    }
                    assertThat(listed + d.get("rest").asDouble()).as(scope + " " + st.get("key")).isCloseTo(st.get("effect").asDouble(), within(1e-6));
                }
            }
            assertThat(a.get("sentences").get(0).asText()).contains("VaR of").contains(D1.toString()).contains(D2.toString());
        }
    }

    @Test
    void eachStepEqualsItsHandBuiltVar() throws Exception {
        VarCalc calc = svc.calc();
        // DESK-X: T1, T2, T3, T5 on D1; BOOK-2 (T3) moves to DESK-Y; T5 matures; T6 is new
        JsonNode a = explain("NODE:MGMT~DESK-X");
        assertThat(a.at("/counts/out").asInt()).isEqualTo(1);
        assertThat(a.at("/counts/in").asInt()).isEqualTo(1);
        assertThat(a.at("/counts/movedOut").asInt()).isEqualTo(1);
        assertThat(a.at("/counts/movedIn").asInt()).isZero();
        double[] s1 = sum(false, t -> t.d1() && t.desk1().equals("DESK-X"));
        double[] s2 = sum(false, t -> t.d1() && t.desk1().equals("DESK-X") && !t.id().equals("T3"));
        double[] s3 = sum(false, t -> t.id().equals("T1") || t.id().equals("T2"));
        assertThat(effect(a, "restatement")).isCloseTo(0.0, within(1e-9));
        assertThat(effect(a, "hierarchy")).isCloseTo(calc.var(s2) - calc.var(s1), within(0.005));
        assertThat(effect(a, "trades-out")).isCloseTo(calc.var(s3) - calc.var(s2), within(0.005));
        double[] common3 = java.util.Arrays.copyOfRange(s3, 1, N);
        assertThat(effect(a, "window-drop")).isCloseTo(calc.var(common3) - calc.var(s3), within(0.005));
        double[] s5full = sum(true, t -> t.id().equals("T1") || t.id().equals("T2"));
        double[] s5 = java.util.Arrays.copyOf(s5full, N - 1);
        assertThat(effect(a, "market")).isCloseTo(calc.var(s5) - calc.var(common3), within(0.005));
        assertThat(effect(a, "window-add")).isCloseTo(calc.var(s5full) - calc.var(s5), within(0.005));
        double[] s7 = sum(true, t -> t.d2() && t.desk2().equals("DESK-X"));
        assertThat(effect(a, "trades-in")).isCloseTo(calc.var(s7) - calc.var(s5full), within(0.005));
        JsonNode outRows = a.get("steps").get(2).at("/drivers/rows");
        assertThat(outRows.get(0).get("id").asText()).isEqualTo("T5");
        assertThat(outRows.get(0).get("reason").asText()).startsWith("no vector on");
        JsonNode hier = a.get("steps").get(1).get("drivers");
        assertThat(hier.at("/rows/0/id").asText()).isEqualTo("BOOK-2");
        assertThat(hier.at("/rows/0/how").asText()).isEqualTo("moved out");
        // the limit line: the use's change split exactly into the VaR's part and the limit's part
        JsonNode lim = a.get("limit");
        assertThat(lim.get("fromVar").asDouble() + lim.get("fromLimit").asDouble())
                .isCloseTo(lim.get("useTo").asDouble() - lim.get("useFrom").asDouble(), within(1e-12));
    }

    @Test
    void theDefaultDatesTheMatrixAndTheRefusals() throws Exception {
        JsonNode a = ask("/api/v1/risk/var/explain", status().isOk(), "scope", "BANK");
        assertThat(a.get("from").asText()).isEqualTo(D1.toString());
        assertThat(a.get("to").asText()).isEqualTo(D2.toString());
        assertThat(a.at("/official/captured").asBoolean()).isFalse();
        assertThat(a.get("order").get(0).asText()).isEqualTo("restatement");
        JsonNode m = ask("/api/v1/risk/var/explain/matrix", status().isOk(), "scope", "BANK");
        assertThat(m.get("rows")).hasSize(3);
        for (JsonNode r : m.get("rows")) {
            double s = 0;
            for (var it = r.get("effects").fields(); it.hasNext(); ) {
                s += it.next().getValue().asDouble();
            }
            assertThat(s).isCloseTo(r.get("change").asDouble(), within(0.005));
        }
        assertThat(ask("/api/v1/risk/var/explain", status().isBadRequest(), "scope", "BANK", "from", D2.toString(), "to", D1.toString())
                .get("detail").asText()).contains("before");
        assertThat(ask("/api/v1/risk/var/explain", status().isBadRequest(), "scope", "BANK", "method", "shapley").get("detail").asText())
                .contains("planned");
        assertThat(ask("/api/v1/risk/var/explain", status().isBadRequest(), "scope", "BANK", "from", "yesterday").get("detail").asText())
                .contains("yyyy-MM-dd");
        assertThat(ask("/api/v1/risk/var/explain", status().isBadRequest()).get("detail").asText()).contains("scope");
    }

    @Test
    void theWorkbookEqualsTheEngineToTheCent() throws Exception {
        MvcResult started = mvc.perform(req("/api/v1/risk/var/explain.xlsx", "scope", "NODE:MGMT~DESK-X", "from", D1.toString(), "to", D2.toString()))
                .andExpect(request().asyncStarted()).andReturn();
        byte[] xlsx = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(XlsxFormulas.scan(xlsx)).isEmpty();
        Assumptions.assumeTrue(LibreOfficeRecalc.available(), "LibreOffice is not installed here");
        Path saved = LibreOfficeRecalc.recalc(LibreOfficeRecalc.write(work, "explain.xlsx", xlsx), work);
        Map<String, Object> main = LibreOfficeRecalc.cells(saved, "Main");
        assertThat(main.get("B3")).isEqualTo(VarExplainBook.ALL_PASS);
        JsonNode a = explain("NODE:MGMT~DESK-X");
        for (int i = 0; i < a.get("states").size(); i++) {
            double sheet = ((Number) main.get("H" + (12 + i))).doubleValue();
            assertThat(sheet).isCloseTo(a.get("states").get(i).get("var").asDouble(), within(0.005));
            assertThat(main.get("I" + (12 + i))).isEqualTo(a.get("states").get(i).get("varScenario").asText());
        }
    }
}
