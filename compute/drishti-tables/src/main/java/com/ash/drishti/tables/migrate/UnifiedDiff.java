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

import java.util.ArrayList;
import java.util.List;

/**
 * A unified diff of two texts, as {@code diff -u} prints it, with three lines of context: what {@code tables migrate --dry-run} shows per
 * file and per stored item. Definitions are small (hundreds of lines), so the longest common subsequence is computed directly.
 */
public final class UnifiedDiff {

    private static final int CONTEXT = 3;
    private static final int MAX_LINES = 20_000;

    private UnifiedDiff() {}

    /** The diff of {@code before} (null: a new file) and {@code after}, labelled; empty when they are equal. */
    public static String of(String label, String before, String after) {
        List<String> a = before == null ? List.of() : before.lines().toList();
        List<String> b = after == null ? List.of() : after.lines().toList();
        if (a.equals(b)) {
            return "";
        }
        if (a.size() > MAX_LINES || b.size() > MAX_LINES) {
            return "--- " + label + "\n+++ " + label + "\n(too large to show: " + a.size() + " lines before, " + b.size() + " after)\n";
        }
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String[]> ops = new ArrayList<>();                            // {op, text, aLine, bLine}
        int i = 0;
        int j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                ops.add(new String[] {" ", a.get(i), String.valueOf(i), String.valueOf(j)});
                i++;
                j++;
            } else if (j < b.size() && (i >= a.size() || lcs[i][j + 1] >= lcs[i + 1][j])) {
                ops.add(new String[] {"+", b.get(j), String.valueOf(i), String.valueOf(j)});
                j++;
            } else {
                ops.add(new String[] {"-", a.get(i), String.valueOf(i), String.valueOf(j)});
                i++;
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(before == null ? "/dev/null" : "a/" + label).append('\n');
        out.append("+++ b/").append(label).append('\n');
        int k = 0;
        while (k < ops.size()) {
            if (ops.get(k)[0].equals(" ")) {
                k++;
                continue;
            }
            int start = Math.max(0, k - CONTEXT);
            int end = k;
            int lastChange = k;
            while (end < ops.size() && (end - lastChange) <= 2 * CONTEXT) {
                if (!ops.get(end)[0].equals(" ")) {
                    lastChange = end;
                }
                end++;
            }
            end = Math.min(ops.size(), lastChange + CONTEXT + 1);
            int aStart = Integer.parseInt(ops.get(start)[2]);
            int bStart = Integer.parseInt(ops.get(start)[3]);
            int aCount = 0;
            int bCount = 0;
            StringBuilder hunk = new StringBuilder();
            for (int x = start; x < end; x++) {
                String[] op = ops.get(x);
                hunk.append(op[0]).append(op[1]).append('\n');
                if (!op[0].equals("+")) {
                    aCount++;
                }
                if (!op[0].equals("-")) {
                    bCount++;
                }
            }
            out.append("@@ -").append(aCount == 0 ? aStart : aStart + 1).append(',').append(aCount).append(" +").append(bStart + 1).append(',').append(bCount)
                    .append(" @@\n").append(hunk);
            k = end;
        }
        return out.toString();
    }
}
