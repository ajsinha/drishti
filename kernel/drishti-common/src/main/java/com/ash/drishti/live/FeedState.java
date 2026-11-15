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
package com.ash.drishti.live;

/**
 * One state per live feed, the same words everywhere: Health, Admin, the CLI and a page's live mark
 * (docs/architecture/LIVE_CONNECTORS_DESIGN.md section 7.9). {@link #attention} is how Health counts it: {@code ok}, {@code amber} or
 * {@code fault}.
 */
public enum FeedState {

    /** Switched off. */
    OFF("OFF", "ok"),
    /** On, but a setting it cannot run without is missing. */
    NOT_CONFIGURED("NOT CONFIGURED", "ok"),
    /** On, the broker is not reachable: live updates paused, the lake's rows shown. */
    WAITING("WAITING", "ok"),
    /** Connected, reading what the broker holds. */
    CATCHING_UP("CATCHING UP", "ok"),
    /** Applying messages; a feed that is simply quiet included. */
    LIVE("LIVE", "ok"),
    /** No message for longer than its {@code stale-after}. */
    STALE("STALE", "amber"),
    /** Rejects over the ratio, unabsorbed rows expired, or messages the state could not keep. */
    DEGRADED("DEGRADED", "amber"),
    /** An administrator paused it. */
    PAUSED("PAUSED", "ok"),
    /** Another node owns it. */
    STANDBY("STANDBY", "ok"),
    /** The broker answers but the feed fails (a refused log-in, TLS); retried. */
    DOWN("DOWN", "fault");

    private final String words;
    private final String attention;

    FeedState(String words, String attention) {
        this.words = words;
        this.attention = attention;
    }

    /** The state as every screen writes it. */
    public String words() {
        return words;
    }

    /** {@code ok}, {@code amber} or {@code fault}. */
    public String attention() {
        return attention;
    }
}
