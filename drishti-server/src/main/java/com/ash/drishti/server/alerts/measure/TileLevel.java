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

    static boolean test(Board.Rule r, double v) {
        return switch (r.op()) {
            case "lt" -> v < r.value();
            case "le" -> v <= r.value();
            case "gt" -> v > r.value();
            case "ge" -> v >= r.value();
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
