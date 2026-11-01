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
package com.ash.drishti.server.var;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The stress window selection (VAR_GUIDE.md section 18.9): which 12-month period of a long P&amp;L history is worst for today's
 * portfolio.
 *
 * @param enabled the tool and its API are on (off: every request is refused in words)
 * @param kind the kind of the long-history P&amp;L rows, one per hierarchy node and date ({@code pnl-history})
 * @param length the days of a window (250: about 12 months); a request may ask for another between {@link #MIN_LENGTH} and the history
 * @param top the candidate windows listed, none overlapping another (5)
 * @param alertThreshold the alert is raised when the worst window's stressed VaR exceeds the current window's by more than this fraction
 *     (0.05: 5%)
 */
@ConfigurationProperties("drishti.var.stress-window")
public record StressWindowProperties(Boolean enabled, String kind, Integer length, Integer top, Double alertThreshold) {

    /** The shortest window a request may ask for. */
    public static final int MIN_LENGTH = 20;

    public StressWindowProperties {
        enabled = enabled == null || enabled;
        kind = kind == null || kind.isBlank() ? "pnl-history" : kind.strip();
        length = length == null || length < MIN_LENGTH ? 250 : length;
        top = top == null || top < 1 ? 5 : Math.min(top, 20);
        alertThreshold = alertThreshold == null || alertThreshold < 0 ? 0.05 : alertThreshold;
    }
}
