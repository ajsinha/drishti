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
package com.ash.drishti.plugin.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.JsonCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every live sample in the shipped packs, ticked many times: the detail moves with the headline and the numbers still add
 * up (a curve's discount factors against its zero rates, a trade's MTM against its legs, an LCR against its components).
 * Also flags a walked headline whose declared dependents never move, and a walk spec that names a path the sample lacks.
 */
class DemoPackWalksTest {

    /** The shipped packs and the retired example packs (examples/packs), whose walks the demo plugin still serves. */
    private static final List<Path> PACKS = List.of(Path.of("../../config/packs"), Path.of("../../examples/packs"));
    private static final int TICKS = 40;
    /** Dependents that change only when something is crossed: a day's high or low when the price breaks it, a curve's shape when its first two months swap. */
    private static final Set<String> CONDITIONAL = Set.of("ohlc.-1.high", "ohlc.-1.low", "shape");
    private static final Instant NOON = Instant.parse("2026-10-01T14:20:00Z");

    private static final JsonCodec JSON = new JsonCodec();
    /** kind -> id -> the sample as read. */
    private static final Map<String, Map<String, DataNode>> SAMPLES = new TreeMap<>();

    @BeforeAll
    static void load() throws IOException {
        for (Path root : PACKS) {
          try (Stream<Path> packs = Files.list(root)) {
            for (Path pack : packs.toList()) {
                Path samples = pack.resolve("samples");
                if (!Files.isDirectory(samples)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(samples)) {
                    for (Path f : files.filter(x -> x.toString().endsWith(".json") && !x.getFileName().toString().equals("catalog.json")).toList()) {
                        DataNode doc = JSON.read(Files.readString(f));
                        if (doc.get("_meta").get("walk") instanceof DataNode.Obj w && w.size() > 0) {
                            SAMPLES.computeIfAbsent(f.getParent().getFileName().toString(), k -> new TreeMap<>()).put(f.getFileName().toString(), doc);
                        }
                    }
                }
            }
          }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private static Map<String, Object> raw(DataNode doc) {
        return (Map<String, Object>) doc.unwrap();
    }

    private static Object at(Map<String, Object> doc, String path) {
        DemoPaths.Slot s = DemoPaths.first(doc, path);
        assertThat(s).as("path %s", path).isNotNull();
        return s.get();
    }

    private static double num(Map<String, Object> doc, String path) {
        return ((Number) at(doc, path)).doubleValue();
    }

    private static String text(Map<String, Object> doc, String path) {
        return String.valueOf(at(doc, path));
    }

    private static List<Double> nums(Map<String, Object> doc, String path) {
        return DemoPaths.find(doc, path).stream().map(s -> ((Number) s.get()).doubleValue()).toList();
    }

    /** Every state of the sample being checked, one per tick (so "it moved" means it moved at some tick, not that the last tick happens to differ). */
    private static List<Map<String, Object>> states = new ArrayList<>();

    private static DataNode ticked(String kind, String id, DataNode doc, int ticks) {
        DemoTicker ticker = new DemoTicker((kind + id).hashCode(), Clock.fixed(NOON, ZoneOffset.UTC));
        DataNode walk = doc.get("_meta").get("walk");
        DataNode now = doc;
        states = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            now = ticker.tick(kind, now, walk);
            states.add(raw(now));
        }
        return now;
    }

    private static boolean moved(Map<String, Object> before, String path) {
        List<Object> was = values(before, path);
        return states.stream().anyMatch(s -> !values(s, path).equals(was));
    }

    private static void eachSample(String kind, BiConsumer<Map<String, Object>, Map<String, Object>> check) {
        Map<String, DataNode> docs = SAMPLES.get(kind);
        assertThat(docs).as("walked samples of kind %s", kind).isNotNull().isNotEmpty();
        docs.forEach((id, doc) -> {
            Map<String, Object> before = raw(doc);
            Map<String, Object> after = raw(ticked(kind, id, doc, TICKS));
            try {
                check.accept(before, after);
            } catch (AssertionError e) {
                throw new AssertionError(kind + "/" + id + ": " + e.getMessage(), e);
            }
        });
    }

    private static void closeTo(double actual, double expected, double tolerance, String what) {
        assertThat(actual).as(what).isCloseTo(expected, within(tolerance));
    }

    // ---- the spec itself --------------------------------------------------------------------------------------
    @Test
    void everyExpressionCompilesAndEveryPathExistsInItsSample() {
        List<String> problems = new ArrayList<>();
        SAMPLES.forEach((kind, docs) -> docs.forEach((id, doc) -> {
            Map<String, Object> m = raw(doc);
            DataNode.Obj walk = (DataNode.Obj) doc.get("_meta").get("walk");
            walk.fields().forEach((field, spec) -> {
                if ("derive".equals(field)) {
                    for (DataNode d : ((DataNode.Arr) spec).elements()) {
                        exists(problems, kind, id, m, d.get("set").asText());
                        try {
                            DemoExpr.check(d.get("expr") instanceof DataNode.Val ? d.get("expr").asText() : d.get("scaleTo").asText());
                        } catch (IllegalArgumentException e) {
                            problems.add(kind + "/" + id + ": " + e.getMessage());
                        }
                    }
                } else if ("append".equals(field)) {
                    for (DataNode a : ((DataNode.Arr) spec).elements()) {
                        exists(problems, kind, id, m, a.get("to").asText());
                        ((DataNode.Obj) a.get("row")).fields().values().forEach(e -> DemoExpr.check(e.asText()));
                    }
                } else {
                    exists(problems, kind, id, m, field);
                    if (spec.get("with") instanceof DataNode.Arr with) {
                        with.elements().forEach(w -> exists(problems, kind, id, m, w instanceof DataNode.Obj o ? o.get("path").asText() : w.asText()));
                    }
                }
            });
        }));
        assertThat(problems).as("walk specs that name something the sample does not have, or do not parse").isEmpty();
    }

    private static void exists(List<String> problems, String kind, String id, Map<String, Object> doc, String path) {
        if (DemoPaths.find(doc, path).isEmpty()) {
            problems.add(kind + "/" + id + ": " + path);
        }
    }

    @Test
    void everyDeclaredDependentOfAWalkedHeadlineMovesInSomeSampleOfItsKind() {
        List<String> stuck = new ArrayList<>();
        SAMPLES.forEach((kind, docs) -> {
            Map<String, Boolean> moved = new LinkedHashMap<>();
            docs.forEach((id, doc) -> {
                Map<String, Object> before = raw(doc);
                Map<String, Object> after = raw(ticked(kind, id, doc, TICKS));
                for (String dep : dependents(doc.get("_meta").get("walk"))) {
                    if (CONDITIONAL.contains(dep)) {
                        continue;
                    }
                    boolean changed = !values(before, dep).equals(values(after, dep));
                    moved.merge(dep, changed, Boolean::logicalOr);
                }
            });
            moved.forEach((dep, did) -> {
                if (!did) {
                    stuck.add(kind + ": " + dep);
                }
            });
        });
        assertThat(stuck).as("declared dependents that never move").isEmpty();
    }

    @Test
    void aWalkedKindWithNumbersInItsListsDeclaresDependents() {
        List<String> alone = new ArrayList<>();
        SAMPLES.forEach((kind, docs) -> {
            DataNode doc = docs.values().iterator().next();
            if (dependents(doc.get("_meta").get("walk")).isEmpty() && hasNumericList(raw(doc))) {
                alone.add(kind);
            }
        });
        assertThat(alone).as("headline walked on its own while a list of numbers beside it stays still").isEmpty();
    }

    private static boolean hasNumericList(Map<String, Object> doc) {
        for (Map.Entry<String, Object> e : doc.entrySet()) {
            if (!"_meta".equals(e.getKey()) && e.getValue() instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> row
                    && row.values().stream().anyMatch(v -> v instanceof Double)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> dependents(DataNode walk) {
        Set<String> out = new LinkedHashSet<>();
        ((DataNode.Obj) walk).fields().forEach((field, spec) -> {
            if ("derive".equals(field)) {
                ((DataNode.Arr) spec).elements().forEach(d -> out.add(d.get("set").asText()));
            } else if ("append".equals(field)) {
                ((DataNode.Arr) spec).elements().forEach(a -> out.add(a.get("to").asText()));
            } else if (spec.get("with") instanceof DataNode.Arr with) {
                with.elements().forEach(w -> out.add(w instanceof DataNode.Obj o ? o.get("path").asText() : w.asText()));
            }
        });
        return out;
    }

    private static List<Object> values(Map<String, Object> doc, String path) {
        return DemoPaths.find(doc, path).stream().map(DemoPaths.Slot::get).toList();
    }

    // ---- market data -----------------------------------------------------------------------------------------
    @Test
    void irCurveZeroRatesDiscountFactorsForwardsAndHeadlineMoveTogether() {
        eachSample("ir-curve", (before, after) -> {
            List<Double> zeros = nums(after, "points.*.zeroRate");
            List<Double> zeros0 = nums(before, "points.*.zeroRate");
            List<Object> tenors = values(after, "points.*.tenor");
            for (int i = 0; i < zeros.size(); i++) {
                closeTo(num(after, "points." + i + ".df"), Math.exp(-zeros.get(i) / 100 * DemoExpr.tenorYears((String) tenors.get(i))), 1.5e-6, "df " + tenors.get(i));
            }
            closeTo(num(after, "tenY"), num(after, "points.tenor=10Y.zeroRate") / 100, 1e-6, "tenY is the 10Y zero rate");
            closeTo(num(after, "slope2s10s"), (num(after, "points.tenor=10Y.zeroRate") - num(after, "points.tenor=2Y.zeroRate")) * 100, 0.06, "2s10s");
            assertThat(zeros).as("zero rates moved").isNotEqualTo(zeros0);
            assertThat(moved(before, "forwards.*.rate")).as("forwards.*.rate" + " moved").isTrue();
            assertThat(moved(before, "points.*.quote")).as("points.*.quote" + " moved").isTrue();
            assertThat(moved(before, "tenY")).as("tenY" + " moved").isTrue();
        });
    }

    @Test
    void fxSpotBidAskHoldTheirSpreadAndTheHistoryEndsOnTheSpot() {
        eachSample("fx-spot", (before, after) -> {
            closeTo(num(after, "ask") - num(after, "bid"), num(before, "ask") - num(before, "bid"), 3e-5, "spread");
            assertThat(num(after, "bid")).isLessThan(num(after, "mid"));
            assertThat(num(after, "ask")).isGreaterThan(num(after, "mid"));
            closeTo(num(after, "history.-1.mid"), num(after, "mid"), 1e-9, "today's point");
            closeTo(num(after, "change1d"), num(after, "mid") / num(after, "history.-2.mid") - 1, 2e-5, "change1d");
            assertThat(moved(before, "mid")).as("mid" + " moved").isTrue();
            assertThat(moved(before, "bid")).as("bid" + " moved").isTrue();
        });
    }

    @Test
    void equityPriceBarHistoryAndMarketCapMoveTogether() {
        eachSample("equity", (before, after) -> {
            double px = num(after, "price");
            closeTo(num(after, "history.-1.close"), px, 0.005, "history");
            closeTo(num(after, "ohlc.-1.close"), px, 0.005, "bar close");
            assertThat(num(after, "ohlc.-1.high")).isGreaterThanOrEqualTo(px - 0.005);
            assertThat(num(after, "ohlc.-1.low")).isLessThanOrEqualTo(px + 0.005);
            closeTo(num(after, "change1d"), px / num(after, "history.-2.close") - 1, 1e-4, "change1d");
            closeTo(num(after, "marketCap") / px, num(before, "marketCap") / num(before, "price"), num(before, "marketCap") / num(before, "price") * 1e-3, "shares x fx");
            assertThat(moved(before, "marketCap")).as("marketCap" + " moved").isTrue();
            assertThat(num(after, "ohlc.-1.volume")).isGreaterThanOrEqualTo(num(before, "ohlc.-1.volume"));
        });
    }

    @Test
    void equityIndexLevelHistoryAndChangeMoveTogether() {
        eachSample("equity-index", (before, after) -> {
            closeTo(num(after, "history.-1.close"), num(after, "level"), 0.005, "history");
            closeTo(num(after, "change1d"), num(after, "level") / num(after, "history.-2.close") - 1, 1e-4, "change1d");
            assertThat(moved(before, "level")).as("level" + " moved").isTrue();
        });
    }

    @Test
    void commodityFrontAndTodaysBarMoveTogether() {
        eachSample("commodity", (before, after) -> {
            double front = num(after, "front");
            closeTo(num(after, "ohlc.-1.close"), front, 0.005, "bar close");
            assertThat(num(after, "ohlc.-1.high")).isGreaterThanOrEqualTo(front - 0.005);
            assertThat(num(after, "ohlc.-1.low")).isLessThanOrEqualTo(front + 0.005);
            assertThat(moved(before, "front")).as("front moved").isTrue();
        });
    }

    @Test
    void commodityCurvePointsFrontRollAndShapeMoveTogether() {
        eachSample("commodity-curve", (before, after) -> {
            closeTo(num(after, "front"), num(after, "points.0.price"), 1e-9, "front is the first point");
            closeTo(num(after, "roll"), (num(after, "points.1.price") / num(after, "points.0.price") - 1) * 100, 0.006, "roll");
            assertThat(text(after, "shape")).isEqualTo(num(after, "points.1.price") < num(after, "points.0.price") ? "backwardation" : "contango");
            assertThat(moved(before, "points.*.price")).as("points.*.price" + " moved").isTrue();
            assertThat(moved(before, "points.11.price")).as("points.11.price" + " moved").isTrue();
        });
    }

    @Test
    void creditCurveSpreadsHazardsSurvivalAndHeadlineMoveTogether() {
        eachSample("credit-curve", (before, after) -> {
            double rec = num(after, "recovery");
            List<Double> spreads = nums(after, "points.*.spread");
            List<Object> tenors = values(after, "points.*.tenor");
            for (int i = 0; i < spreads.size(); i++) {
                double hazard = spreads.get(i) / 1e4 / (1 - rec);
                closeTo(num(after, "points." + i + ".hazard"), hazard, 1e-6, "hazard");
                closeTo(num(after, "points." + i + ".survival"), Math.exp(-num(after, "points." + i + ".hazard") * DemoExpr.tenorYears((String) tenors.get(i))), 1e-6, "survival");
                assertThat(spreads.get(i)).isGreaterThanOrEqualTo(0.5);
            }
            closeTo(num(after, "spread5y"), num(after, "points.tenor=5Y.spread"), 1e-9, "5Y headline");
            closeTo(num(after, "pd1y"), 1 - num(after, "points.tenor=1Y.survival"), 1e-9, "pd1y");
            assertThat(spreads).isNotEqualTo(nums(before, "points.*.spread"));
        });
    }

    @Test
    void bondPriceYieldSpreadAndHistoryMoveTogether() {
        eachSample("bond", (before, after) -> {
            double price = num(after, "price");
            closeTo(num(after, "yield"), num(after, "coupon") - (price - 100) / (num(after, "modDuration") * 100), 2e-5, "yield");
            closeTo(num(after, "history.-1.price"), price, 1e-9, "history");
            assertThat(moved(before, "price")).as("price moved").isTrue();
            double dPrice = price - num(before, "price");
            double dSpread = num(after, "zSpread") - num(before, "zSpread");
            assertThat(dPrice * dSpread).as("price up, spread down").isLessThanOrEqualTo(0.0);
            assertThat((num(after, "yield") - num(before, "yield")) * dPrice).as("price up, yield down").isLessThanOrEqualTo(0.0);
        });
    }

    // ---- trading ---------------------------------------------------------------------------------------------
    @Test
    void tradeMtmValuationPnlExplainAndLegsAddUp() {
        int[] legged = {0};
        eachSample("trade", (before, after) -> {
            boolean fromLegs = ((List<?>) at(before, "_meta.walk.derive")).stream().anyMatch(d -> "mtm".equals(((Map<?, ?>) d).get("set")));
            double mtm = num(after, "mtm");
            closeTo(num(after, "valuation.npv"), mtm, 0.5, "npv is the MTM");
            closeTo(num(after, "pnl1d"), mtm - num(after, "pnlExplain.0.pnl"), 1, "day P&L is the MTM less the opening MTM");
            closeTo(nums(after, "pnlExplain.*.pnl").stream().mapToDouble(Double::doubleValue).sum(), mtm, 2, "P&L explain adds up to the MTM");
            List<Double> legPvs = nums(after, "legs.*.pv");
            if (!legPvs.isEmpty() && fromLegs) {
                legged[0]++;
                double sum0 = nums(before, "legs.*.pv").stream().mapToDouble(Double::doubleValue).sum();
                if (Math.abs(sum0 - num(before, "mtm")) <= Math.max(2, Math.abs(num(before, "mtm")) * 1e-6)) {
                    closeTo(legPvs.stream().mapToDouble(Double::doubleValue).sum(), mtm, 1, "MTM is the sum of the leg PVs");
                }
                assertThat(legPvs).as("leg PVs moved").isNotEqualTo(nums(before, "legs.*.pv"));
                for (int i = 0; i < legPvs.size(); i++) {
                    List<Double> flows = nums(after, "legs." + i + ".cashflows.*.pv");
                    if (!flows.isEmpty()) {
                        closeTo(flows.stream().mapToDouble(Double::doubleValue).sum(), legPvs.get(i), 0.01 * flows.size() + 0.05, "leg " + i + " cashflow PVs add up to the leg");
                    }
                }
            }
            assertThat(moved(before, "mtm")).as("mtm moved").isTrue();
        });
        assertThat(legged[0]).as("trades with leg PVs were exercised").isGreaterThan(50);
    }

    @Test
    void nettingSetNetMtmAssetTotalsTradesAndExposureProfileMoveTogether() {
        eachSample("netting-set", (before, after) -> {
            double hidden = num(before, "netMtm") - nums(before, "trades.*.mtm").stream().mapToDouble(Double::doubleValue).sum();
            closeTo(num(after, "netMtm"), hidden + nums(after, "trades.*.mtm").stream().mapToDouble(Double::doubleValue).sum(), 1, "net MTM is the trades");
            closeTo(num(after, "netMtm"), nums(after, "byAsset.*.mtm").stream().mapToDouble(Double::doubleValue).sum(), values(after, "byAsset.*.mtm").size(), "net MTM is the asset totals");
            closeTo(num(after, "eePeak"), nums(after, "profile.*.ee").stream().mapToDouble(Double::doubleValue).max().orElseThrow(), 0.5, "EE peak");
            closeTo(num(after, "pfePeak"), nums(after, "profile.*.pfe").stream().mapToDouble(Double::doubleValue).max().orElseThrow(), 0.5, "PFE peak");
            closeTo(num(after, "utilisation"), num(after, "pfePeak") / num(after, "limit"), 1e-4, "utilisation");
            assertThat(moved(before, "pfePeak")).as("pfePeak" + " moved").isTrue();
            assertThat(moved(before, "netMtm")).as("netMtm" + " moved").isTrue();
            // the profile moved as one: the ratio of any point to the peak is unchanged
            closeTo(num(after, "profile.0.ee") / num(after, "eePeak"), num(before, "profile.0.ee") / num(before, "eePeak"), 2e-3, "profile shape");
        });
    }

    @Test
    void bookMtmIsItsListedTradesPlusTheRestAndMoves() {
        eachSample("book", (before, after) -> {
            double hidden = num(before, "mtm") - nums(before, "topTrades.*.mtm").stream().mapToDouble(Double::doubleValue).sum();
            closeTo(num(after, "mtm"), hidden + nums(after, "topTrades.*.mtm").stream().mapToDouble(Double::doubleValue).sum(), 1, "book MTM");
            assertThat(moved(before, "mtm")).as("mtm" + " moved").isTrue();
        });
    }

    // ---- other packs -----------------------------------------------------------------------------------------
    @Test
    void keyRiskIndicatorReadingStatusAndHistoryMoveTogether() {
        eachSample("key-risk-indicator", (before, after) -> {
            double v = num(after, "value");
            assertThat(v).isGreaterThanOrEqualTo(0);
            assertThat(text(after, "status")).isEqualTo(v >= num(after, "red") ? "Red" : v >= num(after, "amber") ? "Amber" : "Green");
            closeTo(num(after, "history.-1.value"), v, 1e-9, "today's reading");
            assertThat(moved(before, "value")).as("value moved").isTrue();
        });
    }

    @Test
    void aKriCrossingAThresholdChangesItsStatus() {
        DataNode doc = SAMPLES.get("key-risk-indicator").values().iterator().next();
        Map<String, Object> m = raw(doc);
        m.put("value", 0.0);
        m.put("amber", 0.2);
        m.put("red", 0.4);
        Set<String> seen = new LinkedHashSet<>();
        DemoTicker ticker = new DemoTicker(1, Clock.fixed(NOON, ZoneOffset.UTC));
        DataNode now = DataNode.of(m);
        for (int i = 0; i < 4000; i++) {
            now = ticker.tick("key-risk-indicator", now, doc.get("_meta").get("walk"));
            seen.add(now.get("status").asText());
        }
        assertThat(seen).contains("Green").hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void cardUtilisationFollowsTheBalance() {
        eachSample("card-account", (before, after) -> {
            closeTo(num(after, "utilisation"), num(after, "balance") / num(after, "limit"), 1e-3, "utilisation");
            assertThat(moved(before, "balance")).as("balance" + " moved").isTrue();
        });
    }

    @Test
    void depositLedgerLatestLineAndHistoryFollowTheBalance() {
        eachSample("deposit-account", (before, after) -> {
            double bal = num(after, "balance");
            closeTo(num(after, "transactions.0.balance"), bal, 1e-9, "latest running balance");
            closeTo(num(after, "transactions.0.amount"), bal - num(after, "transactions.1.balance"), 0.006, "latest amount is how it got there");
            closeTo(num(after, "history.-1.balance"), bal, 1e-9, "this month");
            assertThat(moved(before, "balance")).as("balance moved").isTrue();
        });
    }

    @Test
    void lcrComponentsFlowsRatioBufferAndTrendMoveTogether() {
        eachSample("lcr", (before, after) -> {
            closeTo(num(after, "hqla"), nums(after, "hqlaByLevel.*.value").stream().mapToDouble(Double::doubleValue).sum(), 1, "HQLA is its levels");
            List<Object> bal = values(after, "flows.*.balance");
            for (int i = 0; i < bal.size(); i++) {
                double expected = Math.signum(num(before, "flows." + i + ".flow")) * num(after, "flows." + i + ".balance") * num(after, "flows." + i + ".rate");
                closeTo(num(after, "flows." + i + ".flow"), expected, 1, "flow " + i);
            }
            double out = -nums(after, "flows.*.flow").stream().filter(x -> x < 0).mapToDouble(Double::doubleValue).sum();
            double in = nums(after, "flows.*.flow").stream().filter(x -> x > 0).mapToDouble(Double::doubleValue).sum();
            closeTo(num(after, "netOutflows"), out - Math.min(in, 0.75 * out), 1, "net outflows");
            closeTo(num(after, "lcr"), num(after, "hqla") / num(after, "netOutflows"), 1e-4, "ratio");
            closeTo(num(after, "buffer"), num(after, "hqla") - num(after, "netOutflows"), 1, "buffer");
            closeTo(num(after, "trend.-1.lcr"), num(after, "lcr"), 1e-9, "today's trend point");
            assertThat(moved(before, "lcr")).as("lcr" + " moved").isTrue();
            assertThat(moved(before, "flows.*.balance")).as("flows.*.balance" + " moved").isTrue();
        });
    }

    @Test
    void intradayPeakIsTheHighestHourAndUsageFollows() {
        eachSample("intraday-liquidity", (before, after) -> {
            closeTo(num(after, "peakUsage"), nums(after, "usage.*.used").stream().mapToDouble(Double::doubleValue).max().orElseThrow(), 1, "peak");
            closeTo(num(after, "usagePct"), num(after, "peakUsage") / num(after, "available"), 1e-4, "usage ratio");
            assertThat(moved(before, "usage.-1.used")).as("usage.-1.used" + " moved").isTrue();
        });
    }

    @Test
    void containerCurrentHourReadingIsTheCurrentTemperature() {
        eachSample("container", (before, after) -> {
            assertThat(values(after, "readings.*.hour")).hasSize(24);
            assertThat(text(after, "readings.hour=H14.hour")).isEqualTo("H14");
            closeTo(num(after, "readings.hour=H14.celsius"), num(after, "currentC"), 0.051, "this hour's reading");
            assertThat(moved(before, "currentC")).as("currentC" + " moved").isTrue();
        });
    }
}
