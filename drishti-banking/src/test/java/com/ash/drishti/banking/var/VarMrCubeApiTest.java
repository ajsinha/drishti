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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.banking.compute.var.VarLens;
import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The MR cube on the pivot engine, on the tiny lake of {@link VarLakeFixture} (PIVOT_GUIDE.md#29-the-mr-cube-preset): {@code /api/v1/pivot/query} on source
 * {@code var}, where every cell's and total's VaR equals the VaR engine's own answer for the cell's scope to the cent
 * ({@code /api/v1/risk/var/scope} and the engine's unrounded figure), the totals are the union's VaR and never a sum (the desks' VaRs sum to more
 * than the bank's; the diversification is the difference), the components add up to the total, the limit of a whole desk, hierarchy levels
 * and the risk class as dimensions, a node opened by the next level, a cell's trades and its tail, the lens as the view's options, the
 * second ask from the pivot engine's cache, the budget refusing a view too large with its count, and a role that sees a desk only in
 * aggregate pivoting by hierarchy levels only.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=var-limit", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.refresh-seconds=1",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
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
        "drishti.sources.routes.portfolio=risk-lake",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.official.on-start=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.security.roles.division-head.kinds[0]=*", "drishti.security.roles.division-head.raw=true",
        "drishti.var.access.division-head[MGMT~DESK-X]=aggregate", "drishti.var.access.division-head[MGMT~DESK-Y]=positions",
        "drishti.engines.mr-cube.enabled=true", "drishti.pivot.max-holistic-cells=20",
        "drishti.cube.mr.risk-classes.IR[0]=Rates", "drishti.cube.mr.risk-classes.CR[0]=Fixed income",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarMrCubeApiTest {

    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-mr-cube-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    final ObjectMapper json = new ObjectMapper();
    static final Offset<Double> CENT = Offset.offset(0.005);

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode call(String auth, MockHttpServletRequestBuilder r, Object body, int expect) throws Exception {
        if (body != null) {
            r = r.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult res = mvc.perform(r.header("Authorization", auth)).andReturn();
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(expect);
        String t = res.getResponse().getContentAsString();
        return t.isEmpty() ? json.createObjectNode() : json.readTree(t);
    }

    private JsonNode query(String auth, Map<String, Object> view, int expect) throws Exception {
        return call(auth, post("/api/v1/pivot/query"), Map.of("view", view), expect);
    }

    private static Map<String, Object> view(List<String> rows, List<String> cols, Map<String, String> values, Map<String, String> options) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("source", "var");
        v.put("rows", rows);
        v.put("columns", cols);
        List<Map<String, String>> vs = new ArrayList<>();
        values.forEach((m, as) -> vs.add(Map.of("measure", m, "as", as)));
        v.put("values", vs);
        v.put("options", options);
        return v;
    }

    /** The engine's own unrounded VaR of a scope, as the person, through a lens (null: the default). */
    private double engineVar(String user, String role, String scope, VarLens lens) {
        return svc.scoped(new Principal(user, List.of(role)), scope, null, null, lens).measures().var();
    }

    private double scopeVar(String auth, String scope) throws Exception {
        return call(auth, get("/api/v1/risk/var/scope").param("scope", scope), null, 200).get("var").asDouble();
    }

    /** Every cell of an answer whose scope the grammar names: its VaR is the engine's for that scope, to the cent. */
    private int everyCellIsTheEngines(String auth, JsonNode a, String label, VarLens lens) throws Exception {
        int checked = 0;
        for (JsonNode c : a.get("cells")) {
            if (c.path("scope").isNull() || c.path("scope").isMissingNode() || c.at("/values/" + label).isNull()) {
                continue;
            }
            String scope = c.get("scope").asText();
            double v = c.at("/values/" + label).asDouble();
            assertThat(v).as(scope).isCloseTo(engineVar("rita", "risk", scope, lens), CENT);
            if (lens == null) {
                assertThat(Math.round(v)).as(scope + " against /var/scope").isEqualTo(Math.round(scopeVar(auth, scope)));
            }
            checked++;
        }
        return checked;
    }

    private static String key(JsonNode list, String kind) {
        for (JsonNode e : list) {
            if (kind.equals(e.get("kind").asText())) {
                return e.get("key").asText();
            }
        }
        throw new AssertionError("no " + kind + " entry in " + list);
    }

    @Test
    void everyCellAndTotalIsTheEnginesVarOfItsScopeToTheCentAndTotalsAreNeverSums() throws Exception {
        String rita = as("rita", "risk");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("var()", "VaR");
        values.put("es()", "ES");
        values.put("comp_var()", "Comp VaR");
        values.put("pv()", "PV");
        values.put("trades()", "Trades");
        JsonNode a = query(rita, view(List.of("DESK"), List.of("CURRENCY"), values, Map.of("scope", "BANK")), 200);
        assertThat(everyCellIsTheEngines(rita, a, "VaR", null)).isGreaterThanOrEqualTo(6);
        String grandRow = key(a.get("rows"), "grand");
        String grandCol = key(a.get("columns"), "grand");
        JsonNode total = a.get("cells").get(grandRow + "|" + grandCol);
        assertThat(total.get("scope").asText()).isEqualTo("BANK");
        assertThat(total.at("/values/VaR").asDouble()).isEqualTo(540.0);             // VarLakeFixture: the bank's VaR is 540 by hand
        double desks = 0;
        double comp = 0;
        double pv = 0;
        long trades = 0;
        for (JsonNode r : a.get("rows")) {
            if (!"node".equals(r.get("kind").asText())) {
                continue;
            }
            JsonNode c = a.get("cells").get(r.get("key").asText() + "|" + grandCol);
            desks += c.at("/values/VaR").asDouble();
            comp += c.at("/values/Comp VaR").asDouble();
            pv += c.at("/values/PV").asDouble();
            trades += c.at("/values/Trades").asLong();
        }
        assertThat(desks).as("the desks' VaRs sum to more than the bank's: the total is the union's VaR, not a sum").isGreaterThan(540.0);
        JsonNode grand = null;
        for (JsonNode r : a.get("rows")) {
            if ("grand".equals(r.get("kind").asText())) {
                grand = r;
            }
        }
        assertThat(grand.at("/diversification/VaR").asDouble()).isCloseTo(desks - 540.0, Offset.offset(0.01));
        assertThat(comp).as("component VaR adds up to the total").isCloseTo(540.0, Offset.offset(0.02));
        assertThat(pv).isCloseTo(total.at("/values/PV").asDouble(), Offset.offset(1e-6));
        assertThat(trades).isEqualTo(total.at("/values/Trades").asLong());
        // asked again: every set from the pivot engine's partial-state cache
        JsonNode again = query(rita, view(List.of("DESK"), List.of("CURRENCY"), values, Map.of("scope", "BANK")), 200);
        assertThat(again.at("/computed/fromCache").asInt()).isEqualTo(again.at("/computed/sets").asInt());
        // a cell's provenance carries its scope, to be asked of the engine itself
        String someCell = again.get("cells").fieldNames().next();
        JsonNode prov = call(rita, get("/api/v1/pivot/provenance/" + again.get("provenance").asText()).param("cell", someCell), null, 200);
        assertThat(prov.at("/cell/scope").asText()).isNotBlank();
    }

    @Test
    void aWholeDeskCarriesItsLimitAndItsUseOnTheLimitsBasis() throws Exception {
        String rita = as("rita", "risk");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("var()", "VaR");
        values.put("limit()", "Limit");
        values.put("limit_use()", "Use");
        JsonNode a = query(rita, view(List.of("MGMT.DESK"), List.of(), values, Map.of()), 200);
        String grandCol = key(a.get("columns"), "grand");
        JsonNode x = a.get("cells").get(rowKey(a, "DESK-X") + "|" + grandCol);
        assertThat(x.at("/values/Limit").asDouble()).isEqualTo(1000.0);
        assertThat(x.at("/values/Use").asDouble()).isCloseTo(x.at("/values/VaR").asDouble() / 1000.0, Offset.offset(1e-9));
        JsonNode bank = a.get("cells").get(key(a.get("rows"), "grand") + "|" + grandCol);
        assertThat(bank.at("/values/Limit").asDouble()).isEqualTo(5000.0);
        // a desk cut by a filter is not the desk: no limit, and the cell says why
        Map<String, Object> filtered = view(List.of("MGMT.DESK"), List.of(), values, Map.of());
        filtered.put("filters", Map.of("CURRENCY", Map.of("in", List.of("USD"))));
        JsonNode f = query(rita, filtered, 200);
        JsonNode fx = f.get("cells").get(rowKey(f, "DESK-X") + "|" + key(f.get("columns"), "grand"));
        assertThat(fx.at("/values/Limit").isNull()).isTrue();
        assertThat(fx.at("/notes/Limit").asText()).contains("no limit");
        assertThat(fx.get("scope").asText()).contains("WHERE:CURRENCY in ('USD')").contains("NODE:MGMT~DESK-X");
        everyCellIsTheEngines(rita, f, "VaR", null);
    }

    private static String rowKey(JsonNode a, String value) {
        for (JsonNode r : a.get("rows")) {
            if (value.equals(r.path("value").asText())) {
                return r.get("key").asText();
            }
        }
        throw new AssertionError("no row " + value);
    }

    @Test
    void hierarchyLevelsAndTheRiskClassArePivotsWhoseCellsAreScopesOfTheEngine() throws Exception {
        String rita = as("rita", "risk");
        JsonNode cat = call(rita, get("/api/v1/pivot/catalogue").param("source", "var"), null, 200);
        assertThat(cat.path("fields").findValuesAsText("name")).contains("MGMT.DESK", "MGMT.BOOK", "RISK_CLASS", "POSITION", "TRADE", "CURRENCY");
        assertThat(cat.path("measures").findValuesAsText("name")).contains("var", "pvar", "comp_var", "incr_var", "limit_use", "svar");
        assertThat(cat.at("/defaultView/rows/0").asText()).isEqualTo("MGMT.DESK");
        assertThat(cat.at("/defaultView/columns/0").asText()).isEqualTo("RISK_CLASS");
        JsonNode a = query(rita, view(List.of("MGMT.BOOK"), List.of("RISK_CLASS"), Map.of("var()", "VaR"), Map.of()), 200);
        int named = 0;
        for (JsonNode c : a.get("cells")) {
            if (c.get("coords").has("MGMT.BOOK") && c.get("coords").has("RISK_CLASS") && !"OTHER".equals(c.at("/coords/RISK_CLASS").asText())) {
                assertThat(c.get("scope").asText()).contains("NODE:MGMT~BOOK").contains("WHERE:PRODUCT_GROUP in (");
                named++;
            }
        }
        assertThat(named).isPositive();
        assertThat(everyCellIsTheEngines(rita, a, "VaR", null)).isGreaterThanOrEqualTo(4);
        // a desk opened by the next level: only its books are computed, each the engine's VaR of its scope
        JsonNode open = call(rita, post("/api/v1/pivot/expand"), Map.of("view", view(List.of("MGMT.DESK"), List.of(), Map.of("var()", "VaR"),
                Map.of()), "axis", "rows", "path", List.of(List.of("MGMT.DESK", "DESK-X")), "by", "MGMT.BOOK"), 200);
        assertThat(open.get("rows").findValuesAsText("value")).contains("BOOK-1", "BOOK-2");
        everyCellIsTheEngines(rita, open, "VaR", null);
    }

    @Test
    void theCatalogueSaysEachFieldsGroupFolderHiddenAndFunctionsAndEachHierarchyOnce() throws Exception {
        JsonNode cat = call(as("rita", "risk"), get("/api/v1/pivot/catalogue").param("source", "var"), null, 200);
        JsonNode mgmt = null;
        for (JsonNode h : cat.path("hierarchies")) {
            if ("MGMT".equals(h.path("id").asText())) {
                mgmt = h;
            }
        }
        assertThat(mgmt).as("MGMT is one item of the catalogue's hierarchies: " + cat.path("hierarchies")).isNotNull();
        assertThat(mgmt.path("levels").findValuesAsText("field")).contains("MGMT.DESK", "MGMT.BOOK");
        assertThat(mgmt.path("levels").findValuesAsText("label")).contains("Desk", "Book");
        assertThat(cat.path("hierarchies").findValuesAsText("id")).contains("POSITIONS");
        for (JsonNode f : cat.path("fields")) {
            String name = f.path("name").asText();
            if (name.endsWith(".BANK")) {
                assertThat(f.path("hidden").asBoolean()).as(name + ": the root, every view's total, under More fields").isTrue();
            } else if (name.startsWith("MGMT.")) {
                assertThat(f.path("group").asText()).as(name).isEqualTo("hierarchy");
                assertThat(f.path("hierarchy").asText()).as(name).isEqualTo("MGMT");
                assertThat(f.path("hidden").asBoolean()).as(name + ": offered through its hierarchy").isTrue();
            }
            if ("CURRENCY".equals(name) || "RISK_CLASS".equals(name)) {
                assertThat(f.path("group").asText()).isEqualTo("field");
                assertThat(f.path("hidden").asBoolean()).as(name).isFalse();
            }
            assertThat(f.path("functions").size()).as(name + ": the VaR source's values are its own measures").isZero();
        }
        for (JsonNode m : cat.path("measures")) {
            assertThat(m.path("group").asText()).isEqualTo("measure");
            assertThat(m.path("folder").asText()).as(m.path("name").asText()).isNotBlank();
            assertThat(m.path("functions").size()).isZero();
            if ("var".equals(m.path("name").asText()) || "es".equals(m.path("name").asText())) {
                assertThat(m.path("perCell").asBoolean()).as("VaR is computed per cell, never summed").isTrue();
                assertThat(m.path("folder").asText()).isEqualTo("VaR");
            }
        }
        assertThat(cat.path("showAs").findValuesAsText("id")).contains("pct-grand", "rank", "diff").doesNotContain("running");
        assertThat(cat.path("notes").has("folders")).as("the metadata is lifted out of the notes").isFalse();
    }

    @Test
    void aCellsTailAndItsTradesAndTheLensAsTheViewsOptions() throws Exception {
        String rita = as("rita", "risk");
        Map<String, Object> v = view(List.of("MGMT.DESK"), List.of(), Map.of("var()", "VaR"), Map.of());
        JsonNode d = call(rita, post("/api/v1/risk/mr/detail"), Map.of("view", v, "coords", Map.of("MGMT.DESK", "DESK-X")), 200);
        assertThat(d.get("scenarios").asInt()).isEqualTo(VarLakeFixture.N);
        assertThat(d.get("tail").size()).isPositive();
        assertThat(d.get("counts").size()).isEqualTo(34);
        assertThat(d.get("var").asDouble()).isCloseTo(engineVar("rita", "risk", "NODE:MGMT~DESK-X", null), CENT);
        JsonNode rows = call(rita, post("/api/v1/pivot/rows"), Map.of("view", v, "coords", Map.of("MGMT.DESK", "DESK-X")), 200);
        assertThat(rows.get("columns").get(0).asText()).isEqualTo("TRADE_ID");
        assertThat(rows.get("total").asInt()).isEqualTo(d.get("trades").asInt());
        // the lens: 10 days by √10 and 97.5%, every cell the engine's at the same lens
        VarLens lens = svc.props().lens("10d", null, 0.975, null, null);
        JsonNode a = query(rita, view(List.of("MGMT.DESK"), List.of(), Map.of("var()", "VaR"), Map.of("horizon", "10d", "confidence", "0.975")), 200);
        assertThat(everyCellIsTheEngines(rita, a, "VaR", lens)).isGreaterThanOrEqualTo(3);
        // the scope as an option: the view of one desk, its total the desk's VaR
        JsonNode x = query(rita, view(List.of("MGMT.BOOK"), List.of(), Map.of("var()", "VaR"), Map.of("scope", "NODE:MGMT~DESK-X")), 200);
        JsonNode tx = x.get("cells").get(key(x.get("rows"), "grand") + "|" + key(x.get("columns"), "grand"));
        assertThat(tx.at("/values/VaR").asDouble()).isCloseTo(engineVar("rita", "risk", "NODE:MGMT~DESK-X", null), CENT);
        JsonNode bad = query(rita, view(List.of("MGMT.DESK"), List.of(), Map.of("var()", "VaR"), Map.of("colour", "blue")), 400);
        assertThat(bad.get("detail").asText()).contains("not an option of the VaR source");
        // the headline of the view's scope and the slider's dates
        JsonNode h = call(rita, post("/api/v1/risk/mr/headline"), Map.of("view", view(List.of("MGMT.DESK"), List.of(), Map.of("var()", "VaR"),
                Map.of())), 200);
        assertThat(h.get("var").asDouble()).isEqualTo(540.0);
        assertThat(h.get("limit").asDouble()).isEqualTo(5000.0);
        JsonNode vs = call(rita, get("/api/v1/risk/mr/versions"), null, 200);
        assertThat(vs.findValuesAsText("date")).contains(VarLakeFixture.DAY.toString());
        // the what-if side by side: each desk and the total, before as the pivot shows it
        JsonNode w = call(rita, post("/api/v1/risk/mr/what-if"), Map.of("view", view(List.of("MGMT.DESK"), List.of(), Map.of("var()", "VaR"), Map.of()),
                "items", List.of(Map.of("copyOf", "T5", "factor", -1))), 200);
        JsonNode last = w.get("rows").get(w.get("rows").size() - 1);
        assertThat(last.get("label").asText()).isEqualTo("Total");
        assertThat(last.get("before").asDouble()).isCloseTo(540.0, Offset.offset(0.01));
    }

    @Test
    void aViewOverTheBudgetIsRefusedWithItsCountAndABadViewInWords() throws Exception {
        String rita = as("rita", "risk");
        JsonNode r = query(rita, view(List.of("TRADE"), List.of("CURRENCY"), Map.of("var()", "VaR"), Map.of()), 422);
        assertThat(r.get("code").asText()).isEqualTo("DRS-5702");
        assertThat(r.get("detail").asText()).contains("more than the budget of 20");
        JsonNode bad = query(rita, view(List.of("NOPE"), List.of(), Map.of("var()", "VaR"), Map.of()), 400);
        assertThat(bad.get("code").asText()).isEqualTo("DRS-5701");
        JsonNode measure = query(rita, view(List.of("DESK"), List.of(), Map.of("what()", "W"), Map.of()), 400);
        assertThat(measure.get("detail").asText()).contains("what()");
    }

    @Test
    void aRoleThatSeesADeskInAggregateMayPivotByHierarchyLevelsOnly() throws Exception {
        String head = as("dora", "division-head");
        JsonNode ok = query(head, view(List.of("MGMT.DESK"), List.of(), Map.of("var()", "VaR"), Map.of()), 200);
        assertThat(ok.get("rows").findValuesAsText("value")).contains("DESK-X", "DESK-Y");
        JsonNode refused = query(head, view(List.of("CURRENCY"), List.of(), Map.of("var()", "VaR"), Map.of()), 403);
        assertThat(refused.get("detail").asText()).contains("in aggregate only");
        query(head, view(List.of("RISK_CLASS"), List.of(), Map.of("var()", "VaR"), Map.of()), 403);
    }
}
