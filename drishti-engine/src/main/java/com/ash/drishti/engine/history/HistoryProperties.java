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
package com.ash.drishti.engine.history;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * History queries' limits ({@code drishti.history.*}, HISTORY_QUERIES.md section 11). They follow a settings reload without a restart.
 *
 * @param enabled off: no history is read and the endpoint answers {@code DRS-1012}
 * @param defaultDays the range when a link or the CLI names none
 * @param maxDays the most business days one request may span (a pack's {@code max-days} can only lower it)
 * @param maxRows the most rows returned
 * @param maxColumns the most columns
 * @param timeout the whole request; days not read by then are problems and the answer is partial
 * @param parallelDays days read at once per request by the default per-day loop
 * @param maxConcurrent history requests running at once on this server; the next waits, then {@code DRS-1014}
 * @param cacheMb memory for cached answers
 */
@ConfigurationProperties("drishti.history")
public final class HistoryProperties implements com.ash.drishti.common.LiveSettings {

    /** One unit, swapped whole on a reload. */
    public record Limits(boolean enabled, int defaultDays, int maxDays, int maxRows, int maxColumns, Duration timeout, int parallelDays,
            int maxConcurrent, int cacheMb) {
        public Limits {
            maxDays = maxDays <= 0 ? 366 : maxDays;
            defaultDays = defaultDays <= 0 ? 30 : Math.min(defaultDays, maxDays);
            maxRows = maxRows <= 0 ? 1000 : maxRows;
            maxColumns = maxColumns <= 0 ? 200 : maxColumns;
            timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(10) : timeout;
            parallelDays = parallelDays <= 0 ? 8 : parallelDays;
            maxConcurrent = maxConcurrent <= 0 ? 16 : maxConcurrent;
            cacheMb = Math.max(0, cacheMb);
        }
    }

    private volatile Limits limits;

    public HistoryProperties(Boolean enabled, Integer defaultDays, Integer maxDays, Integer maxRows, Integer maxColumns, Duration timeout,
            Integer parallelDays, Integer maxConcurrent, Integer cacheMb) {
        this.limits = new Limits(enabled == null || enabled, n(defaultDays), n(maxDays), n(maxRows), n(maxColumns), timeout, n(parallelDays),
                n(maxConcurrent), cacheMb == null ? 256 : cacheMb);
    }

    /** The defaults of section 11. */
    public static HistoryProperties defaults() {
        return new HistoryProperties(null, null, null, null, null, null, null, null, null);
    }

    private static int n(Integer v) {
        return v == null ? 0 : v;
    }

    public Limits limits() {
        return limits;
    }

    @Override
    public Runnable prepare(org.springframework.core.env.Environment candidate) {
        HistoryProperties fresh = org.springframework.boot.context.properties.bind.Binder.get(candidate).bind("drishti.history", HistoryProperties.class)
                .orElseGet(HistoryProperties::defaults);
        Limits next = fresh.limits();
        return () -> limits = next;
    }
}
