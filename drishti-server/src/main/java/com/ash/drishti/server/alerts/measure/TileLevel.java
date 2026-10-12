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

import com.ash.drishti.server.bi.board.Board;
import java.util.List;

/**
 * The colour a board tile takes, as a number: {@code 0} none, {@code 1} warn (amber), {@code 2} crit (red). The rule is the viewer's (bi-live.js, {@code level}):
 * {@code crit} is tried first, then {@code warn}, and a visual with many rows takes the worst of them. Kept in step with the browser by a test that reads both.
 */
public final class TileLevel {

    private TileLevel() {}

    public static double rank(Board.Alert alert, List<Double> values) {
        if (alert == null) {
            return 0;
        }
        double level = 0;
        for (double v : values) {
            if (alert.crit() != null && test(alert.crit(), v)) {
                return 2;
            }
            if (alert.warn() != null && test(alert.warn(), v)) {
                level = 1;
            }
        }
        return level;
    }

    /**
     * The level the values would have if every threshold were {@code hysteresis} looser: the highest level a value near an edge could still be held at. A tile
     * that is red stays red until this drops below red, so a value hovering at the threshold does not flip the colour (same as a measure alert re-arming only
     * once the value is back inside the limit by its hysteresis). Equals {@link #rank} when the alert has none.
     */
    public static double relaxed(Board.Alert alert, List<Double> values) {
        if (alert == null) {
            return 0;
        }
        double h = alert.hysteresis();
        double level = 0;
        for (double v : values) {
            if (alert.crit() != null && test(alert.crit(), v, h)) {
                return 2;
            }
            if (alert.warn() != null && test(alert.warn(), v, h)) {
                level = 1;
            }
        }
        return level;
    }

    /** As {@link #rank}, for a tile that was at {@code previous}: it drops only as far as the relaxed thresholds allow. */
    public static double rank(Board.Alert alert, List<Double> values, double previous) {
        double strict = rank(alert, values);
        return Math.max(strict, Math.min(previous, relaxed(alert, values)));
    }

    static boolean test(Board.Rule r, double v) {
        return test(r, v, 0);
    }

    /** The rule with its threshold moved {@code h} the way that makes it easier to meet ({@code lt}/{@code le} up, {@code gt}/{@code ge} down; equality is unchanged). */
    static boolean test(Board.Rule r, double v, double h) {
        return switch (r.op()) {
            case "lt" -> v < r.value() + h;
            case "le" -> v <= r.value() + h;
            case "gt" -> v > r.value() - h;
            case "ge" -> v >= r.value() - h;
            case "eq" -> v == r.value();
            case "ne" -> v != r.value();
            default -> false;
        };
    }

    /** The word for a level in a message: red, amber or normal. */
    public static String word(double rank) {
        return rank >= 2 ? "red" : rank >= 1 ? "amber" : "normal";
    }
}
