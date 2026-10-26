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
package com.ash.drishti.compute.ccr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.compute.vector.OrdinalSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Java against the Python reference (tools/packgen/ccr/ccrmath.py) on the committed test data: the eight netting sets of
 * {@code data/testdata/counterparty-risk/exposure-path/} built into a date on the heap (float64, as the lake holds them) and through its
 * hot file (float32), and every set's figures and profile, and CP-ALDERSHOT's (its four sets summed per path), compared with
 * {@code src/test/resources/ccr/reference.json} (written by make_ccr_data.py). On the heap they agree to 1e-6 USD; from the hot file
 * within float32's rounding of the set's values (2^-24 of the largest value on any path, a few USD here). Then: a counterparty's EE is
 * the sum of its sets' EE (additive) while its PFE is not the sum of their PFEs; contributions by netting set and by trade add up.
 */
class ExposureParityTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static JsonNode ref;
    static ExposureBook heap;
    static ExposureBook mapped;
    static Map<String, double[]> tradePaths = new HashMap<>();
    @TempDir
    static Path dir;

    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("data/testdata/counterparty-risk/exposure-path"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("the committed test data (data/testdata/counterparty-risk) is not above " + Path.of("").toAbsolutePath());
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

    static CsaTerms terms(JsonNode c) {
        if (c == null || c.isNull()) {
            return CsaTerms.NONE;
        }
        return new CsaTerms("CSA", c.get("th_them").asDouble(), c.get("th_us").asDouble(), c.get("mta_them").asDouble(), c.get("mta_us").asDouble(),
                c.get("ia").asDouble(), c.get("rounding").asDouble(), c.get("mpor_days").asInt(), 1, c.get("margined").asBoolean(),
                c.get("held_today").asDouble(), c.get("im_held").asDouble());
    }

    static ExposureBook build(Path hot) throws IOException {
        Path data = root().resolve("data/testdata/counterparty-risk");
        JsonNode[] run = new JsonNode[1];
        lines(data.resolve("simulation-run"), n -> run[0] = n);
        List<LocalDate> dates = new ArrayList<>();
        run[0].get("GRID_DATES").forEach(x -> dates.add(LocalDate.parse(x.asText())));
        List<String> roles = new ArrayList<>();
        run[0].get("GRID_ROLE").forEach(x -> roles.add(x.asText()));
        List<Integer> lb = new ArrayList<>();
        run[0].get("LOOKBACK_OF").forEach(x -> lb.add(x.asInt()));
        List<String> tenors = new ArrayList<>();
        run[0].get("GRID_TENORS").forEach(x -> tenors.add(x.isNull() ? null : x.asText()));
        ExposureGrid g = ExposureGrid.of(LocalDate.parse(run[0].get("BUSINESS_DATE").asText()), dates, roles, lb, tenors);
        int p = run[0].get("PATHS").asInt();
        ExposureBook.Builder b = new ExposureBook.Builder(g, p, run[0].get("RUN_ID").asText(), run[0].get("BATCH_ID").asText(), Map.of(),
                ExposureMath.Model.LAGGED, 0);
        double[] values = new double[p];
        lines(data.resolve("exposure-path"), n -> {
            JsonNode m = n.get("MTM");
            for (int j = 0; j < p; j++) {
                values[j] = m.get(j).asDouble();
            }
            String t = n.get("TRADE_ID").asText();
            int k = n.get("GRID_INDEX").asInt();
            b.row(t, n.get("NETTING_SET").asText(), new String[] {n.get("COUNTERPARTY").asText(), n.get("LEGAL_ENTITY").asText(),
                n.path("DESK").asText(null), n.path("TT_BOOK").asText(null), n.path("ASSET_CLASS").asText(null), n.path("PRODUCT").asText(null),
                n.path("SOURCE_SYSTEM").asText(null)}, n.path("MATURITY_DATE").asText(null), n.path("NOTIONAL").asDouble(), k, values, p);
            if (hot == null) {
                double[] tp = tradePaths.computeIfAbsent(t, x -> new double[g.size() * p]);
                System.arraycopy(values, 0, tp, k * p, p);
            }
        });
        ExposureBook.Reference r = new ExposureBook.Reference() {
            @Override
            public CsaTerms terms(String set) {
                return ExposureParityTest.terms(ref.at("/sets/" + set + "/csa"));
            }

            @Override
            public double mtm0(String trade) {
                return ref.at("/mtm0/" + trade).asDouble();
            }
        };
        return b.build(r, hot, "test", Map.of());
    }

    @BeforeAll
    static void load() throws IOException {
        ref = JSON.readTree(ExposureParityTest.class.getResourceAsStream("/ccr/reference.json"));
        heap = build(null);
        mapped = build(dir.resolve("2026-09-30" + ExposureHotFile.SUFFIX));
    }

    static ExposureCalc.Params params() {
        return new ExposureCalc.Params(0.95, ExposureMath.Model.LAGGED, ExposureMath.Rule.KTH, new double[] {0.95, 0.99}, 21, 0);
    }

    static ExposureCalc.Result scope(ExposureBook b, String scope) {
        ExposureScopes.Resolution r = new ExposureScopes(b, g -> java.util.Optional.empty()).resolve(scope);
        return ExposureCalc.evaluate(b, r.trades(), r.standalone(), params(), ids -> {
            Map<String, double[]> out = new HashMap<>();
            ids.forEach(i -> out.put(i, tradePaths.get(i)));
            return out;
        });
    }

    static void compare(ExposureBook b, String scope, JsonNode want, double tol) {
        ExposureMath.Stats s = scope(b, scope).stats;
        int q95 = s.at(0.95);
        int q99 = s.at(0.99);
        double scale = Math.max(1, want.get("PEAK_PFE_99").asDouble());
        for (JsonNode row : want.get("PROFILE")) {
            scale = Math.max(scale, row.get("EE_UNCOLL").asDouble());
        }
        double bound = 2 * tol * scale;                    // float32 keeps 24 bits: half a unit in the last place of the largest value
        assertThat(s.ce).as(scope + " CE").isCloseTo(want.get("CE").asDouble(), within(Math.max(1e-6, tol)));
        assertThat(s.epe).as(scope + " EPE").isCloseTo(want.get("EPE").asDouble(), within(bound + 1e-6));
        assertThat(s.eepe).as(scope + " EEPE").isCloseTo(want.get("EEPE").asDouble(), within(bound + 1e-6));
        assertThat(s.peakPfe[q95]).as(scope + " peak PFE 95").isCloseTo(want.get("PEAK_PFE_95").asDouble(), within(bound + 1e-6));
        assertThat(s.peakPfe[q99]).as(scope + " peak PFE 99").isCloseTo(want.get("PEAK_PFE_99").asDouble(), within(bound + 1e-6));
        JsonNode prof = want.get("PROFILE");
        for (int i = 0; i < prof.size(); i++) {
            JsonNode row = prof.get(i);
            assertThat(s.ee[i]).as(scope + " EE " + row.get("TENOR").asText()).isCloseTo(row.get("EE").asDouble(), within(bound + 1e-5));
            assertThat(s.ene[i]).as(scope + " ENE " + row.get("TENOR").asText()).isCloseTo(row.get("ENE").asDouble(), within(bound + 1e-5));
            assertThat(s.eee[i]).isCloseTo(row.get("EEE").asDouble(), within(bound + 1e-5));
            assertThat(s.pfe[q95][i]).isCloseTo(row.get("PFE_95").asDouble(), within(bound + 1e-5));
            assertThat(s.pfe[q99][i]).isCloseTo(row.get("PFE_99").asDouble(), within(bound + 1e-5));
            assertThat(s.eeUncoll[i]).isCloseTo(row.get("EE_UNCOLL").asDouble(), within(bound + 1e-5));
        }
        if (b == heap) {
            assertThat(b.grid().date(b.grid().primaryAt(s.peakAt[q95])).toString()).isEqualTo(want.get("PEAK_PFE_95_DATE").asText());
        }
    }

    @Test
    void everyNettingSetAndTheCounterpartyAgreeWithThePythonReference() {
        assertThat(heap.sets()).hasSize(8);
        assertThat(heap.trades()).hasSize(ref.at("/mtm0").size());
        ref.get("sets").fields().forEachRemaining(e -> {
            compare(heap, "NSET:" + e.getKey(), e.getValue(), 0);
            compare(mapped, "NSET:" + e.getKey(), e.getValue(), Math.pow(2, -23));
        });
        compare(heap, "CPTY:CP-ALDERSHOT", ref.at("/scopes/CPTY:CP-ALDERSHOT"), 0);
        compare(mapped, "CPTY:ALDERSHOT", ref.at("/scopes/CPTY:CP-ALDERSHOT"), Math.pow(2, -23));
        assertThat(mapped.mapped()).isTrue();
        assertThat(mapped.hot().header().sets()).isEqualTo(8);
    }

    @Test
    void theHotFileReopensWithTheSameAnswers() throws IOException {
        Path f = mapped.hot().file();
        ExposureHotFile.verify(f);
        try (ExposureBook again = ExposureBook.open(f)) {
            ExposureMath.Stats a = scope(mapped, "CPTY:CP-ALDERSHOT").stats;
            ExposureMath.Stats b = scope(again, "CPTY:CP-ALDERSHOT").stats;
            assertThat(b.ee).containsExactly(a.ee);
            assertThat(b.peakPfe).containsExactly(a.peakPfe);
            assertThat(again.trades()).hasSize(mapped.trades().size());
            assertThat(again.set(0).csa()).isEqualTo(mapped.set(0).csa());
        }
    }

    @Test
    void eeAddsUpAcrossNettingSetsAndPfeDoesNot() {
        ExposureCalc.Result cp = scope(heap, "CPTY:CP-ALDERSHOT");
        double[] sumEe = new double[cp.stats.ee.length];
        double sumPeak = 0;
        for (String ns : List.of("NS-ALDERSHOT-FRA", "NS-ALDERSHOT-LDN", "NS-ALDERSHOT-NY", "NS-ALDERSHOT-TKY")) {
            ExposureMath.Stats s = scope(heap, "NSET:" + ns).stats;
            for (int i = 0; i < sumEe.length; i++) {
                sumEe[i] += s.ee[i];
            }
            sumPeak += s.peakPfe[s.at(0.95)];
        }
        for (int i = 0; i < sumEe.length; i++) {
            assertThat(cp.stats.ee[i]).isCloseTo(sumEe[i], within(1e-6));
        }
        assertThat(cp.stats.peakPfe[cp.stats.at(0.95)]).isLessThan(sumPeak);
        // the union written as netting sets is the same scope
        ExposureCalc.Result union = scope(heap, "NSET:NS-ALDERSHOT-FRA,NS-ALDERSHOT-LDN + NSET:NS-ALDERSHOT-NY + NSET:ALDERSHOT-TKY");
        assertThat(union.stats.peakPfe).containsExactly(cp.stats.peakPfe);
    }

    @Test
    void contributionsAddUp() {
        ExposureCalc.Result cp = scope(heap, "CPTY:CP-ALDERSHOT");
        ExposureBook.TradePaths tp = ids -> {
            Map<String, double[]> out = new HashMap<>();
            ids.forEach(i -> out.put(i, tradePaths.get(i)));
            return out;
        };
        for (String figure : List.of("EE", "EPE", "PFE")) {
            double total = figure.equals("EE") ? cp.stats.peakEe : figure.equals("EPE") ? cp.stats.epe : cp.stats.peakPfe[cp.stats.at(0.95)];
            double bySet = ExposureCalc.contributions(cp, ExposureCalc.parts(cp, "netting-set", tp), figure, -1).stream()
                    .mapToDouble(ExposureCalc.Contribution::value).sum();
            assertThat(bySet).as(figure + " by netting set").isCloseTo(total, within(1e-6 * Math.max(1, total)));
            double byTrade = ExposureCalc.contributions(cp, ExposureCalc.parts(cp, "trade", tp), figure, -1).stream()
                    .mapToDouble(ExposureCalc.Contribution::value).sum();
            assertThat(byTrade).as(figure + " by trade").isCloseTo(total, within(1e-6 * Math.max(1, total)));
        }
        List<ExposureCalc.Contribution> pfe = ExposureCalc.contributions(cp, ExposureCalc.parts(cp, "netting-set", tp), "PFE", -1);
        assertThat(pfe).allSatisfy(c -> assertThat(c.incremental()).isNotNull());
        assertThat(pfe.stream().mapToDouble(ExposureCalc.Contribution::pct).sum()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void aDeskCutsNettingSetsAndATradeStandsAlone() {
        String desk = heap.trade(heap.set(heap.setOrdinal("NS-ALDERSHOT-NY")).from()).desk();
        ExposureCalc.Result d = scope(heap, "DESK:" + desk + " & CPTY:CP-ALDERSHOT");
        assertThat(d.flags).contains("partial");
        assertThat(d.partialSets()).isPositive();
        String trade = heap.trade(0).id();
        ExposureCalc.Result t = scope(heap, "TRD:" + trade.toLowerCase(java.util.Locale.ROOT));
        assertThat(t.flags).containsExactly("standalone");
        assertThat(t.trades.size()).isEqualTo(1);
        assertThat(t.stats.ce).isEqualTo(Math.max(0, heap.trade(0).mtm0()));                 // alone and uncollateralised
        assertThat(scope(heap, "BANK").trades.size()).isEqualTo(heap.trades().size());
        assertThat(new ExposureScopes(heap, g -> java.util.Optional.of(List.of("CP-ALDERSHOT"))).resolve("GROUP:ALDERSHOT").trades())
                .isEqualTo(scope(heap, "CPTY:CP-ALDERSHOT").trades);
        assertThat(new ExposureScopes(heap, null).resolve("LE:FRA").trades().size()).isPositive();
        assertThat(new ExposureScopes(heap, null).resolve("WHERE:ASSET_CLASS == 'Securities financing'").trades().size()).isPositive();
        assertThat(new ExposureScopes(heap, null).resolve("NSET:NS-NOPE").unknown()).containsExactly("NS-NOPE");
        OrdinalSet none = new ExposureScopes(heap, null).resolve("CPTY:CP-NOPE").trades();
        assertThat(none.isEmpty()).isTrue();
    }
}
