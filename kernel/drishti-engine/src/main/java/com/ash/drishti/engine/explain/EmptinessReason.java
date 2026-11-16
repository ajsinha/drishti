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
package com.ash.drishti.engine.explain;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.NodeType;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.model.Panel;
import java.util.Optional;

/**
 * Why a panel that came out empty has nothing to show: it looks at the first document path the panel's data comes from
 * ({@code rows}, else the first column's expression) in the document as the caller sees it, so a masked field reads
 * "masked" and no value is ever read out.
 */
final class EmptinessReason {

    private EmptinessReason() {}

    /** @param why {@code missing}, {@code null}, {@code empty list}, {@code masked}, {@code no values} or {@code no data} */
    record Reason(String why, String path) {}

    private static final String[] DATA_OPTIONS = {"rows", "points", "bars", "events", "nodes", "value", "text", "x"};

    static Reason of(Panel panel, DataNode seen, ElCompiler el) {
        if (panel == null || panel.option("source").isPresent()) {
            return new Reason("no data", null);          // read from a linked entity: its shape is not this document's
        }
        String path = firstPath(panel, el);
        if (path == null) {
            return new Reason("no data", null);
        }
        DataNode n = seen.at(path);
        String why = n.isMasked() ? "masked" : n.isMissing() ? "missing" : n.isNull() ? "null"
                : n.size() == 0 && n.type() == NodeType.ARRAY ? "empty list" : "no values";
        return new Reason(why, path);
    }

    private static String firstPath(Panel p, ElCompiler el) {
        for (String o : DATA_OPTIONS) {
            Optional<String> expr = p.option(o);
            if (expr.isPresent()) {
                String path = first(expr.get(), el);
                if (path != null) {
                    return path;
                }
            }
        }
        for (var c : p.columns()) {
            String path = first(c.bind(), el);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private static String first(String expr, ElCompiler el) {
        String[] first = {null};
        try {
            el.compile(expr).paths(x -> {
                if (first[0] == null && !"$".equals(x)) {
                    first[0] = x;
                }
            });
        } catch (RuntimeException e) {
            return null;
        }
        return first[0];
    }
}
