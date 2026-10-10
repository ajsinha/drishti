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
package com.ash.drishti.engine.control;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a request says about a view's controls: the values the reader set ({@code ctl.leg=L2}, a multi-select's choices joined
 * with commas) and, optionally, the only panels to build (a control changed: only the panels that read it are rebuilt). Immutable.
 *
 * @param raw control id to the text the request carried
 * @param only the panels to build; empty builds all of them
 */
public record ControlValues(Map<String, String> raw, Set<String> only) {

    /** The prefix of a request parameter that carries a control's value. */
    public static final String PREFIX = "ctl.";
    /** No values and every panel: the Sutra's defaults. */
    public static final ControlValues NONE = new ControlValues(Map.of(), Set.of());
    /** The longest value a request may carry for one control (the rest is dropped). */
    public static final int MAX_VALUE = 2_000;
    /** The most controls a request carries values for. */
    public static final int MAX_CONTROLS = 32;

    public ControlValues {
        raw = Map.copyOf(raw);
        only = Set.copyOf(only);
    }

    /** Reads {@code ctl.<id>=value} parameters (and a comma-separated {@code panels} list) from a request's parameters. */
    public static ControlValues of(Map<String, String[]> params) {
        Map<String, String> raw = new LinkedHashMap<>();
        Set<String> only = new LinkedHashSet<>();
        params.forEach((k, v) -> {
            if (v == null || v.length == 0 || raw.size() >= MAX_CONTROLS) {
                return;
            }
            if (k.startsWith(PREFIX) && k.length() > PREFIX.length() && k.length() <= 40) {
                String joined = String.join(",", v);
                raw.put(k.substring(PREFIX.length()), joined.length() > MAX_VALUE ? joined.substring(0, MAX_VALUE) : joined);
            } else if ("panels".equals(k)) {
                for (String id : String.join(",", v).split(",")) {
                    if (!id.isBlank() && only.size() < 64) {
                        only.add(id.trim());
                    }
                }
            }
        });
        return raw.isEmpty() && only.isEmpty() ? NONE : new ControlValues(raw, only);
    }

    /** The text the request carried for a control, or null. */
    public String get(String id) {
        return raw.get(id);
    }

    /** Whether panel {@code id} is to be built. */
    public boolean builds(String id) {
        return only.isEmpty() || only.contains(id);
    }

    /** The values as request parameters ({@code ctl.leg=L2}), for a link or a stream. */
    public Map<String, String> asParams() {
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(PREFIX + k, v));
        return out;
    }
}
