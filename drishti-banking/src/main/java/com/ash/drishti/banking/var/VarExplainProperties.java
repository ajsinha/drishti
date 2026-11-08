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
package com.ash.drishti.banking.var;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Why VaR moved (VAR_GUIDE.md section 3.11; docs/lenses/README.md section 4.1, RO1): the settings of the explanation of a VaR change between
 * two business dates.
 *
 * @param enabled the explanation, its API, its tab and its workbook are on (off: refused in words)
 * @param order the sequence of the causes, each a full VaR state after the one before: {@code restatement, hierarchy, trades-out,
 *     window, market, trades-in} (the default). Restatement and trades out come before the market (they have only the earlier date's
 *     vectors), trades in after it (only the later date's), the window next to the market; hierarchy and rules anywhere
 * @param windowLines {@code two} (the oldest scenario days dropped and the newest added, two lines) or {@code one} (one window-roll
 *     line, the two summed)
 * @param splitAmendments amended trades as their own lines (old terms out, new terms in) when the trade feed marks them
 *     ({@code TRADE_ACTION} or {@code TRADE_STATUS} AMEND or AMENDED on the later date); off, or not marked: in the market step
 * @param top the contributors listed per step before "the others and the interaction" (10)
 * @param driversMax the most trades of one step whose incremental VaR is computed (2,000: the largest by their share of the state's
 *     tail; the rest are in "the others")
 * @param cacheMinutes answers kept this long (10), never stored
 * @param cacheMb the most memory the kept answers take (32 MB, weighed in bytes)
 * @param matrixMax the most child nodes the Matrix view explains (60)
 */
@ConfigurationProperties("drishti.var.explain")
public record VarExplainProperties(Boolean enabled, List<String> order, String windowLines, Boolean splitAmendments, Integer top,
        Integer driversMax, Integer cacheMinutes, Integer cacheMb, Integer matrixMax) {

    /** The default order of the causes. */
    public static final List<String> DEFAULT_ORDER = List.of("restatement", "hierarchy", "trades-out", "window", "market", "trades-in");

    public VarExplainProperties {
        enabled = enabled == null || enabled;
        order = order == null || order.isEmpty() ? DEFAULT_ORDER : normalise(order);
        windowLines = windowLines == null || windowLines.isBlank() ? "two" : windowLines.strip().toLowerCase(Locale.ROOT);
        if (!windowLines.equals("two") && !windowLines.equals("one")) {
            throw new IllegalArgumentException("drishti.var.explain.window-lines is two or one, not " + windowLines);
        }
        splitAmendments = splitAmendments == null || splitAmendments;
        top = top == null || top < 1 ? 10 : Math.min(top, 100);
        driversMax = driversMax == null || driversMax < 10 ? 2000 : driversMax;
        cacheMinutes = cacheMinutes == null || cacheMinutes < 0 ? 10 : cacheMinutes;
        cacheMb = cacheMb == null || cacheMb < 1 ? 32 : cacheMb;
        matrixMax = matrixMax == null || matrixMax < 1 ? 60 : matrixMax;
        VarExplainMath.plan(VarExplainMath.causes(order), false, false);   // a bad order refuses the start, in words
    }

    /** The defaults (tests). */
    static VarExplainProperties defaults() {
        return new VarExplainProperties(null, null, null, null, null, null, null, null, null);
    }

    private static List<String> normalise(List<String> order) {
        List<String> out = new ArrayList<>();
        for (String s : order) {
            for (String part : String.valueOf(s).split(",")) {
                String t = part.strip().toLowerCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return List.copyOf(out);
    }
}
