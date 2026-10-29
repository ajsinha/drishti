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
package com.ash.drishti.compute.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The equality tests of 2.9.5 (VAR_GUIDE.md section 16) on the committed test data of 2026-09-30, read straight from
 * {@code data/testdata/market-risk/} as {@link VarTestDataPrecisionTest} reads it:
 *
 * <ul>
 *   <li>MGMT built from the columns {@code DIVISION > DESK > TT_BOOK} (leaf ids, levels division, desk, book) and LEGAL from
 *       {@code LEGAL_ENTITY > DESK > TT_BOOK}, against the same trees from the {@code risk-hierarchy} rows: the same node ids, parents,
 *       levels and members; the aggregates to the bit; VaR, ES, the children's component VaR and the worst scenarios to the cent at every
 *       node; in USD and in EUR (the books converted with the supplied scenario FX); every node's limit from {@code var-limit} on both
 *       sides (2.9.6: the rows carry no {@code LIMIT_VAR}, every VaR limit lives in {@code var-limit});</li>
 *   <li>the limit letters (LL, {@code LIMIT_LETTER > DESK}): every row carries a letter; some letter holds part of a book; each letter's
 *       and each letter-desk's vector is exactly the sum of its rows (the trades named one by one, as {@code TRD:} lists name them).</li>
 * </ul>
 */
class HierarchyEquivalenceTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static ParallelSums sums;
    static Path data;
    static PositionRules rules;
    static List<LocalDate> scen;
    static ScenarioFx fx;
    static Map<String, Double> varLimits = new HashMap<>();
    static VarBook rows;
    static VarBook columns;
    static VarBook letters;

    static final HierarchySpec MGMT = new HierarchySpec("MGMT", "Management", "columns", List.of("DIVISION", "DESK", "TT_BOOK"),
            List.of("division", "desk", "book"), "BANK", "Drishti Bank", true, null, "var-limit");
    static final HierarchySpec LEGAL = new HierarchySpec("LEGAL", "Legal entities", "columns", List.of("LEGAL_ENTITY", "DESK", "TT_BOOK"),
            List.of("legal-entity", "desk", "book"), "BANK", "Drishti Bank", true, null, "var-limit");
    static final HierarchySpec LL = new HierarchySpec("LL", "Limit letters", "columns", List.of("LIMIT_LETTER", "DESK"), null, null, null, false,
            "LL", "var-limit");

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws IOException {
        Path repo = VarTestDataPrecisionTest.root();
        data = repo.resolve("data/testdata/market-risk");
        Map<String, Object> pack = new ObjectMapper(new YAMLFactory()).readValue(repo.resolve("config/packs/market-risk/pack.yaml").toFile(), Map.class);
        rules = PositionRules.of((Map<?, ?>) pack.get("positions"));
        scen = new ArrayList<>();
        VarTestDataPrecisionTest.lines(data.resolve("scenario-set"), n -> n.path("SCENARIO_DATES").forEach(d -> scen.add(LocalDate.parse(d.asText()))));
        fx = VarTestDataPrecisionTest.fx(data, scen.size());
        VarTestDataPrecisionTest.lines(data.resolve("var-limit"), n -> varLimits.put(n.path("NODE_ID").asText(), n.path("LIMIT_VAR").asDouble()));
        sums = new ParallelSums(4, 4096);
        rows = build(List.of(), true);
        columns = build(List.of(MGMT, LEGAL), false);
        letters = build(List.of(LL), true);
    }

    @AfterAll
    static void close() {
        sums.close();
    }

    /** The date's book: the risk-hierarchy rows' trees when {@code fromRows}, and the given column hierarchies with the var-limit rows. */
    static VarBook build(List<HierarchySpec> specs, boolean fromRows) throws IOException {
        VarBook.Builder b = new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", scen, 65_536, Long.MAX_VALUE);
        b.columnHierarchies(specs, varLimits);
        b.currencyRates(c -> fx.rates(c).orElse(null));
        VarTestDataPrecisionTest.lines(data.resolve("scenario-pv"), n -> {
            if (rules.live(VarTestDataPrecisionTest.text(n, "TRADE_STATUS"))) {
                JsonNode pvs = n.get("SCENARIO_PV");
                double[] p = new double[pvs.size()];
                for (int j = 0; j < p.length; j++) {
                    p[j] = pvs.get(j).asDouble();
                }
                ScenarioFx.Rates r = fx.rates(VarTestDataPrecisionTest.text(n, "CURRENCY")).orElseThrow();
                double[] v = new double[p.length];
                ScenarioFx.pnlFromPv(n.path("BASE_PV").asDouble(), p, p.length, r, v);
                add(b, n, v, n.path("BASE_PV").asDouble() * r.base(), VarTestDataPrecisionTest.text(n, "CURRENCY"));
            }
        });
        VarTestDataPrecisionTest.lines(data.resolve("pnl-vector"), n -> {
            if (rules.live(VarTestDataPrecisionTest.text(n, "TRADE_STATUS"))) {
                JsonNode pnl = n.get("PNL");
                double[] v = new double[pnl.size()];
                for (int j = 0; j < v.length; j++) {
                    v[j] = pnl.get(j).asDouble();
                }
                add(b, n, v, n.path("PV").asDouble(Double.NaN), null);
            }
        });
        if (fromRows) {
            VarTestDataPrecisionTest.lines(data.resolve("risk-hierarchy"), n -> {
                List<String> books = new ArrayList<>();
                n.path("BOOKS").forEach(x -> books.add(x.asText()));
                b.node(n.path("NODE_ID").asText(), n.path("HIERARCHY").asText(), n.path("LEVEL").asText(), n.path("NAME").asText(),
                        VarTestDataPrecisionTest.text(n, "PARENT_ID"), books, varLimits.getOrDefault(n.path("NODE_ID").asText(), Double.NaN));
                assertThat(n.has("LIMIT_VAR")).as("a risk-hierarchy row carries no LIMIT_VAR (2.9.6)").isFalse();
            });
        }
        return b.build(sums);
    }

    private static void add(VarBook.Builder b, JsonNode n, double[] v, double pvUsd, String pvCurrency) {
        List<String> cols = b.textColumns();
        String[] texts = new String[cols.size()];
        for (int c = 0; c < texts.length; c++) {
            texts[c] = VarTestDataPrecisionTest.text(n, cols.get(c));
        }
        String trade = VarTestDataPrecisionTest.text(n, "TRADE_ID");
        PositionRules.Derived d = rules.derive(trade, VarTestDataPrecisionTest.text(n, "TT_BOOK"), VarTestDataPrecisionTest.text(n, "PRODUCT_GROUP"),
                VarTestDataPrecisionTest.text(n, "PRODUCT_TYPE"), k -> VarTestDataPrecisionTest.text(n, k));
        texts[cols.indexOf("POSITION_ID")] = d.positionId();
        texts[cols.indexOf("POSITION_RULE")] = d.rule();
        texts[cols.indexOf("INSTRUMENT_ID")] = d.instrumentId();
        texts[cols.indexOf("SECURITY_ID")] = d.securityId();
        texts[cols.indexOf(CurrencyTotals.COLUMN)] = pvCurrency;
        b.trade(trade, v, v.length, texts, new double[] {n.path("QUANTITY").asDouble(Double.NaN), pvUsd});
    }

    static void sameTrees(VarBook a, VarBook b, String what) {
        VarCalc calc = VarCalc.defaults();
        assertThat(new TreeSet<>(b.nodes().keySet())).as(what + ": node ids").isEqualTo(new TreeSet<>(a.nodes().keySet()));
        for (VarBook.Node x : a.nodes().values()) {
            VarBook.Node y = b.node(x.id());
            assertThat(y.parent()).as(what + " parent of " + x.id()).isEqualTo(x.parent());
            assertThat(y.level()).as(what + " level of " + x.id()).isEqualTo(x.level());
            assertThat(y.hierarchy()).isEqualTo(x.hierarchy());
            assertThat(new TreeSet<>(y.children())).as(what + " children of " + x.id()).isEqualTo(new TreeSet<>(x.children()));
            assertThat(y.members()).as(what + " members of " + x.id()).isEqualTo(x.members());
            double[] va = a.aggregate(x.id());
            double[] vb = b.aggregate(x.id());
            assertThat(vb).as(what + " aggregate of " + x.id() + " to the bit").isEqualTo(va);
            VarCalc.Measures ma = calc.measures(va);
            VarCalc.Measures mb = calc.measures(vb);
            assertThat(mb.var()).as(what + " VaR " + x.id()).isCloseTo(ma.var(), within(0.005));
            assertThat(mb.es()).as(what + " ES " + x.id()).isCloseTo(ma.es(), within(0.005));
            assertThat(java.util.Arrays.copyOf(mb.order(), 10)).as(what + " worst scenarios " + x.id()).isEqualTo(java.util.Arrays.copyOf(ma.order(), 10));
            if (!x.children().isEmpty()) {
                List<VarCalc.Contribution> ca = calc.contributions(va, ma, parts(a, x));
                List<VarCalc.Contribution> cb = calc.contributions(vb, mb, parts(b, x));
                Map<String, Double> byKey = new LinkedHashMap<>();
                ca.forEach(c -> byKey.put(c.key(), c.componentVar()));
                for (VarCalc.Contribution c : cb) {
                    assertThat(c.componentVar()).as(what + " component of " + c.key() + " in " + x.id()).isCloseTo(byKey.get(c.key()), within(0.005));
                }
            }
        }
    }

    private static List<VarCalc.Part> parts(VarBook b, VarBook.Node n) {
        List<VarCalc.Part> out = new ArrayList<>();
        for (String c : new TreeSet<>(n.children())) {
            out.add(new VarCalc.Part(c, c, "node", b.aggregate(c), b.node(c).members().size()));
        }
        return out;
    }

    @Test
    void mgmtAndLegalFromTheColumnsAreTheTreesOfTheRiskHierarchyRows() {
        assertThat(rows.nodes()).hasSize(88);
        assertThat(columns.cellColumns()).containsExactly("DIVISION", "DESK", "LEGAL_ENTITY");
        sameTrees(rows, columns, "USD");
        // the limits: var-limit's rows give each node its limit, the same on both sides, and every MGMT and LEGAL node has one (2.9.6)
        for (VarBook.Node x : rows.nodes().values()) {
            assertThat(columns.node(x.id()).limit()).as("limit of " + x.id()).isEqualTo(x.limit());
            assertThat(x.limit()).as("limit of " + x.id()).isPositive();
        }
    }

    @Test
    void theSameInEuro() {
        ScenarioFx.Rates eur = fx.rates("EUR").orElseThrow();
        VarBook a = CurrencyBook.convert(rows, eur, new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", scen, 65_536, Long.MAX_VALUE), sums).book();
        VarBook b = CurrencyBook.convert(columns, eur, new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", scen, 65_536, Long.MAX_VALUE), sums)
                .book();
        sameTrees(a, b, "EUR");
        for (VarBook.Node x : a.nodes().values()) {
            assertThat(b.node(x.id()).limit()).as("EUR limit of " + x.id()).isCloseTo(x.limit(), within(0.005));
        }
    }

    @Test
    void aLimitLetterIsExactlyTheSumOfItsRowsAndSomeHoldPartOfABook() {
        assertThat(letters.cellColumns()).containsExactly("LIMIT_LETTER", "DESK");
        Map<String, List<String>> byLetter = new LinkedHashMap<>();
        Map<String, Set<String>> lettersOfBook = new HashMap<>();
        for (int o = 0; o < letters.tradeCount(); o++) {
            String l = letters.trades().text("LIMIT_LETTER", o);
            assertThat(l).as("the limit letter of " + letters.tradeId(o)).isNotBlank();
            byLetter.computeIfAbsent("LL~" + l, k -> new ArrayList<>()).add(letters.tradeId(o));
            byLetter.computeIfAbsent("LL~" + l + "/" + letters.trades().text("DESK", o), k -> new ArrayList<>()).add(letters.tradeId(o));
            lettersOfBook.computeIfAbsent(letters.trades().text("TT_BOOK", o), k -> new TreeSet<>()).add(l);
        }
        assertThat(lettersOfBook.values().stream().filter(s -> s.size() > 1).count()).as("books split between letters").isGreaterThanOrEqualTo(2);
        assertThat(byLetter.keySet().stream().filter(k -> !k.contains("/")).count()).as("letters").isGreaterThanOrEqualTo(10);
        VarCalc calc = VarCalc.defaults();
        for (Map.Entry<String, List<String>> e : byLetter.entrySet()) {
            VarBook.Node n = letters.node(e.getKey());
            assertThat(n).as(e.getKey()).isNotNull();
            OrdinalSet.Builder trd = new OrdinalSet.Builder();
            e.getValue().forEach(t -> trd.add(letters.tradeOrdinal(t)));
            OrdinalSet named = trd.build();
            assertThat(n.members()).as(e.getKey() + " members").isEqualTo(named);
            double[] agg = letters.aggregate(e.getKey());
            double[] sum = sums.sum(letters.store(), named);
            for (int j = 0; j < agg.length; j++) {
                assertThat(agg[j]).as(e.getKey() + " " + j).isCloseTo(sum[j], within(0.005));
            }
            assertThat(calc.measures(agg).var()).isCloseTo(calc.measures(sum).var(), within(0.005));
            Double limit = varLimits.get(e.getKey());
            if (limit == null) {
                assertThat(n.limit()).as(e.getKey() + " has no limit row: none, never zero").isNaN();
            } else {
                assertThat(n.limit()).isEqualTo(limit);
            }
        }
        // the letters split the bank: their roots' members are every trade
        assertThat(letters.node("LL~BANK").members()).isEqualTo(letters.all());
        // the MGMT tree beside them is the rows', unchanged by the letters' cells
        for (VarBook.Node x : rows.nodes().values()) {
            assertThat(letters.node(x.id()).members()).isEqualTo(x.members());
            double[] a = rows.aggregate(x.id());
            double[] b = letters.aggregate(x.id());
            for (int j = 0; j < a.length; j++) {
                assertThat(b[j]).isCloseTo(a[j], within(0.005));
            }
        }
    }
}
