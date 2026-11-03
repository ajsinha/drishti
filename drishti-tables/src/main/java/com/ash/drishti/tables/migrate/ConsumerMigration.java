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
package com.ash.drishti.tables.migrate;

import com.ash.drishti.tables.check.ExpressionChecker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The consumers of datasets and models in the migration (BI_FOUNDATION_2911E.md section 4.1, "Input"): reports, boards, alerts and
 * notebooks. Only what names a dataset or holds an expression changes; comments, key order and layout are kept (the file is rewritten line
 * by line, never re-serialised):
 *
 * <ul>
 *   <li>a binding {@code dataset: <name>} to a dataset that became a model (a {@code rachana: 1} dataset, Q19) is {@code model: <name>@<major>};</li>
 *   <li>every {@code expr:} (a report's or board's local measure) and every entry of a block {@code define:} (an MQ's measures) is translated
 *       as a measure (DEL into Rachana, date parts, the null rule) and written double-quoted.</li>
 * </ul>
 * Field names ({@code trades.MTM}, {@code trades.TRADE_DATE.month}) and MQ's operators are not expressions and do not change.
 */
final class ConsumerMigration {

    private static final Pattern BINDING = Pattern.compile("^dataset:\\s*([A-Za-z0-9_.-]+)\\s*(#.*)?$");
    private static final Pattern EXPR = Pattern.compile("(\\bexpr:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^']|'')*')");
    private static final Pattern DEFINE = Pattern.compile("^(\\s*)define:\\s*(#.*)?$");
    private static final Pattern ENTRY = Pattern.compile("^(\\s+)([^:#\\s][^:#]*?):\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^']|'')*'|[^#\\s][^#]*?)\\s*(#.*)?$");

    private final Translate translate;

    ConsumerMigration(Translate translate) {
        this.translate = translate;
    }

    /**
     * The file's new text (the same text when nothing changes).
     *
     * @param models dataset names that are models now, to their major
     * @param item the file, for the report
     */
    String rewrite(String text, Map<String, Integer> models, String item, List<TablesMigrator.Change> changes) {
        List<String> out = new ArrayList<>();
        String defineIndent = null;
        for (String line : text.split("\n", -1)) {
            Matcher b = BINDING.matcher(line);
            if (b.matches() && models.containsKey(b.group(1))) {
                String model = b.group(1) + "@" + models.get(b.group(1));
                changes.add(new TablesMigrator.Change(item, "dataset", b.group(1), "model: " + model, List.of("the dataset is a model of the layer (Q19)"), null));
                out.add("model: " + model + (b.group(2) == null ? "" : "   " + b.group(2)));
                continue;
            }
            Matcher d = DEFINE.matcher(line);
            if (d.matches()) {
                defineIndent = d.group(1);
                out.add(line);
                continue;
            }
            if (defineIndent != null) {
                Matcher e = ENTRY.matcher(line);
                if (e.matches() && e.group(1).length() > defineIndent.length()) {
                    String key = e.group(2).strip();
                    String del = unquote(e.group(3).strip());
                    String now = measure(del, item, "define." + key, changes);
                    out.add(e.group(1) + e.group(2) + ": " + now + (e.group(4) == null ? "" : "   " + e.group(4)));
                    continue;
                }
                if (!line.isBlank() && !line.strip().startsWith("#")) {
                    defineIndent = null;
                }
            }
            Matcher x = EXPR.matcher(line);
            StringBuilder sb = new StringBuilder();
            while (x.find()) {
                x.appendReplacement(sb, Matcher.quoteReplacement(x.group(1) + measure(unquote(x.group(2)), item, "expr", changes)));
            }
            x.appendTail(sb);
            out.add(sb.toString());
        }
        return String.join("\n", out);
    }

    private String measure(String del, String item, String key, List<TablesMigrator.Change> changes) {
        Translate.Out o = translate.model(del, ExpressionChecker.Scope.AGGREGATE, Set.of(), Set.of());
        if (o.refusal() != null && com.ash.drishti.tables.lang.Trees.parses(del)) {
            return quote(del);                                              // already Rachana (a second run): DEL does not read it, nothing to do
        }
        changes.add(new TablesMigrator.Change(item, key, del, o.text(), o.notes(), o.refusal()));
        return quote(o.text() == null ? del : o.text());
    }

    static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            return s.substring(1, s.length() - 1).replace("''", "'");
        }
        return s;
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
