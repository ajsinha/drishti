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
import com.ash.drishti.common.PackSamples;
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
    private static final Set<String> CONDITIONAL = Set.of("ohlc.-1.high", "ohlc.-1.low", "shape", "OHLC.-1.HIGH", "OHLC.-1.LOW", "SHAPE");
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
                for (String kind : PackSamples.kinds(samples)) {          // samples/<kind>.jsonl, or samples/<kind>/<id>.json
                    for (Map.Entry<String, String> e : PackSamples.documents(samples, kind, Set.of(), 0).entrySet()) {
                        DataNode doc = JSON.read(e.getValue());
                        if (doc.get("_meta").get("walk") instanceof DataNode.Obj w && w.size() > 0) {
                            SAMPLES.computeIfAbsent(kind, k -> new TreeMap<>()).put(e.getKey(), doc);
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
            List<Double> zeros = nums(after, "POINTS.*.ZERO_RATE");
            List<Double> zeros0 = nums(before, "POINTS.*.ZERO_RATE");
            List<Object> tenors = values(after, "POINTS.*.TENOR");
            for (int i = 0; i < zeros.size(); i++) {
                closeTo(num(after, "POINTS." + i + ".DF"), Math.exp(-zeros.get(i) / 100 * DemoExpr.tenorYears((String) tenors.get(i))), 1.5e-6, "df " + tenors.get(i));
            }
            closeTo(num(after, "TEN_Y"), num(after, "POINTS.TENOR=10Y.ZERO_RATE") / 100, 1e-6, "tenY is the 10Y zero rate");
            closeTo(num(after, "SLOPE_2S_10S"), (num(after, "POINTS.TENOR=10Y.ZERO_RATE") - num(after, "POINTS.TENOR=2Y.ZERO_RATE")) * 100, 0.06, "2s10s");
            assertThat(zeros).as("zero rates moved").isNotEqualTo(zeros0);
            assertThat(moved(before, "FORWARDS.*.RATE")).as("forwards.*.rate" + " moved").isTrue();
            assertThat(moved(before, "POINTS.*.QUOTE")).as("points.*.quote" + " moved").isTrue();
            assertThat(moved(before, "TEN_Y")).as("tenY" + " moved").isTrue();
        });
    }

    @Test
    void fxSpotBidAskHoldTheirSpreadAndTheHistoryEndsOnTheSpot() {
        eachSample("fx-spot", (before, after) -> {
            closeTo(num(after, "ASK") - num(after, "BID"), num(before, "ASK") - num(before, "BID"), 3e-5, "spread");
            assertThat(num(after, "BID")).isLessThan(num(after, "MID"));
            assertThat(num(after, "ASK")).isGreaterThan(num(after, "MID"));
            closeTo(num(after, "HISTORY.-1.MID"), num(after, "MID"), 1e-9, "today's point");
            closeTo(num(after, "CHANGE_1D"), num(after, "MID") / num(after, "HISTORY.-2.MID") - 1, 2e-5, "change1d");
            assertThat(moved(before, "MID")).as("mid" + " moved").isTrue();
            assertThat(moved(before, "BID")).as("bid" + " moved").isTrue();
        });
    }

    @Test
    void equityPriceBarHistoryAndMarketCapMoveTogether() {
        eachSample("equity", (before, after) -> {
            double px = num(after, "PRICE");
            closeTo(num(after, "HISTORY.-1.CLOSE"), px, 0.005, "history");
            closeTo(num(after, "OHLC.-1.CLOSE"), px, 0.005, "bar close");
            assertThat(num(after, "OHLC.-1.HIGH")).isGreaterThanOrEqualTo(px - 0.005);
            assertThat(num(after, "OHLC.-1.LOW")).isLessThanOrEqualTo(px + 0.005);
            closeTo(num(after, "CHANGE_1D"), px / num(after, "HISTORY.-2.CLOSE") - 1, 1e-4, "change1d");
            closeTo(num(after, "MARKET_CAP") / px, num(before, "MARKET_CAP") / num(before, "PRICE"), num(before, "MARKET_CAP") / num(before, "PRICE") * 1e-3, "shares x fx");
            assertThat(moved(before, "MARKET_CAP")).as("marketCap" + " moved").isTrue();
            assertThat(num(after, "OHLC.-1.VOLUME")).isGreaterThanOrEqualTo(num(before, "OHLC.-1.VOLUME"));
        });
    }

    @Test
    void equityIndexLevelHistoryAndChangeMoveTogether() {
        eachSample("equity-index", (before, after) -> {
            closeTo(num(after, "HISTORY.-1.CLOSE"), num(after, "LEVEL"), 0.005, "history");
            closeTo(num(after, "CHANGE_1D"), num(after, "LEVEL") / num(after, "HISTORY.-2.CLOSE") - 1, 1e-4, "change1d");
            assertThat(moved(before, "LEVEL")).as("level" + " moved").isTrue();
        });
    }

    @Test
    void commodityFrontAndTodaysBarMoveTogether() {
        eachSample("commodity", (before, after) -> {
            double front = num(after, "FRONT");
            closeTo(num(after, "OHLC.-1.CLOSE"), front, 0.005, "bar close");
            assertThat(num(after, "OHLC.-1.HIGH")).isGreaterThanOrEqualTo(front - 0.005);
            assertThat(num(after, "OHLC.-1.LOW")).isLessThanOrEqualTo(front + 0.005);
            assertThat(moved(before, "FRONT")).as("front moved").isTrue();
        });
    }

    @Test
    void commodityCurvePointsFrontRollAndShapeMoveTogether() {
        eachSample("commodity-curve", (before, after) -> {
            closeTo(num(after, "FRONT"), num(after, "POINTS.0.PRICE"), 1e-9, "front is the first point");
            closeTo(num(after, "ROLL"), (num(after, "POINTS.1.PRICE") / num(after, "POINTS.0.PRICE") - 1) * 100, 0.006, "roll");
            assertThat(text(after, "SHAPE")).isEqualTo(num(after, "POINTS.1.PRICE") < num(after, "POINTS.0.PRICE") ? "backwardation" : "contango");
            assertThat(moved(before, "POINTS.*.PRICE")).as("points.*.price" + " moved").isTrue();
            assertThat(moved(before, "POINTS.11.PRICE")).as("points.11.price" + " moved").isTrue();
        });
    }

    @Test
    void creditCurveSpreadsHazardsSurvivalAndHeadlineMoveTogether() {
        eachSample("credit-curve", (before, after) -> {
            double rec = num(after, "RECOVERY");
            List<Double> spreads = nums(after, "POINTS.*.SPREAD");
            List<Object> tenors = values(after, "POINTS.*.TENOR");
            for (int i = 0; i < spreads.size(); i++) {
                double hazard = spreads.get(i) / 1e4 / (1 - rec);
                closeTo(num(after, "POINTS." + i + ".HAZARD"), hazard, 1e-6, "hazard");
                closeTo(num(after, "POINTS." + i + ".SURVIVAL"), Math.exp(-num(after, "POINTS." + i + ".HAZARD") * DemoExpr.tenorYears((String) tenors.get(i))), 1e-6, "survival");
                assertThat(spreads.get(i)).isGreaterThanOrEqualTo(0.5);
            }
            closeTo(num(after, "SPREAD_5Y"), num(after, "POINTS.TENOR=5Y.SPREAD"), 1e-9, "5Y headline");
            closeTo(num(after, "PD_1Y"), 1 - num(after, "POINTS.TENOR=1Y.SURVIVAL"), 1e-9, "pd1y");
            assertThat(spreads).isNotEqualTo(nums(before, "POINTS.*.SPREAD"));
        });
    }

    @Test
    void bondPriceYieldSpreadAndHistoryMoveTogether() {
        eachSample("bond", (before, after) -> {
            double price = num(after, "PRICE");
            closeTo(num(after, "YIELD"), num(after, "COUPON") - (price - 100) / (num(after, "MOD_DURATION") * 100), 2e-5, "yield");
            closeTo(num(after, "HISTORY.-1.PRICE"), price, 1e-9, "history");
            assertThat(moved(before, "PRICE")).as("price moved").isTrue();
            double dPrice = price - num(before, "PRICE");
            double dSpread = num(after, "Z_SPREAD") - num(before, "Z_SPREAD");
            assertThat(dPrice * dSpread).as("price up, spread down").isLessThanOrEqualTo(0.0);
            assertThat((num(after, "YIELD") - num(before, "YIELD")) * dPrice).as("price up, yield down").isLessThanOrEqualTo(0.0);
        });
    }

    // ---- trading ---------------------------------------------------------------------------------------------
    /** A trade is a booking (it does not tick); its valuation does: the MTM walks and the day's P&L is the MTM less the opening MTM. */
    @Test
    void tradeValuationMtmWalksAndItsDayPnlFollows() {
        eachSample("trade-valuation", (before, after) -> {
            double opening = num(before, "MTM") - num(before, "PNL_1D");
            closeTo(num(after, "PNL_1D"), num(after, "MTM") - opening, 1, "day P&L is the MTM less the opening MTM");
            assertThat(moved(before, "MTM")).as("MTM moved").isTrue();
        });
    }

    @Test
    void nettingSetNetMtmAssetTotalsTradesAndExposureProfileMoveTogether() {
        eachSample("netting-set", (before, after) -> {
            double hidden = num(before, "NET_MTM") - nums(before, "TRADES.*.MTM").stream().mapToDouble(Double::doubleValue).sum();
            closeTo(num(after, "NET_MTM"), hidden + nums(after, "TRADES.*.MTM").stream().mapToDouble(Double::doubleValue).sum(), 1, "net MTM is the trades");
            closeTo(num(after, "NET_MTM"), nums(after, "BY_ASSET.*.MTM").stream().mapToDouble(Double::doubleValue).sum(), values(after, "BY_ASSET.*.MTM").size(), "net MTM is the asset totals");
            closeTo(num(after, "EE_PEAK"), nums(after, "PROFILE.*.EE").stream().mapToDouble(Double::doubleValue).max().orElseThrow(), 0.5, "EE peak");
            closeTo(num(after, "PFE_PEAK"), nums(after, "PROFILE.*.PFE").stream().mapToDouble(Double::doubleValue).max().orElseThrow(), 0.5, "PFE peak");
            closeTo(num(after, "UTILISATION"), num(after, "PFE_PEAK") / num(after, "LIMIT"), 1e-4, "utilisation");
            assertThat(moved(before, "PFE_PEAK")).as("pfePeak" + " moved").isTrue();
            assertThat(moved(before, "NET_MTM")).as("netMtm" + " moved").isTrue();
            // the profile moved as one: the ratio of any point to the peak is unchanged
            closeTo(num(after, "PROFILE.0.EE") / num(after, "EE_PEAK"), num(before, "PROFILE.0.EE") / num(before, "EE_PEAK"), 2e-3, "profile shape");
        });
    }

    @Test
    void bookMtmIsItsListedTradesPlusTheRestAndMoves() {
        eachSample("book", (before, after) -> {
            double hidden = num(before, "MTM") - nums(before, "TOP_TRADES.*.MTM").stream().mapToDouble(Double::doubleValue).sum();
            closeTo(num(after, "MTM"), hidden + nums(after, "TOP_TRADES.*.MTM").stream().mapToDouble(Double::doubleValue).sum(), 1, "book MTM");
            assertThat(moved(before, "MTM")).as("mtm" + " moved").isTrue();
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
