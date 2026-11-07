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
package com.ash.drishti.server.trading;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The trader home's live ticks, {@code drishti.trading.home.*} (docs/architecture/SHARED_SCREENS.md section 4; USER_GUIDE.md, "The trader
 * home"). Every key has a default.
 *
 * @param watch the kinds whose data versions make the home tick (trades, valuations, VaR limits, P&amp;L vectors, the market data a trader
 *        watches): a new version of any of them is one {@code tick}
 * @param frame how often the data versions are compared (the shortest time between two ticks to one browser)
 * @param status how often a stream says it is still there when nothing changed (also its heartbeat)
 * @param maxStreamsPerUser live streams of the trader home one person may hold at once (each tab of the home is one, through the console's one channel)
 */
@ConfigurationProperties("drishti.trading.home")
public record TradingHomeProperties(List<String> watch, Duration frame, Duration status, Integer maxStreamsPerUser) {

    /** The kinds watched when none are named. */
    public static final List<String> WATCH = List.of("trade", "trade-valuation", "var-limit", "pnl-vector", "ir-curve", "fx-spot");

    public TradingHomeProperties {
        watch = watch == null || watch.isEmpty() ? WATCH : watch.stream().map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
        frame = positive(frame, Duration.ofSeconds(2));
        status = positive(status, Duration.ofSeconds(15));
        maxStreamsPerUser = maxStreamsPerUser == null || maxStreamsPerUser <= 0 ? 8 : maxStreamsPerUser;
    }

    private static Duration positive(Duration d, Duration dflt) {
        return d == null || d.isNegative() || d.isZero() ? dflt : d;
    }

    /** Defaults for tests and embedded use. */
    public static TradingHomeProperties defaults() {
        return new TradingHomeProperties(null, null, null, null);
    }
}
