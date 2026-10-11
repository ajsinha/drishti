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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The decision an alert makes each time it is looked at, with no I/O: given the number it reads now (and, for a change, the number it compares with), what
 * it remembers about this person, and the time, does it fire, clear, wait or do nothing.
 *
 * <p>An alert is a small state machine per person. It is <b>armed</b> at first. When the value breaches the condition it <b>fires</b> and becomes
 * <b>active</b>; while active it does not fire again. It becomes armed again only when the value is back inside the limit by at least the
 * <b>hysteresis</b> (so a value hovering at the edge does not flap), and it fires again only after the <b>cooldown</b> since it last fired has passed. A
 * breach during the cooldown is held back ({@link Action#HELD}) and fires when the cooldown ends if it still holds. A reading that cannot be judged (masked for
 * this person, no data, an error) never fires and never clears.
 */
public final class AlertLogic {

    private AlertLogic() {}

    /** What the reader found. */
    public record Reading(Status status, double value, LocalDate asOf, String note) {

        public enum Status { OK, MASKED, NO_DATA, ERROR }

        public static Reading ok(double value, LocalDate asOf) {
            return new Reading(Status.OK, value, asOf, "");
        }

        public static Reading masked(String note) {
            return new Reading(Status.MASKED, Double.NaN, null, note);
        }

        public static Reading noData(String note) {
            return new Reading(Status.NO_DATA, Double.NaN, null, note);
        }

        public static Reading error(String note) {
            return new Reading(Status.ERROR, Double.NaN, null, note);
        }

        public boolean isOk() {
            return status == Status.OK;
        }
    }

    /** What an alert remembers about one person: whether it is active, when it last fired and the last number it read. */
    public record State(boolean active, Instant firedAt, Double lastValue, Instant lastEval, String note) {

        public static final State FRESH = new State(false, null, null, null, "");
        private static final ObjectMapper JSON = new ObjectMapper();

        public State with(boolean a, Instant fired, Double value, Instant at, String why) {
            return new State(a, fired, value, at, why);
        }

        public ObjectNode toJson() {
            ObjectNode n = JSON.createObjectNode().put("active", active);
            if (firedAt != null) {
                n.put("firedAt", firedAt.toString());
            }
            if (lastValue != null && Double.isFinite(lastValue)) {
                n.put("lastValue", lastValue);
            }
            if (lastEval != null) {
                n.put("lastEval", lastEval.toString());
            }
            if (note != null && !note.isEmpty()) {
                n.put("note", note);
            }
            return n;
        }

        public static State of(JsonNode n) {
            if (n == null || !n.isObject()) {
                return FRESH;
            }
            return new State(n.path("active").asBoolean(false), n.hasNonNull("firedAt") ? Instant.parse(n.get("firedAt").asText()) : null,
                    n.path("lastValue").isNumber() ? n.get("lastValue").asDouble() : null, n.hasNonNull("lastEval") ? Instant.parse(n.get("lastEval").asText()) : null, n.path("note").asText(""));
        }
    }

    public enum Action {
        /** Nothing to do. */
        NONE,
        /** The condition holds and the alert was armed: tell the person. */
        FIRE,
        /** The condition holds but the cooldown since the last firing is not over: nothing is sent yet. */
        HELD,
        /** An active alert is back inside the limit by the hysteresis: armed again. */
        CLEAR,
        /** The reading could not be judged ({@code why} says so). */
        SKIP
    }

    /**
     * @param value the number read now (NaN when none)
     * @param reference the number compared with for a change, or null
     * @param delta the change (percent or absolute, signed) for a change alert, else NaN
     * @param why for {@link Action#SKIP} and {@link Action#HELD}: the reason in words
     */
    public record Outcome(Action action, double value, Double reference, double delta, String why) {}

    /**
     * @param reference the earlier reading for a change alert (null for the others, or when there is none yet)
     * @param pastDeadline for a "no data" alert: the time of day it waits for has passed on a business day
     */
    public static Outcome decide(AlertSpec spec, Reading now, Reading reference, State state, Instant at, boolean pastDeadline) {
        AlertSpec.Condition c = spec.condition();
        boolean noData = c.type().equals("nodata");
        if (noData) {
            if (now.status() == Reading.Status.ERROR || now.status() == Reading.Status.MASKED) {
                return skip(now);
            }
            boolean breach = pastDeadline && now.status() == Reading.Status.NO_DATA;
            boolean clear = now.status() == Reading.Status.OK;
            return step(spec, state, at, breach, clear, Double.NaN, null, Double.NaN);
        }
        if (!now.isOk()) {
            return skip(now);
        }
        double v = now.value();
        double h = spec.hysteresis();
        return switch (c.type()) {
            case "threshold" -> {
                boolean breach = switch (c.op()) {
                    case "above" -> v > c.value();
                    case "below" -> v < c.value();
                    case "between" -> v >= c.value() && v <= c.high();
                    default -> v < c.value() || v > c.high();
                };
                boolean clear = switch (c.op()) {
                    case "above" -> v <= c.value() - h;
                    case "below" -> v >= c.value() + h;
                    case "between" -> v < c.value() - h || v > c.high() + h;
                    default -> v >= c.value() + h && v <= c.high() - h;
                };
                yield step(spec, state, at, breach, clear, v, null, Double.NaN);
            }
            case "change" -> {
                if (reference == null || !reference.isOk()) {
                    yield new Outcome(Action.SKIP, v, null, Double.NaN, "there is no earlier number to compare with yet");
                }
                double ref = reference.value();
                double delta = c.mode().equals("pct") ? pct(v, ref) : v - ref;
                if (Double.isNaN(delta)) {
                    yield new Outcome(Action.SKIP, v, ref, Double.NaN, "the earlier number is zero, so a percentage change cannot be told");
                }
                boolean breach = switch (c.op()) {
                    case "rises" -> delta >= c.value();
                    case "falls" -> delta <= -c.value();
                    default -> Math.abs(delta) >= c.value();
                };
                boolean clear = switch (c.op()) {
                    case "rises" -> delta < c.value() - h;
                    case "falls" -> delta > -c.value() + h;
                    default -> Math.abs(delta) < c.value() - h;
                };
                yield step(spec, state, at, breach, clear, v, ref, delta);
            }
            default -> {                                                       // level: a board tile's level (0 none, 1 warn, 2 crit)
                double need = c.level().equals("crit") ? 2 : 1;
                yield step(spec, state, at, v >= need, v < need, v, null, Double.NaN);
            }
        };
    }

    /** Percent change from {@code ref} to {@code v}; NaN when {@code ref} is zero and {@code v} is not (undefined), 0 when both are. */
    static double pct(double v, double ref) {
        if (ref == 0) {
            return v == 0 ? 0 : Double.NaN;
        }
        return (v - ref) / Math.abs(ref) * 100.0;
    }

    private static Outcome skip(Reading r) {
        String why = switch (r.status()) {
            case MASKED -> "masked for you, so it cannot be judged" + (r.note().isBlank() ? "" : " (" + r.note() + ")");
            case NO_DATA -> "no data yet" + (r.note().isBlank() ? "" : " (" + r.note() + ")");
            default -> "could not be read: " + r.note();
        };
        return new Outcome(Action.SKIP, Double.NaN, null, Double.NaN, why);
    }

    private static Outcome step(AlertSpec spec, State state, Instant at, boolean breach, boolean clear, double v, Double ref, double delta) {
        if (state.active()) {
            return new Outcome(clear ? Action.CLEAR : Action.NONE, v, ref, delta, "");
        }
        if (!breach) {
            return new Outcome(Action.NONE, v, ref, delta, "");
        }
        boolean cooled = state.firedAt() == null || !at.isBefore(state.firedAt().plus(spec.cooldown()));
        return cooled ? new Outcome(Action.FIRE, v, ref, delta, "")
                : new Outcome(Action.HELD, v, ref, delta, "held back by the cooldown until " + state.firedAt().plus(spec.cooldown()));
    }
}
