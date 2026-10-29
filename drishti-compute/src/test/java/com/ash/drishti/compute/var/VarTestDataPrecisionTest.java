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

import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ParallelSums;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The float32 hot copy on the committed test data (VAR_GUIDE.md sections 26 and 22: 9,572 live trades of 2026-09-30 in 7,984 positions,
 * 8,233 sent as P&amp;L vectors and 1,339 as scenario PVs in their own currency, converted to USD here as the server's intake does
 * (ScenarioFx.pnlFromPv with the supplied scenario FX); the
 * market-risk pack's position rules, the 88 hierarchy nodes), read straight from {@code data/testdata/market-risk/}: the same date built
 * on the heap (float64, as the lake holds it) and through its hot file. Every node's VaR and ES are the same to the cent (float64
 * aggregates); every position's, every book's minus a trade, every counterparty's and a set of baskets are within the stated
 * tolerance: <b>1 USD and 1e-7 of the VaR</b>, under the proven bound 2^-24 x the date's gross P&amp;L of the worst scenario. The
 * largest differences found are printed (they are quoted in VAR_GUIDE.md section 26).
 */
class VarTestDataPrecisionTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static ParallelSums sums;
    static VarBook heap;
    static VarBook mapped;
    @TempDir
    static Path dir;

    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("data/testdata/market-risk/pnl-vector"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("the committed test data (data/testdata/market-risk) is not above " + Path.of("").toAbsolutePath());
        }
        return p;
    }

    static void lines(Path folder, Consumer<JsonNode> each) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(folder)) {
            files = s.filter(f -> f.getFileName().toString().endsWith(".jsonl.gz")).sorted().toList();
        }
        for (Path f : files) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(f)), StandardCharsets.UTF_8))) {
                for (String line = r.readLine(); line != null; line = r.readLine()) {
                    if (!line.isBlank()) {
                        each.accept(JSON.readTree(line));
                    }
                }
            }
        }
    }

    static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() || v.asText().isEmpty() ? null : v.asText();
    }

    /** The supplied scenario FX of the test data (fx-scenario), every currency against USD. */
    static ScenarioFx fx(Path data, int width) throws IOException {
        List<ScenarioFx.Rates> rates = new ArrayList<>();
        lines(data.resolve("fx-scenario"), n -> {
            double[] r = new double[n.path("RATES").size()];
            for (int j = 0; j < r.length; j++) {
                r[j] = n.path("RATES").get(j).asDouble();
            }
            rates.add(new ScenarioFx.Rates(text(n, "CURRENCY"), n.path("RATE").asDouble(), r, "supplied"));
        });
        return new ScenarioFx(width, rates);
    }

    @SuppressWarnings("unchecked")
    static void fill(VarBook.Builder b, Path data, PositionRules rules, double scale) throws IOException {
        ScenarioFx fx = fx(data, b.width());
        b.currencyRates(c -> fx.rates(c).orElse(null));                // the per-trade-currency totals (VAR_GUIDE.md section 15.11)
        lines(data.resolve("scenario-pv"), n -> {
            if (!rules.live(text(n, "TRADE_STATUS"))) {
                return;
            }
            JsonNode pvs = n.get("SCENARIO_PV");
            double[] p = new double[pvs.size()];
            for (int j = 0; j < p.length; j++) {
                p[j] = pvs.get(j).asDouble();
            }
            ScenarioFx.Rates r = fx.rates(text(n, "CURRENCY")).orElseThrow();
            double[] v = new double[p.length];
            ScenarioFx.pnlFromPv(n.path("BASE_PV").asDouble(), p, p.length, r, v);
            for (int j = 0; j < v.length; j++) {
                v[j] *= scale;
            }
            add(b, n, rules, v, n.path("BASE_PV").asDouble() * r.base(), text(n, "CURRENCY"));
        });
        lines(data.resolve("pnl-vector"), n -> {
            if (!rules.live(text(n, "TRADE_STATUS"))) {
                return;
            }
            JsonNode pnl = n.get("PNL");
            double[] v = new double[pnl.size()];
            for (int j = 0; j < v.length; j++) {
                v[j] = pnl.get(j).asDouble() * scale;
            }
            add(b, n, rules, v, n.path("PV").asDouble(Double.NaN), null);
        });
        lines(data.resolve("risk-hierarchy"), n -> {
            List<String> books = new ArrayList<>();
            n.path("BOOKS").forEach(x -> books.add(x.asText()));
            b.node(text(n, "NODE_ID"), text(n, "HIERARCHY"), text(n, "LEVEL"), text(n, "NAME"), text(n, "PARENT_ID"), books,
                    Double.NaN);                                         // the limits are var-limit's (2.9.6): this test checks the figures
        });
    }

    private static void add(VarBook.Builder b, JsonNode n, PositionRules rules, double[] v, double pvUsd, String pvCurrency) {
        String[] texts = new String[VarBook.TRADE_TEXTS.size()];
        for (int c = 0; c < texts.length; c++) {
            texts[c] = text(n, VarBook.TRADE_TEXTS.get(c));
        }
        String trade = text(n, "TRADE_ID");
        PositionRules.Derived d = rules.derive(trade, text(n, "TT_BOOK"), text(n, "PRODUCT_GROUP"), text(n, "PRODUCT_TYPE"), k -> text(n, k));
        texts[VarBook.TRADE_TEXTS.indexOf("POSITION_ID")] = d.positionId();
        texts[VarBook.TRADE_TEXTS.indexOf("POSITION_RULE")] = d.rule();
        texts[VarBook.TRADE_TEXTS.indexOf("INSTRUMENT_ID")] = d.instrumentId();
        texts[VarBook.TRADE_TEXTS.indexOf("SECURITY_ID")] = d.securityId();
        texts[VarBook.TRADE_TEXTS.indexOf(CurrencyTotals.COLUMN)] = pvCurrency;
        double[] nums = {n.path("QUANTITY").asDouble(Double.NaN), pvUsd};
        b.trade(trade, v, v.length, texts, nums);
    }

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws IOException {
        Path repo = root();
        Path data = repo.resolve("data/testdata/market-risk");
        Map<String, Object> pack = new ObjectMapper(new YAMLFactory()).readValue(repo.resolve("config/packs/market-risk/pack.yaml").toFile(), Map.class);
        PositionRules rules = PositionRules.of((Map<?, ?>) pack.get("positions"));
        List<LocalDate> scen = new ArrayList<>();
        lines(data.resolve("scenario-set"), n -> n.path("SCENARIO_DATES").forEach(d -> scen.add(LocalDate.parse(d.asText()))));
        sums = new ParallelSums(4, 4096);
        VarBook.Builder h = new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", scen, 65_536, Long.MAX_VALUE);
        fill(h, data, rules, 1);
        heap = h.build(sums);
        VarBook.Builder m = new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", scen, Long.MAX_VALUE,
                SyntheticBank.target(dir, "2026-09-30-HS-509.vhot", "test-data"));
        fill(m, data, rules, 1);
        mapped = m.build(sums);
        DATA = data;
        RULES = rules;
        SCEN = scen;
    }

    static Path DATA;
    static PositionRules RULES;
    static List<LocalDate> SCEN;

    @AfterAll
    static void close() {
        mapped.close();
        sums.close();
    }

    @Test
    void theHotCopyOfTheTestDataIsWithinTheStatedTolerance() {
        assertThat(heap.tradeCount()).isEqualTo(9572);
        assertThat(heap.positionCount()).isEqualTo(7984);
        assertThat(heap.nodes()).hasSize(88);
        VarCalc calc = VarCalc.defaults();
        double bound = VarHotFileTest.bound(heap);
        for (VarBook.Node n : heap.nodes().values()) {
            VarCalc.Measures a = calc.measures(heap.vector(n.members(), sums));
            VarCalc.Measures b = calc.measures(mapped.vector(n.members(), sums));
            assertThat(b.var()).as(n.id()).isEqualTo(a.var());
            assertThat(b.es()).as(n.id()).isEqualTo(a.es());
        }
        ScopeResolver hr = new ScopeResolver(heap, VarBookTest.defs());
        List<String> scopes = new ArrayList<>(List.of("DESK:DESK-RATES - TRD:" + heap.tradeId(heap.node("MGMT~DESK-RATES").members().first()),
                "LE:LE-NY", "CPTY:CP-SUMMIT", "WHERE:PRODUCT_GROUP == 'Rates' and CURRENCY in ('USD', 'CAD')",
                "WHERE:'option' in TAGS or DIRECTION == 'SHORT'", "BANK - DESK:DESK-CREDIT"));
        for (String book : List.of("BOOK-COMM-1", "BOOK-EQD-2", "BOOK-FI-3", "BOOK-FX-1", "BOOK-RATES-2", "BOOK-STRUCT-1")) {
            int[] r = heap.bookRange(book);
            scopes.add("BOOK:" + book + " - TRD:" + heap.tradeId(r[0]) + "," + heap.tradeId(r[1] - 1));
        }
        double worstAbs = 0;
        double worstRel = 0;
        int checked = 0;
        for (String s : scopes) {
            OrdinalSet set = hr.resolve(s).set();
            double[] a = heap.vector(set, sums);
            double[] b = mapped.vector(set, sums);
            for (int j = 0; j < a.length; j++) {
                assertThat(Math.abs(a[j] - b[j])).as(s + " scenario " + j).isLessThanOrEqualTo(bound);
            }
            double va = calc.measures(a).var();
            double vb = calc.measures(b).var();
            worstAbs = Math.max(worstAbs, Math.abs(va - vb));
            worstRel = Math.max(worstRel, Math.abs(va - vb) / Math.max(1, Math.abs(va)));
            checked++;
        }
        for (int p = 0; p < heap.positionCount(); p++) {
            double va = calc.measures(heap.positionVector(p)).var();
            double vb = calc.measures(mapped.positionVector(p)).var();
            worstAbs = Math.max(worstAbs, Math.abs(va - vb));
            worstRel = Math.max(worstRel, Math.abs(va - vb) / Math.max(1, Math.abs(va)));
            checked++;
        }
        System.out.printf("test data, float32 hot copy against float64: %d scopes and positions, largest VaR difference %.4f USD (%.2e of the "
                + "VaR); proven bound %.1f USD; hot file %,d bytes against %,d bytes of float64 vectors%n", checked, worstAbs, worstRel, bound,
                mapped.vectorBytes(), heap.vectorBytes());
        assertThat(worstAbs).isLessThanOrEqualTo(1.0);
        assertThat(worstRel).isLessThanOrEqualTo(1e-7);
    }

    /**
     * The test data's vectors are whole dollars and every position's P&amp;L is below 2^24, so float32 holds them exactly. The same
     * trades scaled by 1,000.37 (positions of billions, with cents) do round: every scope stays within the bound, and VaR within 1e-6 of
     * itself.
     */
    /**
     * The bank from both forms of input (VAR_GUIDE.md section 15.9), and in each currency the batch supplies FX for (section 15.10): the
     * figures the guides quote. The USD figures differ from the 2.8.0 data's (112,035,906 and 160,132,107) only by the FX translation of
     * the 250 Endur trades in EUR and CAD that came as scenario PVs; their USD trades reproduce their vectors exactly.
     */
    @Test
    void theBankFromScenarioPvsAndVectorsInEveryReportingCurrency() throws IOException {
        VarCalc calc = new VarCalc(0.99, 0.975, VarMath.RankRule.KTH_WORST, 5, true);
        VarCalc.Measures usd = calc.measures(heap.aggregate("MGMT~BANK"));
        assertThat(Math.round(usd.var())).isEqualTo(112_041_646L);
        assertThat(Math.round(usd.es())).isEqualTo(160_143_809L);
        ScenarioFx fx = fx(DATA, heap.width());
        StringBuilder say = new StringBuilder("bank VaR / ES: USD " + Math.round(usd.var()) + " / " + Math.round(usd.es()));
        for (String c : List.of("EUR", "GBP", "JPY", "CAD", "CHF", "AUD")) {
            CurrencyBook.Converted conv = CurrencyBook.convert(heap, fx.rates(c).orElseThrow(), new VarBook.Builder(heap.businessDate(),
                    heap.scenarioSet(), heap.scenarioDates(), 65_536, Long.MAX_VALUE), sums);
            VarCalc.Measures m = calc.measures(conv.book().aggregate("MGMT~BANK"));
            // every node from its per-trade-currency totals equals the book converted trade by trade, to the cent (section 15.11)
            for (String node : heap.nodes().keySet()) {
                double[] a = heap.currencyTotals().get(node).in(fx, c);
                double[] e = conv.book().aggregate(node);
                for (int j = 0; j < a.length; j++) {
                    assertThat(a[j]).as(node + " " + c + " " + j).isCloseTo(e[j], org.assertj.core.api.Assertions.within(0.005));
                }
                assertThat(mapped.currencyTotals().get(node).in(fx, c)[0]).as(node).isCloseTo(a[0], org.assertj.core.api.Assertions.within(0.005));
            }
            assertThat(Math.round(calc.measures(heap.currencyTotals().get("MGMT~BANK").in(fx, c)).var())).isEqualTo(Math.round(m.var()));
            say.append("; ").append(c).append(' ').append(Math.round(m.var())).append(" / ").append(Math.round(m.es()));
            assertThat(conv.book().tradeCount()).isEqualTo(heap.tradeCount());
            assertThat(m.var()).isPositive();
        }
        System.out.println(say);
    }

    @Test
    void scaledToABankOfBillionsTheRoundingStaysWithinTheBound(@TempDir Path own) throws IOException {
        double scale = 1000.37;
        VarBook.Builder h = new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", SCEN, 65_536, Long.MAX_VALUE);
        fill(h, DATA, RULES, scale);
        VarBook big = h.build(sums);
        VarBook.Builder m = new VarBook.Builder(LocalDate.of(2026, 9, 30), "HS-509", SCEN, Long.MAX_VALUE,
                SyntheticBank.target(own, "2026-09-30-HS-509.vhot", "scaled"));
        fill(m, DATA, RULES, scale);
        VarBook bigMapped = m.build(sums);
        try {
            VarCalc calc = VarCalc.defaults();
            double bound = VarHotFileTest.bound(big);
            ScopeResolver hr = new ScopeResolver(big, VarBookTest.defs());
            double worstAbs = 0;
            double worstRel = 0;
            for (String s : List.of("BANK - DESK:DESK-CREDIT", "CPTY:CP-SUMMIT", "WHERE:PRODUCT_GROUP == 'Rates' and CURRENCY in ('USD', 'CAD')",
                    "LE:LE-NY - BOOK:BOOK-FX-1", "WHERE:DIRECTION == 'SHORT'")) {
                OrdinalSet set = hr.resolve(s).set();
                double va = calc.measures(big.vector(set, sums)).var();
                double vb = calc.measures(bigMapped.vector(set, sums)).var();
                assertThat(Math.abs(va - vb)).as(s).isLessThanOrEqualTo(bound);
                worstAbs = Math.max(worstAbs, Math.abs(va - vb));
                worstRel = Math.max(worstRel, Math.abs(va - vb) / Math.abs(va));
            }
            for (int p = 0; p < big.positionCount(); p++) {
                double va = calc.measures(big.positionVector(p)).var();
                double vb = calc.measures(bigMapped.positionVector(p)).var();
                worstAbs = Math.max(worstAbs, Math.abs(va - vb));
                worstRel = Math.max(worstRel, Math.abs(va - vb) / Math.max(1, Math.abs(va)));
            }
            System.out.printf("test data x %.2f (bank VaR %,.0f): largest VaR difference %.2f USD (%.2e of the VaR); proven bound %,.0f USD%n",
                    scale, calc.measures(big.aggregate("MGMT~BANK")).var(), worstAbs, worstRel, bound);
            assertThat(worstRel).isLessThanOrEqualTo(1e-6);
        } finally {
            bigMapped.close();
        }
    }
}
