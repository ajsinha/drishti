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
package com.ash.drishti.rachana.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A screen's {@code phone:} block (MOBILE_DISPLAY.md section 5.2): the order of its must and glance panels on a phone, the tier of the
 * panels it does not name, and the one line shown at the top of the phone page.
 *
 * @param order the must and glance panels in phone order (ids); empty: the panels' own order
 * @param defaultTier the tier of panels the Sutra gives none ({@code must}, {@code glance}, {@code more}, {@code desk}), or null: the
 *     panel kind's default
 * @param summary the phone summary line, a template read against the document ({@code "Net MTM ${fmt($.NET_MTM, 'signed0')}"}), or null
 */
public record ScreenPhone(List<String> order, String defaultTier, String summary) {

    /** The four phone tiers, most shown first. */
    public static final List<String> TIERS = List.of("must", "glance", "more", "desk");

    /** The key on a panel, on a use, in a fragment's {@code doc:} and at the top of a Sutra. */
    public static final String KEY = "phone";

    /** The compact options of a glance panel, by the panel kinds each fits (MOBILE_DISPLAY.md section 5.3). */
    public static final Map<String, Set<String>> COMPACT = Map.of(
            "rows", Set.of("table", "ladder", "hbar", "timeline", "links", "map", "waterfall"),
            "columns", Set.of("table", "ladder"),
            "sort", Set.of("table", "ladder"),
            "series", Set.of("line", "area", "candlestick"),
            "figures", Set.of("kv", "status"),
            "tab", Set.of("tabs"));

    public ScreenPhone {
        order = order == null ? List.of() : List.copyOf(order);
    }

    /** The block as the view model carries it (the engine reads the summary against the document and adds it). */
    public Map<String, Object> toView() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!order.isEmpty()) {
            m.put("order", order);
        }
        if (defaultTier != null) {
            m.put("default", defaultTier);
        }
        return m;
    }
}
