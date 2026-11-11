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
package com.ash.drishti.server.bi.dataset;

import com.ash.drishti.server.dist.SubscriptionSchedule;
import java.util.Locale;

/**
 * Reads the {@code refresh:} of a dataset or of a table. The vocabulary is the one subscriptions use ({@link SubscriptionSchedule}): {@code daily 06:00}
 * (also written {@code daily at 06:00}), {@code weekdays 18:30}, {@code business-days 07:30} (the pack's holiday calendar), {@code weekly mon 07:00},
 * {@code monthly last 18:00 business}, {@code hourly}, {@code cron <six fields>}, a bare five-field cron (minute, hour, day, month, weekday), and
 * {@code after-load} (optionally followed by a kind).
 */
public final class RefreshSpec {

    private RefreshSpec() {}

    /** The schedule as {@link SubscriptionSchedule} reads it: {@code "daily at 06:00"} loses its "at", a five-field cron gets a seconds field. */
    public static String normalise(String raw) {
        String t = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            return t;
        }
        String low = t.toLowerCase(Locale.ROOT);
        for (String word : new String[] {"daily at ", "weekdays at ", "business-days at ", "business days at ", "business days "}) {
            if (low.startsWith(word)) {
                String rest = t.substring(word.length());
                String head = word.startsWith("business") ? "business-days" : word.split(" ")[0];
                return head + " " + rest;
            }
        }
        if (t.split(" ").length == 5 && t.matches("[-0-9*/,A-Za-z? ]+") && !low.startsWith("cron")) {
            return "cron 0 " + t;
        }
        return t;
    }

    /** Parses the text; a mistake throws {@link IllegalArgumentException} saying what is accepted. */
    public static SubscriptionSchedule parse(String raw) {
        return SubscriptionSchedule.parse(normalise(raw));
    }
}
