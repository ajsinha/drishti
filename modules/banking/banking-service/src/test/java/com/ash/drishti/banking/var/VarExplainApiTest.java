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
 * Why VaR moved through the one Explain (VAR_GUIDE.md section 3.11; {@code GET /api/v1/explain?kind=var}, its Why it moved part) on a lake of two business dates whose differences are known by hand:
 * the window rolls one scenario day, the market scales every trade's P&amp;L by 1.05, T5 matures (no vector on the later date), T6 is new,
 * and BOOK-2 moves from DESK-X to DESK-Y. The states telescope to the change on every node, each step equals its hand-built VaR, the
 * drivers close their steps, the workbook recalculated by LibreOffice equals the engine to the cent.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=var-limit",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.var-limit=risk-lake",
        "drishti.var.hierarchies.MGMT.title=Management", "drishti.var.hierarchies.MGMT.levels=DESK > TT_BOOK",
        "drishti.var.hierarchies.MGMT.level-names=desk > book", "drishti.var.hierarchies.MGMT.ids=leaf",
        "drishti.var.hierarchies.MGMT.limits=var-limit", "drishti.var.hierarchies.LEGAL.title=Legal entity",
        "drishti.var.hierarchies.LEGAL.levels=LEGAL_ENTITY", "drishti.var.hierarchies.LEGAL.ids=leaf",
        "drishti.var.hierarchies.LEGAL.limits=var-limit",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false", "drishti.var.currencies=USD",
        "drishti.var.hot-dates=2", "drishti.var.confidence=0.95",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.security.roles.desk-y.kinds[0]=*", "drishti.security.roles.desk-y.raw=true",
        "drishti.var.access.desk-y[MGMT~DESK-Y]=positions", "drishti.under-construction.file=../../../config/under-construction.yaml",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0", "drishti.engines.mr-cube.enabled=true",
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
        // MGMT is built from the rows' DESK and TT_BOOK: BOOK-2's rows say DESK-X on the earlier date and DESK-Y on the later (the move);
        // each node's limit is a var-limit row
        List<Map<String, Object>> limits = new ArrayList<>();
        for (LocalDate day : List.of(D1, D2)) {
            for (String id : List.of("MGMT~BANK", "MGMT~DESK-X", "MGMT~DESK-Y", "MGMT~BOOK-1", "MGMT~BOOK-2", "MGMT~BOOK-3")) {
                limits.add(limit(day, id));
            }
        }
        VarLakeFixture.table(root, work, "var-limit", "LIMIT_ID", limits, List.of("HIERARCHY", "NODE_ID", "LIMIT_VAR"), null);
    }

    static Map<String, Object> limit(LocalDate day, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("LIMIT_ID", "VL-" + id);
        m.put("HIERARCHY", "MGMT");
        m.put("NODE_ID", id);
        m.put("LIMIT_VAR", day.equals(D1) ? 100_000.0 : 120_000.0);
        m.put("CURRENCY", "USD");
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
        return reqAs("rita", "risk", path, params);
    }

    private MockHttpServletRequestBuilder reqAs(String user, String role, String path, String... params) {
        var q = get(path).header("Authorization", "Bearer " + tokens.mint(user, List.of(role), 300));
        for (int i = 0; i < params.length; i += 2) {
            q = q.param(params[i], params[i + 1]);
        }
        return q;
    }

    private JsonNode ask(String path, ResultMatcher expect, String... params) throws Exception {
        return json.readTree(mvc.perform(req(path, params)).andExpect(expect).andReturn().getResponse().getContentAsString());
    }

    /** The engine's own answer of why it moved, as the one Explain carries it (part why: the drawer's tab). */
    private JsonNode explain(String scope) throws Exception {
        return ask("/api/v1/explain", status().isOk(), "kind", "var", "part", "why", "scope", scope, "from", D1.toString(), "to", D2.toString())
                .at("/why/answer");
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
        JsonNode a = ask("/api/v1/explain", status().isOk(), "kind", "var", "part", "why", "scope", "BANK").at("/why/answer");
        assertThat(a.get("from").asText()).isEqualTo(D1.toString());
        assertThat(a.get("to").asText()).isEqualTo(D2.toString());
        assertThat(a.at("/official/captured").asBoolean()).isFalse();
        assertThat(a.get("order").get(0).asText()).isEqualTo("restatement");
        JsonNode m = ask("/api/v1/explain", status().isOk(), "kind", "var", "part", "why", "view", "matrix", "scope", "BANK").at("/why/answer/matrix");
        assertThat(m.get("rows")).hasSize(3);
        for (JsonNode r : m.get("rows")) {
            double s = 0;
            for (var it = r.get("effects").fields(); it.hasNext(); ) {
                s += it.next().getValue().asDouble();
            }
            assertThat(s).isCloseTo(r.get("change").asDouble(), within(0.005));
        }
        assertThat(ask("/api/v1/explain", status().isBadRequest(), "kind", "var", "scope", "BANK", "from", D2.toString(), "to", D1.toString())
                .get("detail").asText()).contains("before");
        assertThat(ask("/api/v1/explain", status().isBadRequest(), "kind", "var", "scope", "BANK", "attribution", "shapley").get("detail").asText())
                .contains("planned");
        assertThat(ask("/api/v1/explain", status().isBadRequest(), "kind", "var", "scope", "BANK", "from", "yesterday").get("detail").asText())
                .contains("yyyy-MM-dd");
        assertThat(ask("/api/v1/explain", status().isBadRequest(), "kind", "var").get("detail").asText()).contains("scope");
        assertThat(ask("/api/v1/explain", status().isBadRequest(), "kind", "var", "scope", "BANK", "view", "pie").get("detail").asText())
                .contains("waterfall");
        assertThat(ask("/api/v1/explain", status().isBadRequest(), "kind", "var", "scope", "BANK", "measure", "dv01").get("detail").asText())
                .contains("pvar");
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

    // ---- the one Explain: the three VaR panels folded, the same numbers --------------------------------------------------------------

    /** The How calculated box's rows, as drishti_var-explorer.js's renderProv made them from the scope answer (the old panel, in Java). */
    static List<String[]> oldHowCalculated(JsonNode d) {
        JsonNode p = d.get("provenance");
        JsonNode fx = p.path("fx");
        JsonNode in = p.path("intraday");
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Scope", d.get("kind").asText() + " " + d.get("label").asText()});
        rows.add(new String[] {"Reporting currency", (p.hasNonNull("currency") ? p.get("currency").asText() : "USD") + ", chosen in the header"});
        rows.add(new String[] {"Input", p.hasNonNull("input") ? p.get("input").asText() : "P&L vectors (USD)"});
        List<String> sys = new ArrayList<>();
        new java.util.TreeSet<String>(fieldNames(p.path("inputsBySystem"))).forEach(k -> {
            JsonNode x = p.path("inputsBySystem").get(k);
            sys.add(k + ": " + ("pv".equals(x.path("form").asText()) ? "scenario PVs" : "P&L vectors") + " per " + x.path("grain").asText() + " ("
                    + String.format(java.util.Locale.US, "%,d", x.path("rows").asLong()) + ")");
        });
        rows.add(new String[] {"By source system", sys.isEmpty() ? "one form a trade" : String.join("; ", sys)});
        rows.add(new String[] {"Intraday", in.isMissingNode() || in.isNull() ? "none: the morning run" : "(intraday)"});
        rows.add(new String[] {"Scenario FX", fx.hasNonNull("scenarioFx") ? fx.get("scenarioFx").asText() : "none needed"});
        rows.add(new String[] {"Cross rates", "via USD"});
        rows.add(new String[] {"Positions", String.format(java.util.Locale.US, "%,d", p.get("positions").asLong())});
        rows.add(new String[] {"Outside your view", p.get("hidden").asInt() > 0 ? String.format(java.util.Locale.US, "%,d", p.get("hidden").asLong())
                + " trades not included" : "none"});
        rows.add(new String[] {"Scenario set", p.get("scenarioSet").asText()});
        rows.add(new String[] {"Method", p.get("method").asText()});
        rows.add(new String[] {"ES", p.get("es").asText()});
        rows.add(new String[] {"Components", p.get("components").asText()});
        rows.add(new String[] {"Engine", p.get("engine").asText()});
        rows.add(new String[] {"View", p.get("view").asText()});
        rows.add(new String[] {"Members", p.get("membershipHash").asText()});
        return rows;
    }

    static List<String> fieldNames(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }

    static Map<String, String> rows(JsonNode x) {
        Map<String, String> m = new LinkedHashMap<>();
        x.at("/inputs/rows").forEach(r -> m.put(r.get("label").asText(), r.get("value").asText()));
        return m;
    }

    @Test
    void theOneExplainKeepsEveryNumberTheThreeVarPanelsGave() throws Exception {
        for (String scope : List.of("BANK", "NODE:MGMT~DESK-X", "DESK:DESK-Y")) {
            JsonNode head = ask("/api/v1/risk/var/scope", status().isOk(), "scope", scope, "asOf", D2.toString());
            JsonNode x = ask("/api/v1/explain", status().isOk(), "kind", "var", "scope", scope, "date", D2.toString(), "from", D1.toString(),
                    "to", D2.toString());
            // the head: the figure the tile shows, computed (not stored)
            assertThat(x.get("value").asDouble()).as(scope).isEqualTo(head.get("var").asDouble());
            assertThat(x.get("stored").asBoolean()).isFalse();
            assertThat(x.get("asOf").asText()).isEqualTo(D2.toString());
            // How calculated: every row the box showed, the same words and figures
            Map<String, String> got = rows(x);
            for (String[] r : oldHowCalculated(head)) {
                if (!r[1].equals("(intraday)")) {
                    assertThat(got.get(r[0])).as(scope + " " + r[0]).isEqualTo(r[1]);
                }
            }
            assertThat(got).containsKeys("Resolved", "Trades", "Unknown ids", "Warnings", "Rows left out", "Vectors", "Positions by", "Computed");
            assertThat(x.at("/inputs/lineage")).isNotEmpty();
            // the formula: k, the rank and the VaR scenario's P&L with the figure
            String formula = x.at("/formula/lines").toString();
            assertThat(formula).contains("k = " + head.get("k").asInt()).contains(head.get("scenarios").asText() + " scenario P&Ls");
            assertThat(x.at("/formula/rachana").asText()).isEqualTo("var()");
            // Why it moved: the engine's answer, as the tab drew it (the states telescope; the waterfall's ends are the two VaRs)
            JsonNode why = x.get("why");
            assertThat(why.get("available").asBoolean()).isTrue();
            assertThat(why.get("addsUp").asBoolean()).isTrue();
            JsonNode a = why.get("answer");
            assertThat(why.get("change").asDouble()).isEqualTo(a.get("change").asDouble());
            JsonNode wf = null;
            for (JsonNode pnl : why.get("panels")) {
                if (pnl.get("id").asText().equals("waterfall")) {
                    wf = pnl;
                }
            }
            assertThat(wf).as("the waterfall panel").isNotNull();
            JsonNode steps = wf.at("/data/steps");
            assertThat(steps.get(0).get("value").asDouble()).isEqualTo(a.get("varFrom").asDouble());
            assertThat(steps.get(steps.size() - 1).get("value").asDouble()).isEqualTo(a.get("varTo").asDouble());
            assertThat(steps.size()).isEqualTo(a.get("lines").size() + 2);
            for (int i = 0; i < a.get("lines").size(); i++) {
                assertThat(steps.get(i + 1).get("value").asDouble()).isEqualTo(a.get("lines").get(i).get("effect").asDouble());
            }
            // the workbook: the explanation's .xlsx and the playground
            assertThat(x.at("/workbook/links/0/api").asText()).startsWith("/api/v1/risk/var/explain.xlsx?scope=");
            assertThat(x.at("/checks/0/ok").asBoolean()).isTrue();
            assertThat(x.at("/underConstruction/0/id").asText()).isEqualTo("explain-official-check");
        }
        // the ? of each parametric figure: the same server lines it showed
        JsonNode head = ask("/api/v1/risk/var/scope", status().isOk(), "scope", "BANK", "asOf", D2.toString());
        JsonNode pm = head.get("parametric");
        Assumptions.assumeTrue(pm != null && !pm.isNull(), "parametric VaR is off here");
        List<String> describe = new ArrayList<>();
        pm.get("describe").forEach(l -> describe.add(l.asText()));
        for (String measure : List.of("pvar", "pes")) {
            JsonNode x = ask("/api/v1/explain", status().isOk(), "kind", "var", "measure", measure, "scope", "BANK", "date", D2.toString());
            List<String> lines = new ArrayList<>();
            x.at("/formula/lines").forEach(l -> lines.add(l.asText()));
            assertThat(lines).isEqualTo(describe);
            assertThat(x.get("value").asDouble()).isEqualTo(pm.get("pvar".equals(measure) ? "var" : "es").asDouble());
            assertThat(x.at("/why/underConstruction/id").asText()).isEqualTo("explain-why-es-parametric");
        }
        List<String> gap = new ArrayList<>();
        gap.add(describe.get(0).replace("Parametric VaR", "Fat-tail gap"));
        describe.stream().filter(l -> l.startsWith("Fat-tail gap")).forEach(gap::add);
        pm.path("notes").forEach(l -> gap.add(l.asText()));
        JsonNode g = ask("/api/v1/explain", status().isOk(), "kind", "var", "measure", "pgap", "scope", "BANK", "date", D2.toString());
        List<String> lines = new ArrayList<>();
        g.at("/formula/lines").forEach(l -> lines.add(l.asText()));
        assertThat(lines).isEqualTo(gap);
        // ES: its own formula, its figure
        JsonNode es = ask("/api/v1/explain", status().isOk(), "kind", "var", "measure", "es", "scope", "BANK", "date", D2.toString());
        assertThat(es.get("value").asDouble()).isEqualTo(head.get("es").asDouble());
        assertThat(es.at("/formula/lines/0").asText()).startsWith("ES = −mean(the worst " + head.get("esCount").asInt());
    }

    @Test
    void thePreviewIsLightAndTheKindsAreListed() throws Exception {
        JsonNode pre = ask("/api/v1/explain", status().isOk(), "kind", "var", "part", "preview", "scope", "BANK", "date", D2.toString());
        assertThat(pre.get("preview").asText()).contains("worst of " + N);
        assertThat(pre.has("why")).isFalse();
        assertThat(pre.has("inputs")).isFalse();
        assertThat(pre.has("formula")).isFalse();
        JsonNode inputs = ask("/api/v1/explain", status().isOk(), "kind", "var", "part", "inputs", "scope", "BANK", "date", D2.toString());
        assertThat(inputs.has("why")).isFalse();
        assertThat(inputs.at("/inputs/rows")).isNotEmpty();
        JsonNode kinds = ask("/api/v1/explain/kinds", status().isOk());
        List<String> ks = new ArrayList<>();
        kinds.get("kinds").forEach(k -> ks.add(k.get("kind").asText()));
        assertThat(ks).contains("var", "pivot");
        List<String> uc = new ArrayList<>();
        kinds.get("underConstruction").forEach(k -> uc.add(k.get("id").asText()));
        assertThat(uc).contains("explain-more-kinds").doesNotContain("explain-pivot-screen");   // the Pivot's card is on the one Explain (2.9.11.e)
        assertThat(ask("/api/v1/explain", status().isNotFound(), "kind", "sutra", "scope", "BANK").get("detail").asText()).contains("no explainer");
        assertThat(ask("/api/v1/explain", status().isBadRequest(), "scope", "BANK").get("detail").asText()).contains("kind=");
        assertThat(ask("/api/v1/explain", status().isBadRequest(), "kind", "var", "part", "everything", "scope", "BANK").get("detail").asText())
                .contains("preview");
    }

    /** Two people, one number: the one who sees DESK-Y only is never told a DESK-X trade, in any part; only how many are outside the view. */
    @Test
    void twoPeopleSeeOnlyWhatTheyMay() throws Exception {
        JsonNode rita = json.readTree(mvc.perform(req("/api/v1/explain", "kind", "var", "scope", "BANK", "from", D1.toString(), "to", D2.toString(),
                "date", D2.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode yan = json.readTree(mvc.perform(reqAs("yan", "desk-y", "/api/v1/explain", "kind", "var", "scope", "BANK", "from", D1.toString(),
                "to", D2.toString(), "date", D2.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String all = rita.toString();
        String mine = yan.toString();
        assertThat(all).contains("\"T6\"");                                // the full view names the new trade among the drivers
        for (String hidden : List.of("T1", "T2", "T6")) {                      // DESK-X's trades on both dates (BOOK-1)
            assertThat(mine).as("yan is never told " + hidden).doesNotContain("\"" + hidden + "\"");
        }
        assertThat(mine).doesNotContain("BOOK-1");
        assertThat(rows(yan).get("Outside your view")).endsWith("trades not included");
        assertThat(rows(rita).get("Outside your view")).isEqualTo("none");
        // and yan's figure is yan's own VaR of the scope, as yan's screen shows it
        JsonNode head = json.readTree(mvc.perform(reqAs("yan", "desk-y", "/api/v1/risk/var/scope", "scope", "BANK", "asOf", D2.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(yan.get("value").asDouble()).isEqualTo(head.get("var").asDouble());
        assertThat(yan.get("value").asDouble()).isNotEqualTo(rita.get("value").asDouble());
    }

    /** An answer without its timing (the one field two asks never share). */
    private static JsonNode timeless(JsonNode a) {
        com.fasterxml.jackson.databind.node.ObjectNode o = a.deepCopy();
        o.remove("computeMs");
        return o;
    }

    /** The answer has exactly the recorded 2.9.11.d fields, each of its recorded JSON type (or null). */
    private static void shaped(JsonNode a, JsonNode shape, String what) {
        List<String> fields = new ArrayList<>();
        a.fieldNames().forEachRemaining(fields::add);
        List<String> recorded = new ArrayList<>();
        shape.fieldNames().forEachRemaining(recorded::add);
        assertThat(fields).as(what).containsExactlyInAnyOrderElementsOf(recorded);
        shape.fields().forEachRemaining(e -> {
            JsonNode v = a.get(e.getKey());
            String type = v.isObject() ? "object" : v.isArray() ? "array" : v.isNumber() ? "number" : v.isTextual() ? "string"
                    : v.isBoolean() ? "boolean" : "null";
            assertThat(type.equals(e.getValue().asText()) || v.isNull()).as(what + " " + e.getKey() + ": " + type).isTrue();
        });
    }

    @Test
    void theOldExplainPathsAnswerForOneReleaseWithTheirOldBody() throws Exception {
        JsonNode shape = json.readTree(VarExplainApiTest.class.getResourceAsStream("/var-explain-d-shape.json"));
        JsonNode why = ask("/api/v1/explain", status().isOk(), "kind", "var", "part", "why", "scope", "BANK").at("/why/answer");
        JsonNode matrix = ask("/api/v1/explain", status().isOk(), "kind", "var", "part", "why", "view", "matrix", "scope", "BANK")
                .at("/why/answer/matrix");
        String link = "</api/v1/explain?kind=var&part=why>; rel=\"successor-version\"";
        String linkMatrix = "</api/v1/explain?kind=var&part=why&view=matrix>; rel=\"successor-version\"";
        for (String path : List.of("/api/v1/var/explain", "/api/v1/risk/var/explain", "/api/v1/var/explain/matrix",
                "/api/v1/risk/var/explain/matrix", "/api/v1/pivot/mr/explain", "/api/v1/risk/mr/explain")) {
            MvcResult r = mvc.perform(req(path, "scope", "BANK")).andExpect(status().isOk()).andReturn();
            assertThat(r.getResponse().getHeader("Deprecation")).as(path).isEqualTo("true");
            assertThat(r.getResponse().getHeaders("Link")).as(path).containsExactly(path.endsWith("matrix") ? linkMatrix : link);
            JsonNode a = json.readTree(r.getResponse().getContentAsString());
            if (path.endsWith("matrix")) {
                shaped(a, shape.get("matrix"), path);
                for (JsonNode row : a.get("rows")) {
                    shaped(row, shape.get("matrixRow"), path + " row");
                }
                assertThat(a).as(path).isEqualTo(matrix);
            } else if (path.contains("/mr/")) {
                shaped(a, shape.get("mr"), path);
                assertThat(a.at("/factorOverlay/available").asBoolean(true)).isFalse();
                com.fasterxml.jackson.databind.node.ObjectNode rest = (com.fasterxml.jackson.databind.node.ObjectNode) timeless(a);
                rest.remove("factorOverlay");
                assertThat((JsonNode) rest).as(path).isEqualTo(timeless(why));
            } else {
                shaped(a, shape.get("var"), path);
                assertThat(timeless(a)).as(path).isEqualTo(timeless(why));
            }
        }
        JsonNode d1 = ask("/api/v1/var/explain", status().isOk(), "scope", "NODE:MGMT~DESK-X", "from", D1.toString(), "to", D2.toString());
        assertThat(timeless(d1)).isEqualTo(timeless(explain("NODE:MGMT~DESK-X")));
        assertThat(ask("/api/v1/var/explain", status().isBadRequest(), "scope", "BANK", "method", "shapley").get("detail").asText())
                .contains("planned");
        assertThat(ask("/api/v1/var/explain", status().isBadRequest()).get("detail").asText()).contains("give a scope");
    }
}
