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
package com.ash.drishti.server.alerts.measure;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.alerts.measure.AlertLogic.Action;
import com.ash.drishti.server.alerts.measure.AlertLogic.Outcome;
import com.ash.drishti.server.alerts.measure.AlertLogic.Reading;
import com.ash.drishti.server.alerts.measure.AlertLogic.State;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The decision an alert makes, with no I/O: thresholds, changes, "no data", hysteresis, cooldown, and a reading that cannot be judged. */
class AlertLogicTest {

    static final Instant T0 = Instant.parse("2026-11-25T15:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();
    static final MeasureAlertProperties PROPS = MeasureAlertProperties.defaults();

    static AlertSpec spec(String condition, String extra) {
        try {
            return AlertSpec.parse("alice", "a", JSON.readTree("{\"source\":{\"type\":\"dataset\",\"dataset\":\"trading\",\"measure\":\"exposure\"},\"condition\":" + condition
                    + ",\"trigger\":{\"mode\":\"schedule\",\"schedule\":\"hourly\"}" + (extra.isEmpty() ? "" : "," + extra) + "}"), PROPS);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Reading ok(double v) {
        return Reading.ok(v, LocalDate.of(2026, 11, 25));
    }

    static Outcome decide(AlertSpec s, double v, State st, Instant at) {
        return AlertLogic.decide(s, ok(v), null, st, at, false);
    }

    @Test
    void aboveFiresOnceAndStaysQuietWhileActive() {
        AlertSpec s = spec("{\"type\":\"threshold\",\"op\":\"above\",\"value\":100}", "\"cooldown\":\"0\"");
        assertThat(decide(s, 99, State.FRESH, T0).action()).isEqualTo(Action.NONE);
        assertThat(decide(s, 100, State.FRESH, T0).action()).isEqualTo(Action.NONE);                       // above means strictly
        assertThat(decide(s, 101, State.FRESH, T0).action()).isEqualTo(Action.FIRE);
        State active = State.FRESH.with(true, T0, 101.0, T0, "");
        assertThat(decide(s, 150, active, T0.plusSeconds(60)).action()).isEqualTo(Action.NONE);             // still above: it does not repeat
        assertThat(decide(s, 99, active, T0.plusSeconds(120)).action()).isEqualTo(Action.CLEAR);
    }

    @Test
    void belowBetweenAndOutside() {
        AlertSpec below = spec("{\"type\":\"threshold\",\"op\":\"below\",\"value\":10}", "\"cooldown\":\"0\"");
        assertThat(decide(below, 9.9, State.FRESH, T0).action()).isEqualTo(Action.FIRE);
        assertThat(decide(below, 10, State.FRESH, T0).action()).isEqualTo(Action.NONE);
        AlertSpec between = spec("{\"type\":\"threshold\",\"op\":\"between\",\"value\":10,\"high\":20}", "\"cooldown\":\"0\"");
        assertThat(decide(between, 10, State.FRESH, T0).action()).isEqualTo(Action.FIRE);                   // the edges are inside
        assertThat(decide(between, 20, State.FRESH, T0).action()).isEqualTo(Action.FIRE);
        assertThat(decide(between, 20.1, State.FRESH, T0).action()).isEqualTo(Action.NONE);
        AlertSpec outside = spec("{\"type\":\"threshold\",\"op\":\"outside\",\"value\":10,\"high\":20}", "\"cooldown\":\"0\"");
        assertThat(decide(outside, 9, State.FRESH, T0).action()).isEqualTo(Action.FIRE);
        assertThat(decide(outside, 21, State.FRESH, T0).action()).isEqualTo(Action.FIRE);
        assertThat(decide(outside, 15, State.FRESH, T0).action()).isEqualTo(Action.NONE);
        assertThat(decide(outside, 10, State.FRESH, T0).action()).isEqualTo(Action.NONE);
    }

    @Test
    void hysteresisKeepsAValueHoveringAtTheEdgeFromFlapping() {
        AlertSpec s = spec("{\"type\":\"threshold\",\"op\":\"above\",\"value\":100}", "\"hysteresis\":5,\"cooldown\":\"0\"");
        State st = State.FRESH;
        Outcome o = decide(s, 101, st, T0);
        assertThat(o.action()).isEqualTo(Action.FIRE);
        st = st.with(true, T0, 101.0, T0, "");
        assertThat(decide(s, 99, st, T0.plusSeconds(10)).action()).isEqualTo(Action.NONE);                 // back under 100, but not by 5: still active
        assertThat(decide(s, 101, st, T0.plusSeconds(20)).action()).isEqualTo(Action.NONE);                // over again: no second alert
        assertThat(decide(s, 95, st, T0.plusSeconds(30)).action()).isEqualTo(Action.CLEAR);                // 100 - 5: re-armed
        State armed = st.with(false, T0, 95.0, T0, "");
        assertThat(decide(s, 101, armed, T0.plusSeconds(40)).action()).isEqualTo(Action.FIRE);
    }

    @Test
    void hysteresisFortheOtherShapes() {
        AlertSpec below = spec("{\"type\":\"threshold\",\"op\":\"below\",\"value\":10}", "\"hysteresis\":2");
        State active = State.FRESH.with(true, T0.minus(Duration.ofDays(1)), 9.0, T0, "");
        assertThat(decide(below, 11, active, T0).action()).isEqualTo(Action.NONE);
        assertThat(decide(below, 12, active, T0).action()).isEqualTo(Action.CLEAR);
        AlertSpec between = spec("{\"type\":\"threshold\",\"op\":\"between\",\"value\":10,\"high\":20}", "\"hysteresis\":1");
        assertThat(decide(between, 20.5, active, T0).action()).isEqualTo(Action.NONE);
        assertThat(decide(between, 21.5, active, T0).action()).isEqualTo(Action.CLEAR);
        AlertSpec outside = spec("{\"type\":\"threshold\",\"op\":\"outside\",\"value\":10,\"high\":20}", "\"hysteresis\":1");
        assertThat(decide(outside, 10.5, active, T0).action()).isEqualTo(Action.NONE);
        assertThat(decide(outside, 11, active, T0).action()).isEqualTo(Action.CLEAR);
    }

    @Test
    void cooldownHoldsABreachBackUntilItIsOver() {
        AlertSpec s = spec("{\"type\":\"threshold\",\"op\":\"above\",\"value\":100}", "\"cooldown\":\"30m\"");
        State justFired = State.FRESH.with(false, T0, 90.0, T0, "");                                         // fired at T0, cleared since
        Outcome held = decide(s, 120, justFired, T0.plus(Duration.ofMinutes(10)));
        assertThat(held.action()).isEqualTo(Action.HELD);
        assertThat(held.why()).contains("cooldown");
        assertThat(decide(s, 120, justFired, T0.plus(Duration.ofMinutes(30))).action()).isEqualTo(Action.FIRE);   // the cooldown ends: it fires if it still holds
        assertThat(decide(s, 90, justFired, T0.plus(Duration.ofMinutes(10))).action()).isEqualTo(Action.NONE);
    }

    @Test
    void changeInPercentAgainstTheEarlierNumber() {
        AlertSpec s = spec("{\"type\":\"change\",\"mode\":\"pct\",\"op\":\"moves\",\"value\":10,\"against\":\"previous-run\"}", "\"cooldown\":\"0\"");
        assertThat(AlertLogic.decide(s, ok(111), ok(100), State.FRESH, T0, false).action()).isEqualTo(Action.FIRE);       // +11%
        assertThat(AlertLogic.decide(s, ok(89), ok(100), State.FRESH, T0, false).action()).isEqualTo(Action.FIRE);        // -11%
        Outcome inside = AlertLogic.decide(s, ok(105), ok(100), State.FRESH, T0, false);
        assertThat(inside.action()).isEqualTo(Action.NONE);
        assertThat(inside.delta()).isEqualTo(5.0);
        assertThat(AlertLogic.decide(s, ok(-120), ok(-100), State.FRESH, T0, false).delta()).isEqualTo(-20.0);          // a negative base: the sign follows the move up or down
    }

    @Test
    void changeRisesFallsAndAbsolute() {
        AlertSpec rises = spec("{\"type\":\"change\",\"mode\":\"abs\",\"op\":\"rises\",\"value\":50,\"against\":\"previous-day\"}", "\"cooldown\":\"0\"");
        assertThat(AlertLogic.decide(rises, ok(160), ok(100), State.FRESH, T0, false).action()).isEqualTo(Action.FIRE);
        assertThat(AlertLogic.decide(rises, ok(40), ok(100), State.FRESH, T0, false).action()).isEqualTo(Action.NONE);      // a fall is not a rise
        AlertSpec falls = spec("{\"type\":\"change\",\"mode\":\"abs\",\"op\":\"falls\",\"value\":50,\"against\":\"previous-day\"}", "\"cooldown\":\"0\"");
        assertThat(AlertLogic.decide(falls, ok(40), ok(100), State.FRESH, T0, false).action()).isEqualTo(Action.FIRE);
        assertThat(AlertLogic.decide(falls, ok(160), ok(100), State.FRESH, T0, false).action()).isEqualTo(Action.NONE);
    }

    @Test
    void aChangeWithNothingToCompareWithIsSkippedAndAZeroBaseCannotBeAPercentage() {
        AlertSpec s = spec("{\"type\":\"change\",\"mode\":\"pct\",\"op\":\"moves\",\"value\":10,\"against\":\"previous-run\"}", "");
        Outcome first = AlertLogic.decide(s, ok(100), null, State.FRESH, T0, false);
        assertThat(first.action()).isEqualTo(Action.SKIP);
        assertThat(first.why()).contains("no earlier number");
        Outcome zero = AlertLogic.decide(s, ok(5), ok(0), State.FRESH, T0, false);
        assertThat(zero.action()).isEqualTo(Action.SKIP);
        assertThat(zero.why()).contains("zero");
        assertThat(AlertLogic.decide(s, ok(0), ok(0), State.FRESH, T0, false).action()).isEqualTo(Action.NONE);
    }

    @Test
    void noDataFiresOnlyAfterTheDeadlineAndClearsWhenDataLands() {
        AlertSpec s = spec("{\"type\":\"nodata\",\"by\":\"09:00\"}", "\"cooldown\":\"0\"");
        Reading none = Reading.noData("latest data is of 2026-11-24");
        assertThat(AlertLogic.decide(s, none, null, State.FRESH, T0, false).action()).isEqualTo(Action.NONE);      // before 09:00 it may still come
        assertThat(AlertLogic.decide(s, none, null, State.FRESH, T0, true).action()).isEqualTo(Action.FIRE);
        State active = State.FRESH.with(true, T0, null, T0, "");
        assertThat(AlertLogic.decide(s, none, null, active, T0.plusSeconds(600), true).action()).isEqualTo(Action.NONE);   // once, not every look
        assertThat(AlertLogic.decide(s, ok(1), null, active, T0.plusSeconds(1200), true).action()).isEqualTo(Action.CLEAR);
        assertThat(AlertLogic.decide(s, Reading.error("the engine is down"), null, State.FRESH, T0, true).action()).isEqualTo(Action.SKIP);   // an error is not "no data"
    }

    @Test
    void aMaskedOrMissingReadingIsNeverJudged() {
        AlertSpec s = spec("{\"type\":\"threshold\",\"op\":\"above\",\"value\":1}", "");
        Outcome masked = AlertLogic.decide(s, Reading.masked("exposure"), null, State.FRESH, T0, false);
        assertThat(masked.action()).isEqualTo(Action.SKIP);
        assertThat(masked.why()).contains("masked for you");
        assertThat(Double.isNaN(masked.value())).isTrue();                                                    // no number is carried out of a masked reading
        State active = State.FRESH.with(true, T0, 5.0, T0, "");
        assertThat(AlertLogic.decide(s, Reading.masked("x"), null, active, T0, false).action()).isEqualTo(Action.SKIP);   // and it does not clear an active alert either
        assertThat(AlertLogic.decide(s, Reading.noData("no rows"), null, State.FRESH, T0, false).action()).isEqualTo(Action.SKIP);
    }

    @Test
    void aTileLevelFiresWhenItReachesTheColourAndClearsBelowIt() {
        AlertSpec crit = tile("crit");
        assertThat(AlertLogic.decide(crit, ok(1), null, State.FRESH, T0, false).action()).isEqualTo(Action.NONE);    // amber, but the tile notifies on red
        assertThat(AlertLogic.decide(crit, ok(2), null, State.FRESH, T0, false).action()).isEqualTo(Action.FIRE);
        State active = State.FRESH.with(true, T0, 2.0, T0, "");
        assertThat(AlertLogic.decide(crit, ok(2), null, active, T0, false).action()).isEqualTo(Action.NONE);
        assertThat(AlertLogic.decide(crit, ok(1), null, active, T0, false).action()).isEqualTo(Action.CLEAR);
        assertThat(AlertLogic.decide(tile("warn"), ok(1), null, State.FRESH, T0, false).action()).isEqualTo(Action.FIRE);
    }

    static AlertSpec tile(String level) {
        return new AlertSpec("t", "board", "wall · t", "critical", true, new AlertSpec.Source("tile", "r", "", "v", null, "", "", "first", null, "", "", "", null),
                new AlertSpec.Condition("level", "", 0, 0, "", "", null, level), new AlertSpec.Trigger("after-load", "", "", Duration.ZERO), 0, Duration.ZERO,
                new AlertSpec.Delivery(true, false, false), new AlertSpec.BoardRef("wall", "p", "t"));
    }

    @Test
    void stateRoundTripsThroughItsJson() {
        State s = new State(true, T0, 12.5, T0.plusSeconds(5), "held");
        State back = State.of(s.toJson());
        assertThat(back).isEqualTo(s);
        assertThat(State.of(null)).isEqualTo(State.FRESH);
        assertThat(MeasureAlertService.expand(MeasureAlertService.compact(s)).active()).isTrue();
    }
}
