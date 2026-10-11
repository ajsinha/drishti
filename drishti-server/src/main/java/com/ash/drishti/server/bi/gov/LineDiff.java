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
package com.ash.drishti.server.bi.gov;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A side-by-side line diff of two YAML texts (docs/guides/BI_GOVERNANCE.md): the rows the version diff and the review page draw. Lines are compared
 * as they are; the common head and tail are skipped, the middle is matched by the longest common subsequence (when it is small enough; a very large
 * middle is shown as one changed block). A run of removed lines followed by a run of added lines is paired as {@code changed}.
 */
public final class LineDiff {

    /** Largest middle (lines left x lines right) matched exactly; beyond it the middle is one changed block. */
    static final long MAX_CELLS = 4_000_000L;

    /**
     * One row.
     *
     * @param op {@code same}, {@code added}, {@code removed} or {@code changed}
     * @param left the line on the left (null when added)
     * @param right the line on the right (null when removed)
     * @param leftNo the left line number from 1 (0 when none)
     * @param rightNo the right line number from 1 (0 when none)
     */
    public record Row(String op, String left, String right, int leftNo, int rightNo) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("op", op);
            m.put("left", left);
            m.put("right", right);
            m.put("leftNo", leftNo);
            m.put("rightNo", rightNo);
            return m;
        }
    }

    /** The rows and the counts of what changed. */
    public record Result(List<Row> rows, int added, int removed, int changed) {

        public boolean identical() {
            return added == 0 && removed == 0 && changed == 0;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("identical", identical());
            m.put("added", added);
            m.put("removed", removed);
            m.put("changed", changed);
            m.put("rows", rows.stream().map(Row::toMap).toList());
            return m;
        }
    }

    private LineDiff() {}

    public static Result diff(String left, String right) {
        String[] a = lines(left);
        String[] b = lines(right);
        int head = 0;
        while (head < a.length && head < b.length && a[head].equals(b[head])) {
            head++;
        }
        int tail = 0;
        while (tail < a.length - head && tail < b.length - head && a[a.length - 1 - tail].equals(b[b.length - 1 - tail])) {
            tail++;
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < head; i++) {
            rows.add(new Row("same", a[i], b[i], i + 1, i + 1));
        }
        int n = a.length - head - tail;
        int m = b.length - head - tail;
        List<int[]> script = n == 0 || m == 0 || (long) n * m > MAX_CELLS ? coarse(n, m) : lcs(a, b, head, n, m);
        // script entries: {0 same | 1 removed | 2 added, indexLeft, indexRight} relative to the middle
        int i = 0;
        while (i < script.size()) {
            int[] s = script.get(i);
            if (s[0] == 0) {
                rows.add(new Row("same", a[head + s[1]], b[head + s[2]], head + s[1] + 1, head + s[2] + 1));
                i++;
                continue;
            }
            List<int[]> dels = new ArrayList<>();
            List<int[]> adds = new ArrayList<>();
            while (i < script.size() && script.get(i)[0] != 0) {
                (script.get(i)[0] == 1 ? dels : adds).add(script.get(i));
                i++;
            }
            int pairs = Math.min(dels.size(), adds.size());
            for (int k = 0; k < pairs; k++) {
                rows.add(new Row("changed", a[head + dels.get(k)[1]], b[head + adds.get(k)[2]], head + dels.get(k)[1] + 1, head + adds.get(k)[2] + 1));
            }
            for (int k = pairs; k < dels.size(); k++) {
                rows.add(new Row("removed", a[head + dels.get(k)[1]], null, head + dels.get(k)[1] + 1, 0));
            }
            for (int k = pairs; k < adds.size(); k++) {
                rows.add(new Row("added", null, b[head + adds.get(k)[2]], 0, head + adds.get(k)[2] + 1));
            }
        }
        for (int k = 0; k < tail; k++) {
            int la = a.length - tail + k;
            int lb = b.length - tail + k;
            rows.add(new Row("same", a[la], b[lb], la + 1, lb + 1));
        }
        int added = 0;
        int removed = 0;
        int changed = 0;
        for (Row r : rows) {
            switch (r.op()) {
                case "added" -> added++;
                case "removed" -> removed++;
                case "changed" -> changed++;
                default -> {
                }
            }
        }
        return new Result(rows, added, removed, changed);
    }

    private static List<int[]> coarse(int n, int m) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new int[] {1, i, 0});
        }
        for (int j = 0; j < m; j++) {
            out.add(new int[] {2, 0, j});
        }
        return out;
    }

    private static List<int[]> lcs(String[] a, String[] b, int off, int n, int m) {
        int[][] t = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                t[i][j] = a[off + i].equals(b[off + j]) ? t[i + 1][j + 1] + 1 : Math.max(t[i + 1][j], t[i][j + 1]);
            }
        }
        List<int[]> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[off + i].equals(b[off + j])) {
                out.add(new int[] {0, i, j});
                i++;
                j++;
            } else if (t[i + 1][j] >= t[i][j + 1]) {
                out.add(new int[] {1, i++, 0});
            } else {
                out.add(new int[] {2, 0, j++});
            }
        }
        while (i < n) {
            out.add(new int[] {1, i++, 0});
        }
        while (j < m) {
            out.add(new int[] {2, 0, j++});
        }
        return out;
    }

    private static String[] lines(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        String t = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        return t.split("\n", -1);
    }
}
