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
package com.ash.drishti.bicompute.rachana;

import com.ash.drishti.rachana.el.Functions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one function catalogue page (Help → Functions; ONE_GRAMMAR.md section 5), generated from the two registries: Rachana's function
 * table ({@link Functions#catalogue()}) and the pivot engine's functions registered into it ({@link EngineFunctions}). Written to
 * {@code docs/guides/FUNCTIONS.md}; {@code FunctionCataloguePageTest} fails when the committed page differs from what the registries
 * give (regenerate it with {@code -Ddrishti.functions.write=true} on that test, or by running this class with the file's path), and
 * when a function is missing from FUNCTION_CATALOGUE.md, the reviewed catalogue with each function's example and Excel and SAS names.
 */
public final class FunctionCataloguePage {

    /** The page, from the repository's root. */
    public static final String FILE = "docs/guides/FUNCTIONS.md";

    static final Map<String, String> FAMILIES = new LinkedHashMap<>();

    static {
        FAMILIES.put("aggregates", "Aggregates: the pivot engine's (many values to one; a pivot cell runs the same function)");
        FAMILIES.put("math", "Math (scalars)");
        FAMILIES.put("logic", "Logic (scalars)");
        FAMILIES.put("text", "Text (scalars)");
        FAMILIES.put("dates", "Dates, on the bank's calendars (scalars)");
        FAMILIES.put("lists", "Lists (Excel's dynamic arrays, over [*])");
    }

    private FunctionCataloguePage() {}

    /** The page's text, under {@code header} (the repository's licence header, as the reviewed catalogue carries it: {@link #header}). */
    public static String markdown(String header) {
        List<Functions.Entry> all = Functions.catalogue();
        StringBuilder b = new StringBuilder();
        b.append(header);
        b.append("# Functions\n\n");
        b.append("*Generated from the registries (Rachana's function table and the pivot engine's functions registered into it) by ")
                .append("`FunctionCataloguePage`; do not edit by hand. ").append(all.size()).append(" functions, each under its one name. ")
                .append("Each function's example, its Excel and SAS equivalents and its rules are in ")
                .append("[FUNCTION_CATALOGUE.md](../architecture/FUNCTION_CATALOGUE.md); the grammar is in ")
                .append("[RACHANA_REFERENCE.md](RACHANA_REFERENCE.md#functions).*\n\n");
        b.append("**Runs:** **R** Rachana (a scalar, one value from values); **E** the pivot engine (`drishti-bicompute`), the same ")
                .append("implementation a pivot cell runs. Where the engine is not on the class path an **E** function is refused with ")
                .append("the reason, never computed another way. **Form:** *mergeable* (a total combines partial results exactly), ")
                .append("*holistic* (it needs the values), *measure* (a figure from its own engine, never summed).\n\n");
        for (Map.Entry<String, String> family : FAMILIES.entrySet()) {
            List<Functions.Entry> rows = all.stream().filter(e -> family.getKey().equals(e.family())).toList();
            if (rows.isEmpty()) {
                continue;
            }
            b.append("## ").append(family.getValue()).append("\n\n");
            if ("aggregates".equals(family.getKey())) {
                b.append("| Function | Arguments | Form | Means | Runs | Source |\n|---|---|---|---|---|---|\n");
                for (Functions.Entry e : rows) {
                    b.append("| `").append(e.name()).append("` | ").append(cell(e.args())).append(" | ").append(cell(e.form())).append(" | ")
                            .append(cell(e.description())).append(" | ").append(e.runs()).append(" | ").append(e.source()).append(" |\n");
                }
            } else {
                b.append("| Function | Runs | Source |\n|---|---|---|\n");
                for (Functions.Entry e : rows) {
                    b.append("| `").append(e.name()).append("` | ").append(e.runs()).append(" | ").append(e.source()).append(" |\n");
                }
            }
            b.append('\n');
        }
        b.append("## Old spellings (read for existing Sutras, never offered)\n\n| Old | Write instead |\n|---|---|\n");
        Functions.oldSpellings().forEach((old, one) -> b.append("| `").append(old).append("` | `").append(one).append("` |\n"));
        return b.toString();
    }

    private static String cell(String s) {
        return s == null || s.isEmpty() ? "" : s.replace("|", "\\|");
    }

    /** The reviewed catalogue, from the repository's root. */
    public static final String CATALOGUE = "docs/architecture/FUNCTION_CATALOGUE.md";

    /** The licence header of a Markdown document: its leading HTML comment, with the blank line after it. */
    public static String header(Path document) throws IOException {
        String text = Files.readString(document, StandardCharsets.UTF_8);
        int end = text.indexOf("-->");
        if (!text.startsWith("<!--") || end < 0) {
            throw new IllegalStateException(document + " has no licence header");
        }
        return text.substring(0, end + 3) + "\n";
    }

    /** Writes the page to the path given (the repository's {@value #FILE}), run from the repository's root. */
    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : FILE);
        Files.writeString(out, markdown(header(Path.of(CATALOGUE))), StandardCharsets.UTF_8);
    }
}
