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
package com.ash.drishti.server.bi.layer;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Rewrites a dataset's row rule (or {@code where:} filter) for the table it becomes inside a model: the dataset calls itself by its own name
 * ({@code pack.trading.trade.desk}, {@code trade.desk}) or names a column bare ({@code desk}); in the model the table is the alias ({@code trades.desk}).
 * String literals, {@code user.*} attributes, function names and keywords are left alone. The rule was checked to name only its own columns, so the result
 * names only the alias.
 */
final class RuleText {

    private static final Set<String> WORDS = Set.of("in", "not", "and", "or", "true", "false", "null", "user");

    private RuleText() {}

    static String qualify(String text, String datasetName, Set<String> columns, String alias) {
        List<String> prefixes = java.util.stream.Stream.of(datasetName, datasetName.substring(datasetName.lastIndexOf('.') + 1)).distinct()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n && text.charAt(j) != c) {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(n, j + 1);
                out.append(text, i, j);
                i = j;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_' || text.charAt(j) == '.' || text.charAt(j) == '-'
                        && j + 1 < n && Character.isLetterOrDigit(text.charAt(j + 1)) && dotted(text, i, j))) {
                    j++;
                }
                String word = text.substring(i, j);
                out.append(rewrite(word, text, j, prefixes, columns, alias));
                i = j;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** A hyphen belongs to a name only inside a dotted dataset name ({@code pack.counterparty-risk.netting-set.x}), never as a minus between two words. */
    private static boolean dotted(String text, int from, int at) {
        return text.startsWith(DatasetDef.PACK_PREFIX, from) && at > from;
    }

    private static String rewrite(String word, String text, int end, List<String> prefixes, Set<String> columns, String alias) {
        if (word.startsWith("user.") || WORDS.contains(word.toLowerCase(java.util.Locale.ROOT))) {
            return word;
        }
        int k = end;
        while (k < text.length() && text.charAt(k) == ' ') {
            k++;
        }
        if (k < text.length() && text.charAt(k) == '(') {
            return word;                                                             // a function
        }
        for (String p : prefixes) {
            if (word.startsWith(p + ".")) {
                return alias.isEmpty() ? word.substring(p.length() + 1) : alias + word.substring(p.length());
            }
        }
        String first = word.contains(".") ? word.substring(0, word.indexOf('.')) : word;
        if (columns.contains(first) || DatasetDef.BUILT_IN.contains(first)) {
            return alias.isEmpty() ? word : alias + "." + word;
        }
        return word;
    }
}
