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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.banking.compute.var.VarMath;
import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Regulatory VaR end to end (VAR_GUIDE.md sections 18.10, 18.11 and 18.14): a date of 280 scenario days, its official capture, the synthetic
 * history a demo server seeds before it (30 records, each the 250-day VaR of today's members before the day), a desk P&amp;L table whose
 * actual P&amp;L is each desk's scenario P&amp;L of the day before the date. The backtest's exceptions, zone and multiplier are those of an
 * independent count over the same figures; the period statistics those of the records by hand; a report run is frozen with its checks
 * and lineage, signed by maker, checker and approver (one person one step), its files released after the last approval; a failed check
 * blocks sign-off; a restated record leaves a frozen run as it was.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.var.scenario-window=all", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.kinds[4]=desk-pnl",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=VALUE_TYPE,GRAIN,TRADE_ID,POSITION_ID,CURRENCY,SOURCE_SYSTEM,"
                + "LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,COUNTERPARTY,DIRECTION,QUANTITY,PV,"
                + "TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,SCENARIO_SET_ID,BATCH_ID,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.risk-lake.settings.layout.desk-pnl.columns=DESK_ID,LEGAL_ENTITY,ACTUAL_PNL,HYPOTHETICAL_PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.desk-pnl=risk-lake",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false", "drishti.var.currencies=USD",
        "drishti.regulatory.synthetic-history=true", "drishti.regulatory.backtesting.window=25",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class VarRegulatoryApiTest {

    static final LocalDate DAY = VarLakeFixture.DAY;
    static final int N = 280;
    static final int WINDOW = 250;
    static final String[][] TRADES = {{"T1", "BOOK-1", "DESK-X", "LE-A"}, {"T3", "BOOK-1", "DESK-X", "LE-A"}, {"T4", "BOOK-2", "DESK-X", "LE-A"},
            {"T5", "BOOK-3", "DESK-Y", "LE-B"}};
    static Path lake;
    static List<LocalDate> scenarioDays;

    /** The N weekdays before the date, oldest first. */
    static List<LocalDate> weekdaysBefore(LocalDate d, int n) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate x = d.minusDays(1); out.size() < n; x = x.minusDays(1)) {
            if (x.getDayOfWeek() != DayOfWeek.SATURDAY && x.getDayOfWeek() != DayOfWeek.SUNDAY) {
                out.add(0, x);
            }
        }
        return out;
    }

    static double[] vector(String trade) {
        java.util.SplittableRandom r = new java.util.SplittableRandom(trade.hashCode());
        double[] v = new double[N];
        for (int j = 0; j < N; j++) {
            v[j] = Math.round(r.nextGaussian() * 1000 * (r.nextInt(40) == 0 ? 3 : 1));    // fat tails: some exceptions
        }
        return v;
    }

    static double[] sum(String desk) {
        double[] v = new double[N];
        for (String[] t : TRADES) {
            if (desk == null || desk.equals(t[2])) {
                double[] x = vector(t[0]);
                for (int j = 0; j < N; j++) {
                    v[j] += x[j];
                }
            }
        }
        return v;
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-regulatory-lake-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake, DAY, 1.0, false, false);              // the hierarchy and portfolios
        scenarioDays = weekdaysBefore(DAY, N);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String[] t : TRADES) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("VALUE_TYPE", "PNL");
            d.put("GRAIN", "TRADE");
            d.put("TRADE_ID", t[0]);
            d.put("CURRENCY", "USD");
            d.put("SOURCE_SYSTEM", "MUREX");
            d.put("VECTOR_ID", "PNL-" + t[0] + "-HS-280");
            d.put("BUSINESS_DATE", DAY.toString());
            d.put("SCENARIO_SET_ID", "HS-280");
            d.put("BATCH_ID", "B-" + DAY + "-01");
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
            d.put("PNL", vector(t[0]));
            rows.add(d);
        }
        Path work = Files.createDirectories(lake.resolve("_work"));
        VarLakeFixture.table(lake, work, "pnl-vector", "VECTOR_ID", rows, List.of("TRADE_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK",
                "PORTFOLIO", "PRODUCT_GROUP", "PRODUCT_TYPE", "COUNTERPARTY", "DIRECTION", "QUANTITY", "PV", "TRADE_STATUS", "MATURITY_DATE",
                "STATUS", "REPORTING_CCY", "SCENARIO_SET_ID", "BATCH_ID"), "PNL");
        VarLakeFixture.table(lake, work, "scenario-set", "SCENARIO_SET_ID", List.of(Map.of("SCENARIO_SET_ID", "HS-280", "BUSINESS_DATE",
                DAY.toString(), "SCENARIO_DATES", scenarioDays.stream().map(LocalDate::toString).toList())), List.of(), null);
        // the desk P&L of every seeded date and the date: actual the desk's scenario P&L of the day before, hypothetical 90% of it
        List<Map<String, Object>> pnl = new ArrayList<>();
        for (int j = WINDOW; j <= N; j++) {
            LocalDate d = j == N ? DAY : scenarioDays.get(j);
            for (String desk : List.of("DESK-X", "DESK-Y")) {
                double a = sum(desk)[j - 1];
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("DESK_PNL_ID", "DPNL-" + desk + "-" + d);
                row.put("BUSINESS_DATE", d.toString());
                row.put("DESK_ID", desk);
                row.put("LEGAL_ENTITY", desk.equals("DESK-X") ? "LE-A" : "LE-B");
                row.put("ACTUAL_PNL", a);
                row.put("HYPOTHETICAL_PNL", Math.round(a * 0.9));
                row.put("CURRENCY", "USD");
                row.put("SOURCE", "test");
                row.put("AS_OF", d + "T22:00:00Z");
                pnl.add(row);
            }
        }
        VarLakeFixture.table(lake, work, "desk-pnl", "DESK_PNL_ID", pnl, List.of("DESK_ID", "LEGAL_ENTITY", "ACTUAL_PNL", "HYPOTHETICAL_PNL"), null);
        Path reports = Files.createDirectories(lake.resolve("reports"));
        Files.writeString(reports.resolve("annual-report-var.yaml"), """
                report: annual-report-var
                title: Trading VaR by desk
                type: var-table
                units: thousands
                rows:
                  - { label: Desk X, code: X, node: MGMT~DESK-X }
                  - { label: Desk Y, code: Y, node: MGMT~DESK-Y }
                  - { label: Diversification, compute: diversification }
                  - { label: Total, node: MGMT~BANK, total: true }
                checks:
                  - total-equals-official
                  - rows-sum-to-total
                  - no-missing-records
                  - { check: same-total-as, report: var-backtesting-chart }
                  - { check: movement-needs-note, threshold: 0.10 }
                sign-off: [maker, checker, approver]
                step-titles: { approver: CRO }
                """);
        Files.writeString(reports.resolve("var-backtesting-chart.yaml"), """
                report: var-backtesting-chart
                title: Backtesting chart
                type: backtesting-chart
                node: MGMT~BANK
                checks: [total-equals-official, window-complete]
                """);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
        r.add("drishti.var.tier.dir", () -> lake.resolve("hot").toString());
        r.add("drishti.regulatory.reports-dir", reports::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    @Autowired VarOfficial official;
    final ObjectMapper json = new ObjectMapper();

    private String auth(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private JsonNode ok(ResultActions a) throws Exception {
        return json.readTree(a.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode refused(ResultActions a, int code) throws Exception {
        return json.readTree(a.andExpect(status().is(code)).andReturn().getResponse().getContentAsString());
    }

    /** The maker's narrative on the report's own endpoint; a sign-off step (approve, or return: reject) in the one Approvals queue. */
    private ResultActions sign(String user, String period, Map<String, Object> body) throws Exception {
        if ("narrative".equals(body.get("action"))) {
            return mvc.perform(post("/api/v1/risk/var/reports/annual-report-var/runs/" + period + "/narrative").header("Authorization", auth(user, "risk"))
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("narrative", body.get("narrative")))));
        }
        Map<String, Object> decision = new java.util.LinkedHashMap<>();
        decision.put("comment", body.getOrDefault("comment", ""));
        if (body.get("step") != null) {
            decision.put("params", Map.of("step", body.get("step")));
        }
        String action = "return".equals(body.get("action")) ? "reject" : "approve";
        return mvc.perform(post("/api/v1/approvals/var-report/annual-report-var:" + period + ":v" + body.getOrDefault("version", 1) + "/" + action)
                .header("Authorization", auth(user, "risk")).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(decision)));
    }

    /** The seeded VaR of a node on scenario day j: the 250 scenarios before it. */
    static double seeded(double[] v, int j, double q) {
        return VarMath.var(java.util.Arrays.copyOfRange(v, j - WINDOW, j), q);
    }

    @Test
    void theBacktestTheStatisticsAndAReportRunSignedOff() throws Exception {
        String rita = auth("rita", "risk");
        assertThat(official.capture(DAY).get("status")).isIn("captured", "unchanged");
        // a viewer may not seed; an administrator may, on a demo server
        refused(mvc.perform(post("/api/v1/risk/var/official/synthetic-history").header("Authorization", rita)), 403);
        JsonNode seeded = ok(mvc.perform(post("/api/v1/risk/var/official/synthetic-history").header("Authorization", auth("ada", "admin"))));
        assertThat(seeded.get("written").asInt()).isEqualTo(N - WINDOW);
        assertThat(svc.official().dates()).hasSize(N - WINDOW + 1);
        Map<String, Object> rec = svc.official().index(scenarioDays.get(WINDOW)).orElseThrow();
        assertThat(rec.get("synthetic")).isEqualTo(true);

        // the backtest: the official VaR of the day before (seeded: the 250 days before it) against each day's actual P&L
        double[] bank = sum(null);
        List<LocalDate> dates = new ArrayList<>(svc.official().dates());
        double officialToday = ((Number) ((Map<?, ?>) ((Map<?, ?>) svc.official().index(DAY).orElseThrow().get("nodes")).get("MGMT~BANK")).get("var"))
                .doubleValue();
        int actual = 0;
        int hypo = 0;
        int actual975 = 0;
        for (int i = dates.size() - 25; i < dates.size(); i++) {           // the window: the last 25 tested days
            int jPrior = WINDOW + i - 1;                                    // the prior record is scenario day s_(250 + i - 1)
            double v = seeded(bank, jPrior, 0.99);
            double v975 = seeded(bank, jPrior, 0.975);
            double a = bank[WINDOW + i - 1];                                // the day's actual P&L: the scenario P&L of the day before
            actual += a < -v ? 1 : 0;
            hypo += Math.round(sum("DESK-X")[WINDOW + i - 1] * 0.9) + Math.round(sum("DESK-Y")[WINDOW + i - 1] * 0.9) < -v ? 1 : 0;
            actual975 += a < -v975 ? 1 : 0;
        }
        JsonNode bt = ok(mvc.perform(get("/api/v1/risk/var/backtesting").param("scope", "BANK").header("Authorization", rita)));
        assertThat(bt.get("node").asText()).isEqualTo("MGMT~BANK");
        assertThat(bt.get("days").asInt()).isEqualTo(25);
        assertThat(bt.get("full").asBoolean()).isTrue();
        assertThat(bt.get("synthetic").asInt()).isEqualTo(25);
        assertThat(bt.get("var").asDouble()).isCloseTo(officialToday, within(1e-6));
        assertThat(bt.at("/counts/0/actual").asInt()).isEqualTo(actual);
        assertThat(bt.at("/counts/0/hypothetical").asInt()).isEqualTo(hypo);
        assertThat(bt.at("/counts/1/actual").asInt()).isEqualTo(actual975);
        RegulatoryProperties.Backtesting cfg = new RegulatoryProperties.Backtesting(25, null, null, null, null, null);
        assertThat(bt.get("zone").asText()).isEqualTo(cfg.zone(actual));
        assertThat(bt.get("multiplier").asDouble()).isEqualTo(cfg.multiplier(actual));
        assertThat(bt.get("rows")).hasSize(25);
        assertThat(bt.get("exceptions").size()).isGreaterThanOrEqualTo(actual);
        assertThat(bt.get("parts")).hasSize(3);                             // the bank and its two desks
        JsonNode desk = ok(mvc.perform(get("/api/v1/risk/var/backtesting").param("scope", "DESK:DESK-Y").header("Authorization", rita)));
        assertThat(desk.get("node").asText()).isEqualTo("MGMT~DESK-Y");
        JsonNode book = ok(mvc.perform(get("/api/v1/risk/var/backtesting").param("scope", "NODE:MGMT~BOOK-3").header("Authorization", rita)));
        assertThat(book.get("applicable").asBoolean()).isFalse();
        byte[] xlsx = mvc.perform(get("/api/v1/risk/var/backtesting").param("scope", "BANK").param("format", "xlsx").header("Authorization", rita))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(xlsx, 0, 2, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("PK");

        // the period statistics over the records, by hand
        LocalDate from = dates.get(0);
        String range = from + ".." + DAY;
        JsonNode st = ok(mvc.perform(get("/api/v1/risk/var/statistics").param("scope", "BANK").param("period", range).header("Authorization", rita)));
        double hi = Double.NEGATIVE_INFINITY;
        double lo = Double.POSITIVE_INFINITY;
        double total = 0;
        for (int i = 0; i < dates.size(); i++) {
            double v = i == dates.size() - 1 ? officialToday : seeded(bank, WINDOW + i, 0.99);
            hi = Math.max(hi, v);
            lo = Math.min(lo, v);
            total += v;
        }
        assertThat(st.get("records").asInt()).isEqualTo(dates.size());
        assertThat(st.at("/periodEnd/value").asDouble()).isCloseTo(officialToday, within(1e-6));
        assertThat(st.at("/high/value").asDouble()).isCloseTo(hi, within(1e-6));
        assertThat(st.at("/low/value").asDouble()).isCloseTo(lo, within(1e-6));
        assertThat(st.get("average").asDouble()).isCloseTo(total / dates.size(), within(1e-6));
        assertThat(st.get("missing")).isEmpty();
        assertThat(st.at("/average60/records").asInt()).isEqualTo(dates.size());
        refused(mvc.perform(get("/api/v1/risk/var/statistics").param("scope", "BANK").param("period", "last year").header("Authorization", rita)), 400);

        // a report run: generated, frozen with its checks and lineage
        JsonNode run = ok(mvc.perform(post("/api/v1/risk/var/reports/annual-report-var/runs").param("period", range).header("Authorization", auth("rao", "risk"))));
        assertThat(run.get("version").asInt()).isEqualTo(1);
        for (JsonNode c : run.get("checks")) {
            assertThat(c.get("status").asText()).as(c.toString()).isIn("pass", "note");
        }
        JsonNode totalRow = run.get("rows").get(3);
        assertThat(totalRow.at("/cells/period-end/value").asDouble()).isCloseTo(officialToday, within(1e-6));
        assertThat(totalRow.at("/cells/period-end/lineage/date").asText()).isEqualTo(DAY.toString());
        assertThat(run.get("rows").get(2).at("/cells/high/text").asText()).isEqualTo("n/m");
        assertThat(run.at("/signoff/state").asText()).isEqualTo("with maker");
        // files before the last approval: a draft only
        refused(mvc.perform(get("/api/v1/risk/var/reports/annual-report-var/runs/" + range + "/file").param("format", "xlsx")
                .header("Authorization", rita)), 409);
        mvc.perform(get("/api/v1/risk/var/reports/annual-report-var/runs/" + range + "/file").param("format", "pdf").param("draft", "true")
                .header("Authorization", rita)).andExpect(status().isOk());
        // maker, then the same person as checker is refused (four eyes), another checker, then the approver
        ok(sign("rao", range, Map.of("action", "narrative", "narrative", "The year in one line.")));
        ok(sign("rao", range, Map.of("action", "approve", "step", "maker", "comment", "prepared")));
        assertThat(refused(sign("rao", range, Map.of("action", "approve", "step", "checker")), 409).get("detail").asText()).contains("four eyes");
        ok(sign("chen", range, Map.of("action", "approve", "step", "checker", "comment", "checked")));
        assertThat(ok(sign("cro", range, Map.of("action", "approve", "step", "approver"))).path("status").asText()).isEqualTo("signed");
        JsonNode done = ok(mvc.perform(get("/api/v1/risk/var/reports/annual-report-var/runs/" + range).header("Authorization", rita)));
        assertThat(done.at("/signoff/state").asText()).isEqualTo("signed");
        assertThat(done.at("/signoff/narrative").asText()).isEqualTo("The year in one line.");
        byte[] pdf = mvc.perform(get("/api/v1/risk/var/reports/annual-report-var/runs/" + range + "/file").param("format", "pdf")
                .header("Authorization", rita)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        String csv = mvc.perform(get("/api/v1/risk/var/reports/annual-report-var/runs/" + range + "/file").param("format", "csv")
                .header("Authorization", rita)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("Diversification").contains("Total");

        // a restated record leaves the frozen run as it was
        Map<String, Object> today = new LinkedHashMap<>(svc.official().index(DAY).orElseThrow());
        @SuppressWarnings("unchecked")
        Map<String, Object> nodes = new LinkedHashMap<>((Map<String, Object>) today.get("nodes"));
        @SuppressWarnings("unchecked")
        Map<String, Object> b = new LinkedHashMap<>((Map<String, Object>) nodes.get("MGMT~BANK"));
        b.put("var", officialToday * 2);
        nodes.put("MGMT~BANK", b);
        today.put("nodes", nodes);
        today.put("resultVersion", 2);
        svc.official().putIndex(DAY, today, VarDate.USD);
        JsonNode again = ok(mvc.perform(get("/api/v1/risk/var/reports/annual-report-var/runs/" + range).param("version", "1").header("Authorization", rita)));
        assertThat(again.get("rows").get(3).at("/cells/period-end/value").asDouble()).isCloseTo(officialToday, within(1e-6));
        // a period that starts before the records: the missing-records check fails, and blocks sign-off
        String longer = from.minusDays(30) + ".." + DAY;
        JsonNode bad = ok(mvc.perform(post("/api/v1/risk/var/reports/annual-report-var/runs").param("period", longer).header("Authorization", rita)));
        assertThat(bad.toString()).contains("\"status\":\"fail\"");
        assertThat(refused(sign("rao", longer, Map.of("action", "approve", "step", "maker", "comment", "x")), 409).get("detail").asText())
                .contains("failed");
        JsonNode list = ok(mvc.perform(get("/api/v1/risk/var/reports").header("Authorization", rita)));
        assertThat(list.get("reports")).hasSize(2);
    }

    @Test
    void zonesMultipliersPeriodsAndDefinitions() {
        RegulatoryProperties.Backtesting b = new RegulatoryProperties.Backtesting(null, null, null, null, null, null);
        assertThat(List.of(0, 4, 5, 9, 10).stream().map(b::zone).toList()).containsExactly("green", "green", "amber", "amber", "red");
        assertThat(List.of(0, 4, 5, 6, 9, 10, 14).stream().map(b::multiplier).toList()).containsExactly(3.0, 3.0, 3.4, 3.5, 3.85, 4.0, 4.0);
        java.time.MonthDay oct = java.time.MonthDay.of(10, 31);
        VarPeriodStats.Period fy = VarPeriodStats.period("FY2026", oct);
        assertThat(fy.from()).isEqualTo(LocalDate.of(2025, 11, 1));
        assertThat(fy.to()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(VarPeriodStats.period("FY2026Q1", oct).to()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(VarPeriodStats.fiscalYear(LocalDate.of(2026, 11, 2), oct)).isEqualTo("FY2027");
        assertThat(VarPeriodStats.priorYear(fy, oct).name()).isEqualTo("FY2025");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> VarReportDefinition.parse("x", "report: x\ncolour: blue\n", "x.yaml"))
                .hasMessageContaining("x.yaml: unknown field 'colour'");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> VarReportDefinition.parse("x", "report: x\nrows: []\n", "x.yaml"))
                .hasMessageContaining("one row with total: true");
    }
}
