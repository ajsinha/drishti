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

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a reader had on screen besides the data: the control values ({@code ctl.legs=2}) and the column filters
 * ({@code f.<panel>.<column>=gt:1m}), as the address carries them. Kept with a share so the recipient, the email and the picture
 * show what the sender saw. Only those two prefixes are ever kept; everything else in the text is dropped. Immutable.
 *
 * @param params the parameter names (with their prefix) to their decoded values, in the order written
 */
public record ViewState(Map<String, String> params) {

    /** The prefix of a column filter's parameter: {@code f.<panel>.<column>}. */
    public static final String FILTER_PREFIX = "f.";
    /** The longest parameter name kept. */
    public static final int MAX_KEY = 200;
    /** No state: the Sutra's defaults and no filters. */
    public static final ViewState NONE = new ViewState(Map.of());

    public ViewState {
        params = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /**
     * Reads query text ({@code ctl.legs=2&f.cashflows.rate=gt%3A1m}, with or without a leading {@code ?}); anything that is not a control
     * or a filter is dropped, a repeated name keeps its last value.
     *
     * @throws IllegalArgumentException when the kept text would be longer than {@code maxLength} characters
     */
    public static ViewState parse(String query, int maxLength) {
        if (query == null || query.isBlank()) {
            return NONE;
        }
        String q = query.strip();
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : (q.startsWith("?") ? q.substring(1) : q).split("&")) {
            int eq = pair.indexOf('=');
            String k = decode(eq < 0 ? pair : pair.substring(0, eq));
            String v = eq < 0 ? "" : decode(pair.substring(eq + 1));
            boolean control = k.startsWith(ControlValues.PREFIX) && k.length() > ControlValues.PREFIX.length() && k.length() <= 40;
            boolean filter = k.startsWith(FILTER_PREFIX) && k.indexOf('.', FILTER_PREFIX.length()) > FILTER_PREFIX.length() && k.length() <= MAX_KEY;
            if ((control || filter) && v.length() <= ControlValues.MAX_VALUE) {
                out.put(k, v);
            }
        }
        ViewState s = out.isEmpty() ? NONE : new ViewState(out);
        if (!s.isEmpty() && s.text().length() > maxLength) {
            throw new IllegalArgumentException("the view state is " + s.text().length() + " characters; the limit is " + maxLength);
        }
        return s;
    }

    public boolean isEmpty() {
        return params.isEmpty();
    }

    /** The canonical query text (names and values percent-encoded), or {@code null} when there is none. */
    public String text() {
        if (params.isEmpty()) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        params.forEach((k, v) -> b.append(b.length() > 0 ? "&" : "").append(encode(k)).append('=').append(encode(v)));
        return b.toString();
    }

    /** The control values, as a request would carry them. */
    public ControlValues controls() {
        Map<String, String[]> request = new LinkedHashMap<>();
        params.forEach((k, v) -> {
            if (k.startsWith(ControlValues.PREFIX)) {
                request.put(k, new String[] {v});
            }
        });
        return ControlValues.of(request);
    }

    /** The column filters of one panel: column heading to the filter in its address form ({@code in:a,b}, {@code gt:1m}). */
    public Map<String, String> filtersOf(String panel) {
        Map<String, String> out = new LinkedHashMap<>();
        String lead = FILTER_PREFIX + panel + ".";
        params.forEach((k, v) -> {
            if (k.startsWith(lead) && k.length() > lead.length()) {
                out.put(k.substring(lead.length()), v);
            }
        });
        return out;
    }

    /** Whether any column filter is present. */
    public boolean hasFilters() {
        return params.keySet().stream().anyMatch(k -> k.startsWith(FILTER_PREFIX));
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
