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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** The coherent walk: one step moves a group, derive recomputes dependents, append grows a series. */
class DemoCoherentWalkTest {

    private final JsonCodec json = new JsonCodec();

    private static final String CURVE = """
            {"tenY": 0.0382, "slope2s10s": 22.6,
             "points": [{"tenor": "1Y", "zeroRate": 3.64, "quote": 0.0357, "df": 0.9643},
                        {"tenor": "2Y", "zeroRate": 3.59, "quote": 0.0346, "df": 0.9307},
                        {"tenor": "10Y", "zeroRate": 3.82, "quote": 0.0317, "df": 0.6825}],
             "forwards": [{"tenor": "1Y", "rate": 3.56}, {"tenor": "10Y", "rate": 3.85}]}""";

    private static final String CURVE_WALK = """
            {"tenY": {"step": 0.0003, "scale": 100, "with": ["points.*.zeroRate", "forwards.*.rate", {"path": "points.*.quote", "scale": 1}]},
             "derive": [{"set": "points.*.df", "expr": "exp(-zeroRate / 100 * tenor(tenor))", "places": 6},
                        {"set": "tenY", "expr": "points.tenor=10Y.zeroRate / 100"},
                        {"set": "slope2s10s", "expr": "(points.tenor=10Y.zeroRate - points.tenor=2Y.zeroRate) * 100", "places": 1}]}""";

    @Test
    void oneStepMovesTheWholeCurveAndTheDependentsFollow() {
        DemoTicker ticker = new DemoTicker(11);
        DataNode doc = json.read(CURVE);
        DataNode walk = json.read(CURVE_WALK);
        double zero0 = doc.get("points").get(0).get("zeroRate").asDouble();
        double fwd0 = doc.get("forwards").get(1).get("rate").asDouble();
        for (int i = 0; i < 20; i++) {
            doc = ticker.tick("ir-curve", doc, walk);
        }
        assertThat(doc.get("points").get(0).get("zeroRate").asDouble()).isNotEqualTo(zero0);
        assertThat(doc.get("forwards").get(1).get("rate").asDouble()).isNotEqualTo(fwd0);
        for (int i = 0; i < 3; i++) {
            DataNode p = doc.get("points").get(i);
            double t = new double[] {1, 2, 10}[i];
            assertThat(p.get("df").asDouble()).isCloseTo(Math.exp(-p.get("zeroRate").asDouble() / 100 * t), within(1e-6));
        }
        // headline = the 10Y point, in the headline's own unit (decimal), and the slope in basis points
        assertThat(doc.get("tenY").asDouble()).isCloseTo(doc.get("points").get(2).get("zeroRate").asDouble() / 100, within(1e-9));
        assertThat(doc.get("slope2s10s").asDouble())
                .isCloseTo((doc.get("points").get(2).get("zeroRate").asDouble() - doc.get("points").get(1).get("zeroRate").asDouble()) * 100, within(0.06));
        // a parallel step: every zero rate moved by the same amount, every forward by that same amount
        double moved = doc.get("points").get(2).get("zeroRate").asDouble() - 3.82;
        assertThat(doc.get("points").get(0).get("zeroRate").asDouble() - 3.64).isCloseTo(moved, within(1e-3));
        assertThat(doc.get("forwards").get(1).get("rate").asDouble() - 3.85).isCloseTo(moved, within(1e-3));
        assertThat(doc.get("points").get(0).get("quote").asDouble() - 0.0357).isCloseTo(moved / 100, within(1e-5));
    }

    @Test
    void aTwistTiltsTheCurveAndAJitterBlursIt() {
        DataNode walk = json.read("{\"tenY\": {\"step\": 0.0003, \"scale\": 100, \"shape\": \"twist\", \"with\": [\"points.*.zeroRate\"]}}");
        DemoTicker ticker = new DemoTicker(5);
        DataNode doc = json.read(CURVE);
        double spread = 0;
        for (int i = 0; i < 30; i++) {
            doc = ticker.tick("ir-curve", doc, walk);
            spread += Math.abs((doc.get("points").get(2).get("zeroRate").asDouble() - 3.82) - (doc.get("points").get(0).get("zeroRate").asDouble() - 3.64));
        }
        assertThat(spread).as("short and long ends part company under a twist").isGreaterThan(0.01);
    }

    @Test
    void aDerivedTotalAndRatioAndStatusFollowTheirParts() {
        DataNode walk = json.read("""
                {"a": {"step": 5, "with": ["parts.*.v"]},
                 "derive": [{"set": "total", "expr": "sum(parts.*.v)"}, {"set": "ratio", "expr": "a / total", "places": 4},
                            {"set": "status", "expr": "total >= red ? 'Red' : total >= amber ? 'Amber' : 'Green'"}]}""");
        DataNode doc = json.read("{\"a\": 100, \"total\": 300, \"ratio\": 0.33, \"amber\": 250, \"red\": 400, \"status\": \"Amber\", \"parts\": [{\"v\": 100}, {\"v\": 200}]}");
        DemoTicker ticker = new DemoTicker(3);
        for (int i = 0; i < 10; i++) {
            doc = ticker.tick("x", doc, walk);
            double sum = doc.get("parts").get(0).get("v").asDouble() + doc.get("parts").get(1).get("v").asDouble();
            assertThat(doc.get("total").asDouble()).isEqualTo(sum);
            assertThat(doc.get("ratio").asDouble()).isCloseTo(doc.get("a").asDouble() / sum, within(1e-4));
            assertThat(doc.get("status").asText()).isEqualTo(sum >= 400 ? "Red" : sum >= 250 ? "Amber" : "Green");
        }
    }

    @Test
    void aSeriesGrowsByTheMinuteAndKeepsTheLastRows() {
        DataNode walk = json.read("""
                {"x": 1, "append": [{"to": "usage", "key": "time", "format": "HH:mm", "keep": 3, "row": {"used": "max(0, last.used + noise(10))"}}]}""");
        DataNode doc = json.read("{\"x\": 5, \"usage\": [{\"time\": \"09:58\", \"used\": 100}]}");
        Instant t0 = Instant.parse("2026-10-01T10:00:10Z");
        DemoTicker first = new DemoTicker(1, Clock.fixed(t0, ZoneOffset.UTC));
        doc = first.tick("x", doc, walk);
        assertThat(doc.get("usage").size()).isEqualTo(2);
        assertThat(doc.get("usage").get(1).get("time").asText()).isEqualTo("10:00");
        doc = first.tick("x", doc, walk);
        assertThat(doc.get("usage").size()).as("same minute, same row").isEqualTo(2);
        for (int minute = 1; minute <= 4; minute++) {
            doc = new DemoTicker(1, Clock.fixed(t0.plusSeconds(60L * minute), ZoneOffset.UTC)).tick("x", doc, walk);
        }
        assertThat(doc.get("usage").size()).isEqualTo(3);
        assertThat(doc.get("usage").get(2).get("time").asText()).isEqualTo("10:04");
        assertThat(doc.get("usage").get(0).get("time").asText()).isEqualTo("10:02");
    }

    @Test
    void aBrokenEntryIsSkippedAndTheRestStillWalk() {
        DataNode walk = json.read("{\"a\": 0.5, \"b\": 0.5, \"derive\": [{\"set\": \"c\", \"expr\": \"nope(\"}, {\"set\": \"d\", \"expr\": \"a + 1\"}]}");
        DataNode doc = json.read("{\"a\": 10, \"b\": 20, \"c\": 1, \"d\": 1.5}");
        DataNode next = new DemoTicker(2).tick("x", doc, walk);
        assertThat(next.get("a").asDouble()).isNotEqualTo(10.0);
        assertThat(next.get("c").asDouble()).isEqualTo(1.0);
        assertThat(next.get("d").asDouble()).isCloseTo(next.get("a").asDouble() + 1, within(0.01));
    }

    @Test
    void anAbsoluteWalkKeepsItsStepPrecisionAsBefore() {
        DataNode next = new DemoTicker(9).tick("fx-spot", json.read("{\"mid\": 0.661}"), json.read("{\"mid\": 0.0004}"));
        assertThat(next.get("mid").asDouble()).isNotEqualTo(0.661);
        assertThat(String.valueOf(next.get("mid").asDouble()).length()).isLessThanOrEqualTo(8);
    }
}
