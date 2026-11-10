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
package com.ash.drishti.banking.compute.var;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The stress window selection (VAR_GUIDE.md section 18.9): which run of {@code length} consecutive days of a long P&amp;L history of
 * today's portfolio gives the largest VaR. Pure arithmetic on a {@code double[]} (gains positive), the same rank rule as every VaR of the
 * engine ({@link VarMath}), so the window of the date's stress set gives exactly the stressed VaR of that set.
 *
 * <ul>
 *   <li>{@link #rolling}: the VaR of every window, by its first day's index (N − length + 1 windows).</li>
 *   <li>{@link #candidates}: the largest windows that do not overlap one another, the largest first (ties: the earlier start), so
 *       the list names different periods rather than the same crisis shifted by a day.</li>
 *   <li>{@link #worstDay}: the day of a window with the lowest P&amp;L (ties: the earlier day).</li>
 * </ul>
 */
public final class StressWindows {

    private StressWindows() {}

    /**
     * The VaR at {@code alpha} of every window of {@code length} consecutive values, indexed by its first value: {@code out[s]} is the
     * VaR of {@code pnl[s .. s + length - 1]}. Empty when the history is shorter than one window.
     */
    public static double[] rolling(double[] pnl, int length, double alpha, VarMath.RankRule rule) {
        if (length <= 0) {
            throw new IllegalArgumentException("a window has at least one day");
        }
        int windows = pnl.length - length + 1;
        if (windows <= 0) {
            return new double[0];
        }
        double[] out = new double[windows];
        double[] w = new double[length];
        for (int s = 0; s < windows; s++) {
            System.arraycopy(pnl, s, w, 0, length);
            out[s] = VarMath.var(w, alpha, rule);
        }
        return out;
    }

    /**
     * The first days of up to {@code top} windows with the largest VaR, no two of them overlapping (their starts at least {@code length}
     * apart), the largest first; ties break by the earlier start.
     */
    public static int[] candidates(double[] rolling, int length, int top) {
        Integer[] order = new Integer[rolling.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int c = Double.compare(rolling[b], rolling[a]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        List<Integer> picked = new ArrayList<>();
        for (int s : order) {
            if (picked.size() >= top) {
                break;
            }
            boolean apart = true;
            for (int p : picked) {
                if (Math.abs(p - s) < length) {
                    apart = false;
                    break;
                }
            }
            if (apart) {
                picked.add(s);
            }
        }
        return picked.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The index (in the whole history) of the window's day with the lowest P&amp;L; ties: the earlier day. */
    public static int worstDay(double[] pnl, int start, int length) {
        int best = start;
        for (int j = start + 1; j < start + length; j++) {
            if (pnl[j] < pnl[best]) {
                best = j;
            }
        }
        return best;
    }

    /** The first index whose date is on or after {@code day} (ISO dates compare as text), or -1 when every date is before it. */
    public static int indexOf(String[] dates, String day) {
        int lo = 0;
        int hi = dates.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (dates[mid].compareTo(day) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo < dates.length ? lo : -1;
    }

    /** The VaR of the last {@code n} values (today's VaR when they are the normal scenario set's days); NaN when the history is shorter. */
    public static double tail(double[] pnl, int n, double alpha, VarMath.RankRule rule) {
        if (n <= 0 || n > pnl.length) {
            return Double.NaN;
        }
        return VarMath.var(Arrays.copyOfRange(pnl, pnl.length - n, pnl.length), alpha, rule);
    }
}
