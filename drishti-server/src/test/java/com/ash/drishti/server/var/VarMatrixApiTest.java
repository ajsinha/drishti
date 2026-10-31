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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.api.DataNode;
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
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * The scenario matrix (VAR_GUIDE.md section 3.9) over {@link VarLakeFixture}'s trades and {@link VarPvFixture}'s scenario PVs on three
 * business dates (every vector times 0.5, 0.8 and 1.0) and a date that is not one run: the values against the stored vectors, read from
 * the lake by id on a date not open and computed in memory on the open one (equal); scenario PVs to P&amp;L; the VaR rank and the ES tail
 * per date; a node from its official capture; a position by trade; gaps with their DRS reasons, never zeros; the person's nodes and
 * masks; the cap on N; the parameters; Arrow.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.kinds[4]=scenario-pv", "drishti.sources.connectors.risk-lake.kinds[5]=fx-scenario",
        "drishti.sources.connectors.risk-lake.kinds[6]=fx-spot",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
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
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false", "drishti.var.currencies=USD",
        "drishti.var.hot-dates=1", "drishti.var.smat.max-days=5",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact[0]=COUNTERPARTY",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.desk-y.kinds[0]=*", "drishti.security.roles.desk-y.raw=true", "drishti.var.access.desk-y[MGMT~DESK-Y]=positions",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VarMatrixApiTest {

    static final LocalDate D2 = VarLakeFixture.DAY;
    static final LocalDate D1 = D2.minusDays(1);
    static final LocalDate D0 = D2.minusDays(2);
    /** A date whose vectors come from two runs: the engine refuses it (DRS-5203). */
    static final LocalDate DR = D2.minusDays(3);
    static final Map<LocalDate, Double> FACTOR = Map.of(D0, 0.5, D1, 0.8, D2, 1.0);
    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-matrix-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake, DR, 1.0, true, false);
        for (LocalDate d : List.of(D0, D1, D2)) {
            VarLakeFixture.write(lake, d, FACTOR.get(d), false, false);
            vectors(d, FACTOR.get(d));                                 // the same rows with the ids of 2.9.5 on (PNL-<trade>-<set>)
            VarPvFixture.write(lake, d);
        }
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
    }

    /** The fixture's P&amp;L vectors of a date again, keyed {@code PNL-<trade>-HS-20}, as a batch of 2.9.5 on writes them. */
    static void vectors(LocalDate day, double factor) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VarLakeFixture.Trade t : VarLakeFixture.trades()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("VECTOR_ID", "PNL-" + t.id() + "-HS-20");
            d.put("TRADE_ID", t.id());
            d.put("BUSINESS_DATE", day.toString());
            d.put("SCENARIO_SET_ID", "HS-20");
            d.put("BATCH_ID", "B-" + day + "-01");
            d.put("LEGAL_ENTITY", t.le());
            d.put("DIVISION", "DIV-M");
            d.put("DESK", t.desk());
            d.put("TT_BOOK", t.book());
            d.put("PORTFOLIO", t.book());
            d.put("PRODUCT_GROUP", t.security() == null ? "Rates" : "Fixed income");
            d.put("PRODUCT_TYPE", t.security() == null ? "IRS" : "GOVT_BOND");
            d.put("SECURITY_ID", t.security());
            d.put("CURRENCY", t.ccy());
            d.put("COUNTERPARTY", t.cpty());
            d.put("DIRECTION", t.qty() >= 0 ? "LONG" : "SHORT");
            d.put("QUANTITY", t.qty());
            d.put("PV", 1000.0);
            d.put("TRADE_STATUS", t.status());
            d.put("MATURITY_DATE", "2030-01-15");
            d.put("STATUS", "ok");
            d.put("REPORTING_CCY", "USD");
            d.put("PNL", t.v() instanceof double[] a ? VarLakeFixture.scale(a, factor) : t.v());
            rows.add(d);
        }
        VarLakeFixture.table(lake, Files.createDirectories(lake.resolve("_work")), "pnl-vector", "VECTOR_ID", rows, List.of("TRADE_ID",
                "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK", "PORTFOLIO", "PRODUCT_GROUP", "PRODUCT_TYPE", "SECURITY_ID", "CURRENCY",
                "COUNTERPARTY", "DIRECTION", "QUANTITY", "PV", "TRADE_STATUS", "MATURITY_DATE", "STATUS", "REPORTING_CCY", "SCENARIO_SET_ID",
                "BATCH_ID"), "PNL");
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    @Autowired VarOfficial official;
    final ObjectMapper json = new ObjectMapper();

    private JsonNode ask(String role, ResultMatcher expect, String... params) throws Exception {
        var req = get("/api/v1/var/matrix").param("asOf", D2.toString()).header("Authorization", "Bearer " + tokens.mint("rita", List.of(role), 300));
        for (int i = 0; i < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return json.readTree(mvc.perform(req).andExpect(expect).andReturn().getResponse().getContentAsString());
    }

    private JsonNode ask(String... params) throws Exception {
        return ask("risk", status().isOk(), params);
    }

    private static JsonNode row(JsonNode m, String scenario) {
        for (JsonNode r : m.get("rows")) {
            if (r.get("scenario").asText().equals(scenario)) {
                return r;
            }
        }
        throw new AssertionError("no row " + scenario);
    }

    private static JsonNode col(JsonNode m, LocalDate d) {
        for (JsonNode c : m.get("dates")) {
            if (c.get("date").asText().equals(d.toString())) {
                return c;
            }
        }
        throw new AssertionError("no column " + d);
    }

    static final List<String> DAYS = VarLakeFixture.scenarioDates();

    @Test
    @Order(1)
    void aTradesColumnsReadFromTheLakeByIdEqualTheStoredVectorsAndTheEngine() throws Exception {
        JsonNode m = ask("id", "T1", "days", "4");
        assertThat(m.get("scope").asText()).isEqualTo("TRD:T1");
        assertThat(m.get("kind").asText()).isEqualTo("trade");
        assertThat(m.get("days").asInt()).isEqualTo(4);
        assertThat(col(m, D2).get("source").asText()).isEqualTo("open");
        assertThat(col(m, D1).get("source").asText()).isEqualTo("lake");   // not open (one hot date): its record read by id
        assertThat(col(m, D0).get("source").asText()).isEqualTo("lake");
        assertThat(col(m, D1).get("link").asText()).isEqualTo("/v/pnl-vector/PNL-T1-HS-20");
        assertThat(col(m, D1).get("batch").asText()).isEqualTo("B-" + D1 + "-01");
        // the date of two runs: the record is not there under its id: a gap with its reason, every cell null (never zero)
        assertThat(col(m, DR).get("gap").asText()).startsWith("DRS-1001");
        assertThat(m.get("rows")).hasSize(VarLakeFixture.N);
        for (int j = 0; j < VarLakeFixture.N; j++) {
            JsonNode r = row(m, DAYS.get(j));
            assertThat(r.get(DR.toString()).isNull()).isTrue();
            for (LocalDate d : List.of(D0, D1, D2)) {
                assertThat(r.get(d.toString()).asDouble()).as(d + " " + j).isEqualTo(VarLakeFixture.U[j] * 100 * FACTOR.get(d));
            }
        }
        // the VaR rank and the ES tail per date: 20 scenarios, the worst is both (rank 1); the base PV is the vector's PV
        JsonNode c2 = col(m, D2);
        assertThat(c2.get("k").asInt()).isEqualTo(1);
        assertThat(c2.get("var").asDouble()).isEqualTo(1000.0);
        assertThat(c2.get("varScenario").asText()).isEqualTo(DAYS.get(7));
        assertThat(c2.get("tail").get(0).get("scenario").asText()).isEqualTo(DAYS.get(7));
        assertThat(c2.get("tail").get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(col(m, D0).get("var").asDouble()).isEqualTo(500.0);
        assertThat(c2.get("basePv").asDouble()).isEqualTo(1000.0);
        assertThat(m.at("/labels/var").asText()).isEqualTo("VaR 99% 1-day");
        // the engine's own vector of the date read from the lake is the same
        double[] engine = svc.scoped(null, "TRD:T1", D0, null).vector();
        for (int j = 0; j < engine.length; j++) {
            assertThat(row(m, DAYS.get(j)).get(D0.toString()).asDouble()).isEqualTo(engine[j]);
        }
    }

    @Test
    @Order(2)
    void scenarioPvsBecomeTheirUsdPnlAndTheIdOfTheRecordFindsItsTrade() throws Exception {
        JsonNode m = ask("id", "SPV-P1-HS-20", "days", "3");
        assertThat(m.get("scope").asText()).isEqualTo("TRD:P1");
        for (LocalDate d : List.of(D0, D1, D2)) {
            JsonNode c = col(m, d);
            assertThat(c.get("valueType").asText()).as(d.toString()).isEqualTo("PV");
            assertThat(c.get("basePv").asDouble()).as(d.toString()).isEqualTo(1200.0);       // 1,000 EUR at 1.20 today
            // P&L = PV(s) x FX(s) - PV(base) x FX(base): scenario 7 (1000 - 100) x 1.00 - 1200; scenario 0 (1000 + 10) x 1.20 - 1200
            assertThat(row(m, DAYS.get(7)).get(d.toString()).asDouble()).isEqualTo(-300.0);
            assertThat(row(m, DAYS.get(0)).get(d.toString()).asDouble()).isEqualTo(12.0);
            // the PV shown is the base plus the P&L: 900 USD under scenario 7
            assertThat(c.get("basePv").asDouble() + row(m, DAYS.get(7)).get(d.toString()).asDouble()).isEqualTo(900.0);
        }
        assertThat(col(m, D0).get("source").asText()).isEqualTo("lake");
        assertThat(col(m, D0).get("fx").asText()).contains("EUR to USD");
        assertThat(col(m, D0).get("link").asText()).isEqualTo("/v/scenario-pv/SPV-P1-HS-20");
    }

    @Test
    @Order(3)
    void aNodeReadsItsOfficialCaptureOnADateNotOpenAndGapsSayWhy() throws Exception {
        assertThat(official.capture(D1).get("status")).isEqualTo("captured");
        double[] d1 = svc.scoped(null, "NODE:MGMT~DESK-X", D1, null).vector();
        JsonNode m = ask("id", "NODE:MGMT~DESK-X", "days", "4");          // the newest date opened again: D1 leaves memory
        assertThat(m.get("kind").asText()).isEqualTo("node");
        assertThat(col(m, D1).get("source").asText()).isEqualTo("official");
        assertThat(col(m, D1).get("link").asText()).startsWith("/var?capture=CAP-");
        assertThat(col(m, D0).get("source").asText()).isEqualTo("engine");   // no official record: the engine opened it
        assertThat(col(m, D2).get("source").asText()).isEqualTo("open");
        for (int j = 0; j < d1.length; j++) {
            assertThat(row(m, DAYS.get(j)).get(D1.toString()).asDouble()).isEqualTo((double) Math.round(d1[j]));
        }
        // the date of two runs is refused by the engine: its column says DRS-5203 and holds no number
        assertThat(col(m, DR).get("gap").asText()).startsWith("DRS-5203");
        assertThat(col(m, DR).get("code").asText()).isEqualTo("DRS-5203");
        assertThat(row(m, DAYS.get(7)).get(DR.toString()).isNull()).isTrue();
    }

    @Test
    @Order(4)
    void aPositionByTradeWithTheCounterpartyMaskedForAMaskedRole() throws Exception {
        JsonNode m = ask("id", "POS-BOOK-1~SEC-A", "days", "3", "by", "trade");
        assertThat(m.get("by").asText()).isEqualTo("trade");
        assertThat(m.get("scenario").asText()).isEqualTo(DAYS.get(7));      // the newest date's VaR scenario
        Map<String, JsonNode> rows = new LinkedHashMap<>();
        m.get("rows").forEach(r -> rows.put(r.get("trade").asText(), r));
        assertThat(rows.keySet()).containsExactlyInAnyOrder("T1", "T2");
        for (LocalDate d : List.of(D0, D1, D2)) {
            assertThat(rows.get("T1").get(d.toString()).asDouble()).isEqualTo(-1000 * FACTOR.get(d));
            assertThat(rows.get("T2").get(d.toString()).asDouble()).isEqualTo(400 * FACTOR.get(d));
            assertThat(col(m, d).get("total").asDouble()).isEqualTo(-600 * FACTOR.get(d));
            assertThat(col(m, d).get("rank").asInt()).isEqualTo(1);
        }
        assertThat(rows.get("T1").get("counterparty").asText()).isEqualTo("CP-1");
        JsonNode masked = ask("masked", status().isOk(), "id", "POS-BOOK-1~SEC-A", "days", "1", "by", "trade");
        masked.get("rows").forEach(r -> assertThat(r.get("counterparty").asText()).isEqualTo(DataNode.MASK));
        // a trade has no trades: refused in words
        assertThat(ask("risk", status().isBadRequest(), "id", "T1", "by", "trade").get("detail").asText()).contains("by trade is for a position");
    }

    @Test
    @Order(5)
    void readAsThePersonCappedAndTheParametersChecked() throws Exception {
        assertThat(ask("desk-y", status().isForbidden(), "id", "T1").get("code").asText()).isEqualTo("DRS-5002");   // DESK-X is not theirs
        assertThat(ask("desk-y", status().isOk(), "id", "T5", "days", "1").get("scope").asText()).isEqualTo("TRD:T5");
        JsonNode capped = ask("id", "T1", "days", "9");
        assertThat(capped.get("maxDays").asInt()).isEqualTo(5);
        assertThat(capped.get("note").asText()).contains("at most 5").contains("drishti.var.smat.max-days");
        assertThat(ask("risk", status().isBadRequest(), "id", "T1", "horizon", "10").get("detail").asText()).contains("1-day horizon");
        assertThat(ask("risk", status().isBadRequest(), "id", "T1", "confidence", "1.2").get("detail").asText()).contains("not in (0.5, 1)");
        JsonNode at95 = ask("id", "T1", "days", "1", "confidence", "0.95");
        assertThat(at95.at("/labels/var").asText()).isEqualTo("VaR 95% 1-day");
        assertThat(col(at95, D2).get("k").asInt()).isEqualTo(1);           // ceil(20 x 0.05)
        assertThat(ask("risk", status().isNotFound(), "id", "NO-SUCH-THING").get("detail").asText()).contains("SMAT takes");
        var arrow = mvc.perform(get("/api/v1/var/matrix").param("id", "T1").param("days", "3").param("asOf", D2.toString())
                .header("Accept", "application/vnd.apache.arrow.stream").header("Authorization", "Bearer " + tokens.mint("rita", List.of("risk"), 300)))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(arrow.getContentType()).startsWith("application/vnd.apache.arrow.stream");
        assertThat(arrow.getContentAsByteArray().length).isGreaterThan(100);
    }

    @Test
    void aRecordTheEngineWouldRefuseIsAGapWithItsCode() {
        VarLoader.ScenarioSet set = new VarLoader.ScenarioSet("HS-3", List.of(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"),
                LocalDate.parse("2026-09-03")), List.of(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"), LocalDate.parse("2026-09-03")));
        LocalDate d = LocalDate.parse("2026-09-30");
        assertThat(VarMatrixLake.refused(DataNode.of(Map.of("PNL", List.of(1.0, 2.0))), d, set, "PNL")).startsWith("DRS-5201 wrong length");
        List<Object> withNull = new ArrayList<>(List.of(1.0, 2.0));
        withNull.add(null);
        assertThat(VarMatrixLake.refused(DataNode.of(Map.of("PNL", withNull)), d, set, "PNL")).startsWith("DRS-5206");
        assertThat(VarMatrixLake.refused(DataNode.of(Map.of("PNL", List.of(1.0, 2.0, 3.0), "STATUS", "failed")), d, set, "PNL")).startsWith("DRS-5206");
        assertThat(VarMatrixLake.refused(DataNode.of(Map.of("PNL", List.of(1.0, 2.0, 3.0), "SCENARIO_SET_ID", "HS-9")), d, set, "PNL"))
                .startsWith("DRS-5203");
        assertThat(VarMatrixLake.refused(DataNode.of(Map.of("PNL", List.of(1.0, 2.0, 3.0), "TRADE_STATUS", "CANCELLED")), d, set, "PNL"))
                .contains("CANCELLED");
        assertThat(VarMatrixLake.refused(DataNode.of(Map.of("PNL", List.of(1.0, 2.0, 3.0), "TRADE_STATUS", "VALIDATED")), d, set, "PNL")).isNull();
        assertThat(VarMatrix.recordOf("PNL-T1-HS-20")).isEqualTo("T1");
        assertThat(VarMatrix.recordOf("SPV-MX-20000274-HS-509")).isEqualTo("MX-20000274");
        assertThat(VarMatrix.recordOf("T1")).isNull();
    }
}
