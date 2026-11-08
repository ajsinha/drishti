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
package com.ash.drishti.banking.compute.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
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
 * The golden test of the hierarchies (VAR_GUIDE.md section 15.14) on the committed test data of 2026-09-30, read straight from
 * {@code data/testdata/market-risk/} as {@link VarTestDataPrecisionTest} reads it.
 *
 * <p>{@code var/hierarchy-golden-2026-09-30.json} holds the MGMT, LEGAL and LL trees as they were built before the hierarchies came from
 * the promoted columns only (MGMT and LEGAL then from rows of their own, LL from the columns), in USD and in EUR: every node's parent,
 * level, children, members, aggregate (to the bit), VaR, ES, worst scenarios, its children's component VaR (exact doubles) and its limit
 * from {@code var-limit}. The trees built now, all three from the trades' promoted columns as the market-risk pack configures them, are
 * equal to it field by field. LEGAL's node ids are paths since the pack's LEGAL is {@code ids: path} (a desk booking into two legal
 * entities is two nodes): the golden file's LEGAL ids were re-keyed from the leaf ids it was recorded with to their paths, every figure
 * kept as recorded ({@link #legalIdsAreThePathsOfTheLeafIdsTheTreesWereRecordedWith}).
 */
class HierarchyEquivalenceTest {

    /**
     * The re-keying of the golden file: each LEGAL node below the legal entities is its parent's id, a slash and its own value (the leaf
     * id it was recorded with), the root and the legal entities keep theirs; the number of nodes, and every MGMT and LL node, unchanged.
     */
    @Test
    void legalIdsAreThePathsOfTheLeafIdsTheTreesWereRecordedWith() {
        for (String ccy : List.of("USD", "EUR")) {
            JsonNode tree = golden.get(ccy);
            List<String> legal = new ArrayList<>();
            tree.fieldNames().forEachRemaining(id -> {
                if (id.startsWith("LEGAL~")) {
                    legal.add(id);
                }
            });
            assertThat(legal).as(ccy + ": LEGAL's nodes, bank, legal entities, desks and books").hasSize(1 + 4 + 40);
            for (String id : legal) {
                JsonNode n = tree.get(id);
                String parent = n.get("parent").isNull() ? null : n.get("parent").asText();
                String level = n.get("level").asText();
                if (parent == null || level.equals("legal-entity")) {
                    assertThat(id).as(ccy + " " + id).doesNotContain("/");
                } else {
                    assertThat(id).as(ccy + " " + id + " is its parent's path and its value").startsWith(parent + "/")
                            .doesNotContain("~DESK-").matches("LEGAL~LE-[A-Z]+/DESK-[A-Z]+(/BOOK-[A-Z]+-\\d+)?");
                }
            }
        }
    }

    static final ObjectMapper JSON = new ObjectMapper();
    static ParallelSums sums;
    static Path data;
    static PositionRules rules;
    static List<LocalDate> scen;
    static ScenarioFx fx;
    static Map<String, Double> varLimits = new HashMap<>();
    static VarBook columns;
    static JsonNode golden;

    static final HierarchySpec MGMT = new HierarchySpec("MGMT", "Management", List.of("DIVISION", "DESK", "TT_BOOK"),
            List.of("division", "desk", "book"), "BANK", "Drishti Bank", true, null, "var-limit", null);
    static final HierarchySpec LEGAL = new HierarchySpec("LEGAL", "Legal entities", List.of("LEGAL_ENTITY", "DESK", "TT_BOOK"),
            List.of("legal-entity", "desk", "book"), "BANK", "Drishti Bank", false, null, "var-limit", null);   // ids: path
    static final HierarchySpec LL = new HierarchySpec("LL", "Limit letters", List.of("LIMIT_LETTER", "DESK"), null, null, null, false,
            "LL", "var-limit", null);
    /** MGMT and LEGAL as the market-risk pack configures them. */
    static final List<HierarchySpec> MGMT_LEGAL = List.of(MGMT, LEGAL);

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws IOException {
        Path repo = VarTestDataPrecisionTest.root();
        data = repo.resolve("data/testdata/market-risk");
        Map<String, Object> pack = new ObjectMapper(new YAMLFactory()).readValue(repo.resolve("config/packs/banking/market-risk/pack.yaml").toFile(), Map.class);
        rules = PositionRules.of((Map<?, ?>) pack.get("positions"));
        // the pack's own hierarchies are the ones this test builds (the root name is the server's drishti.var.root-name)
        assertThat(HierarchySpec.parse((Map<?, ?>) pack.get("hierarchies")).stream().map(s -> s.withRootName("Drishti Bank")).toList())
                .isEqualTo(List.of(MGMT, LEGAL, LL.withRootName("Drishti Bank")));
        scen = new ArrayList<>();
        VarTestDataPrecisionTest.lines(data.resolve("scenario-set"), n -> n.path("SCENARIO_DATES").forEach(d -> scen.add(LocalDate.parse(d.asText()))));
        fx = VarTestDataPrecisionTest.fx(data, scen.size());
        VarTestDataPrecisionTest.lines(data.resolve("var-limit"), n -> varLimits.put(n.path("NODE_ID").asText(), n.path("LIMIT_VAR").asDouble()));
        sums = new ParallelSums(4, 4096);
        columns = build(List.of(MGMT, LEGAL, LL), Cells.Names.NONE);
        try (InputStream in = HierarchyEquivalenceTest.class.getResourceAsStream("/var/hierarchy-golden-2026-09-30.json")) {
            golden = JSON.readTree(in);
        }
    }

    @AfterAll
    static void close() {
        sums.close();
    }

    /** The date's book: the given hierarchies from the trades' promoted columns, with the var-limit rows. */
    static VarBook build(List<HierarchySpec> specs, Cells.Names names) throws IOException {
        VarBook.Builder b = new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", scen, 65_536, Long.MAX_VALUE);
        b.columnHierarchies(specs, varLimits, names);
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

    /** Every node of {@code b} equal to the golden record's, field by field (doubles exactly), and no node more or fewer. */
    static void sameAsGolden(VarBook b, String currency) {
        JsonNode want = golden.get(currency);
        Map<String, Map<String, Object>> have = HierarchyGolden.describe(b);
        List<String> ids = new ArrayList<>();
        want.fieldNames().forEachRemaining(ids::add);
        assertThat(have.keySet()).as(currency + ": node ids").containsExactlyElementsOf(new TreeSet<>(ids));
        for (String id : ids) {
            JsonNode got = JSON.valueToTree(have.get(id));
            JsonNode exp = want.get(id);
            exp.fieldNames().forEachRemaining(f -> assertThat(got.get(f)).as(currency + " " + id + " " + f).isEqualTo(exp.get(f)));
            assertThat(got.size()).as(currency + " " + id + " fields").isEqualTo(exp.size());
        }
    }

    @Test
    void mgmtLegalAndLimitLettersFromThePromotedColumnsEqualTheGoldenTrees() {
        assertThat(columns.cellColumns()).containsExactly("DIVISION", "DESK", "LEGAL_ENTITY", "LIMIT_LETTER");
        sameAsGolden(columns, "USD");
        // every MGMT and LEGAL node has its var-limit row
        columns.nodes().values().stream().filter(x -> !x.hierarchy().equals("LL"))
                .forEach(x -> assertThat(x.limit()).as("limit of " + x.id()).isPositive());
    }

    @Test
    void theSameInEuro() {
        ScenarioFx.Rates eur = fx.rates("EUR").orElseThrow();
        VarBook e = CurrencyBook.convert(columns, eur, new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", scen, 65_536, Long.MAX_VALUE), sums)
                .book();
        sameAsGolden(e, "EUR");
    }

    @Test
    void nodesAreNamedFromTheirReferenceEntitiesElseTheirValue() throws IOException {
        Map<String, String> desks = Map.of("DESK-RATES", "Rates · Swaps and options");
        VarBook named = build(List.of(MGMT), (level, value) -> level.equals("desk") ? desks.get(value) : null);
        assertThat(named.node("MGMT~DESK-RATES").name()).isEqualTo("Rates · Swaps and options");
        assertThat(named.node("MGMT~DESK-FX").name()).isEqualTo("DESK-FX");          // no entity: its id
        assertThat(named.node("MGMT~BOOK-FX-1").name()).isEqualTo("BOOK-FX-1");
        assertThat(named.node("MGMT~BANK").name()).isEqualTo("Drishti Bank");
    }

    @Test
    void anyNumberOfHierarchiesByYaml() throws IOException {
        Map<?, ?> yaml = new ObjectMapper(new YAMLFactory()).readValue("""
                MGMT: { title: Management, levels: "DIVISION > DESK > TT_BOOK", level-names: "division > desk > book", ids: leaf, limits: var-limit }
                LEGAL: { title: Legal entities, levels: "LEGAL_ENTITY > DESK > TT_BOOK", level-names: "legal-entity > desk > book", ids: path, limits: var-limit }
                LL: { title: Limit letters, levels: "LIMIT_LETTER > DESK", word: LL, limits: var-limit }
                REG: { title: Regions, levels: "LEGAL_ENTITY > DIVISION", word: REG }
                """, Map.class);
        List<HierarchySpec> four = HierarchySpec.parse(yaml);
        assertThat(four).extracting(HierarchySpec::id).containsExactly("MGMT", "LEGAL", "LL", "REG");
        VarBook b = build(four, Cells.Names.NONE);
        for (String root : List.of("MGMT~BANK", "LEGAL~BANK", "LL~BANK", "REG~BANK")) {
            assertThat(b.node(root).members()).as(root).isEqualTo(b.all());
            double[] a = b.aggregate(root);
            double[] m = columns.aggregate("MGMT~BANK");
            for (int j = 0; j < a.length; j++) {
                assertThat(a[j]).as(root + " " + j).isCloseTo(m[j], within(0.005));
            }
        }
        assertThat(b.node("REG~LE-NY/DIV-MARKETS").parent()).isEqualTo("REG~LE-NY");
        // the three of the pack unchanged beside the fourth
        for (String id : List.of("MGMT~DESK-RATES", "LEGAL~LE-NY", "LL~BANK")) {
            assertThat(b.aggregate(id)).as(id).isEqualTo(columns.aggregate(id));
        }
    }

    @Test
    void aLimitLetterIsExactlyTheSumOfItsRowsAndSomeHoldPartOfABook() {
        VarBook letters = columns;
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
    }
}
