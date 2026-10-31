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
package com.ash.drishti.server.bi.expr;

import java.util.List;
import java.util.Map;

/**
 * The function reference of docs/guides/BI_LANGUAGE_REFERENCE.md, generated from the catalogue so the guide never drifts from what the server accepts (a test compares
 * the guide's section between {@link #BEGIN} and {@link #END} with {@link #render}). One table of contents, then per family each function with its
 * signature, scopes, return type, status, description, examples and edge cases.
 */
public final class FunctionDocs {

    public static final String BEGIN = "<!-- functions:begin (generated from drishti-server/src/main/resources/bi/expr/functions.yaml; do not edit by hand) -->";
    public static final String END = "<!-- functions:end -->";

    private static final Map<String, String> EDGE_TITLES = Map.of("nulls", "None", "empty", "No rows", "masked", "Masked values", "currencies", "Currencies", "types",
            "Types");
    private static final List<String> EDGE_ORDER = List.of("nulls", "empty", "masked", "currencies", "types");

    private FunctionDocs() {}

    public static String render(FunctionCatalog cat) {
        StringBuilder b = new StringBuilder();
        b.append(BEGIN).append("\n\n");
        b.append("| Family | Functions |\n|---|---|\n");
        for (String family : cat.families()) {
            b.append("| ").append(family).append(" | ");
            List<String> names = cat.all().stream().filter(f -> f.family().equals(family)).map(f -> "[`" + f.name() + "`](#fn-" + anchor(f.name()) + ")"
                    + (f.built() ? "" : " *(" + f.status() + ")*")).toList();
            b.append(String.join(", ", names)).append(" |\n");
        }
        for (String family : cat.families()) {
            b.append("\n### ").append(capital(family)).append("\n");
            for (FunctionCatalog.Fn f : cat.all()) {
                if (!f.family().equals(family)) {
                    continue;
                }
                b.append("\n<a id=\"fn-").append(anchor(f.name())).append("\"></a>\n#### `").append(f.signature()).append("`\n\n");
                b.append("*Scopes: ").append(String.join(", ", f.scopes())).append(" · returns: ").append(returns(f)).append(" · since ").append(f.since());
                if (f.legacy() != null) {
                    b.append(" · old syntax: `").append(f.legacy()).append("`");
                }
                b.append("*");
                if (!f.built()) {
                    b.append(" — **arrives in wave ").append(f.status()).append("**: refused with `DRS-9129` until then");
                }
                b.append("\n\n").append(f.description()).append("\n");
                if (!f.params().isEmpty()) {
                    b.append("\n| Parameter | Type | Default | Notes |\n|---|---|---|---|\n");
                    for (FunctionCatalog.Param p : f.params()) {
                        b.append("| `").append(p.name()).append("` | ").append(p.type()).append(" | ").append(p.defaultText() == null ? (p.variadic() ? "—" : "required")
                                : "`" + p.defaultText() + "`").append(" | ").append(p.keywordOnly() ? "keyword only: `" + p.name() + "=...`" : p.variadic() ? "one or more" : "")
                                .append(" |\n");
                    }
                }
                b.append("\n| Example | What it says |\n|---|---|\n");
                for (FunctionCatalog.Example e : f.examples()) {
                    b.append("| `").append(e.expr().replace("|", "\\|")).append("` | ").append(e.says().replace("|", "\\|")).append(" |\n");
                }
                if (!f.edgeCases().isEmpty()) {
                    b.append("\n");
                    for (String k : EDGE_ORDER) {
                        String v = f.edgeCases().get(k);
                        if (v != null) {
                            b.append("* **").append(EDGE_TITLES.get(k)).append(":** ").append(v).append("\n");
                        }
                    }
                }
            }
        }
        b.append("\n").append(END);
        return b.toString();
    }

    private static String returns(FunctionCatalog.Fn f) {
        if ("same".equals(f.returns())) {
            return f.params().isEmpty() ? "any" : "the type of `" + f.params().get(0).name() + "`";
        }
        return f.returns();
    }

    static String anchor(String name) {
        return name.replace('_', '-');
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The guide with its generated section replaced by {@code section}; null when the guide has no markers. */
    public static String splice(String guide, String section) {
        int i = guide.indexOf(BEGIN);
        int j = guide.indexOf(END);
        if (i < 0 || j < i) {
            return null;
        }
        return guide.substring(0, i) + section + guide.substring(j + END.length());
    }

    /** The generated section of a guide; null when it has none. */
    public static String section(String guide) {
        int i = guide.indexOf(BEGIN);
        int j = guide.indexOf(END);
        return i < 0 || j < i ? null : guide.substring(i, j + END.length());
    }
}
