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
package com.ash.drishti.graph;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.el.ElException;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.rachana.format.Formats;
import java.util.Map;

/** Renders the short badge beside a link ({@code EE 4.1m}, {@code threshold 0}, {@code live}) from config. */
public final class BadgeRenderer {

    private final Map<String, String> badges;
    private final ElCompiler el;
    private final Formats formats;

    public BadgeRenderer(GraphProperties props, ElCompiler el, Formats formats) {
        this.badges = props.badges();
        this.el = el;
        this.formats = formats;
        badges.values().forEach(el::compile);
    }

    /** The badge for a target of {@code kind}; empty when none is configured or it evaluates to nothing. */
    public String badge(String kind, DataNode target) {
        String expr = badges.get(kind);
        if (expr == null) {
            return "";
        }
        try {
            return Values.text(el.compile(expr).eval(EvalContext.of(target, formats)));
        } catch (ElException e) {
            return "";
        }
    }
}
