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
package com.ash.drishti.compute.ccr;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A simulation run's time grid (EXP.md section 3.2): G dates after the business date, each {@code PRIMARY} (exposure is reported there)
 * or {@code LOOKBACK} (only for collateral: the set's value a margin period of risk before the primary date it serves). Times are years
 * ACT/365 from the business date. A primary date with no look-back point (its look-back would fall on or before the business date: 1W
 * and 2W on a 10-day margin period) keeps today's collateral.
 *
 * <p>Built from the run's {@code GRID_DATES}, {@code GRID_ROLE} and {@code LOOKBACK_OF} and checked: strictly increasing, all after the
 * business date, every look-back point maps to a primary point after it, the lists of one length ({@link IllegalArgumentException}
 * says what is wrong: the run is not loaded, DRS-5441).
 */
public final class ExposureGrid {

    private final LocalDate businessDate;
    private final LocalDate[] dates;
    private final boolean[] primary;
    private final int[] lookbackOf;
    private final int[] primaries;
    private final int[] lookbackFor;
    private final double[] times;
    private final String[] tenors;

    private ExposureGrid(LocalDate businessDate, LocalDate[] dates, boolean[] primary, int[] lookbackOf, String[] tenors) {
        this.businessDate = businessDate;
        this.dates = dates;
        this.primary = primary;
        this.lookbackOf = lookbackOf;
        this.times = new double[dates.length];
        List<Integer> ps = new ArrayList<>();
        this.lookbackFor = new int[dates.length];
        Arrays.fill(lookbackFor, -1);
        for (int k = 0; k < dates.length; k++) {
            times[k] = ChronoUnit.DAYS.between(businessDate, dates[k]) / 365.0;
            if (primary[k]) {
                ps.add(k);
            } else {
                lookbackFor[lookbackOf[k]] = k;
            }
        }
        this.primaries = ps.stream().mapToInt(Integer::intValue).toArray();
        this.tenors = new String[dates.length];
        for (int k = 0; k < dates.length; k++) {
            this.tenors[k] = tenors != null && k < tenors.length && tenors[k] != null && !tenors[k].isBlank() ? tenors[k]
                    : primary[k] ? label(times[k], dates[k]) : null;
        }
    }

    /**
     * The grid of a run, checked.
     *
     * @param roles {@code PRIMARY} or {@code LOOKBACK} per date
     * @param lookbackOf per date: for a look-back point the index of the primary point it serves, -1 for a primary point
     * @param tenors labels of the primary points (null: the nearest standard tenor, else the date)
     */
    public static ExposureGrid of(LocalDate businessDate, List<LocalDate> dates, List<String> roles, List<Integer> lookbackOf, List<String> tenors) {
        int g = dates.size();
        if (g == 0) {
            throw new IllegalArgumentException("the run has no GRID_DATES");
        }
        if (roles.size() != g || lookbackOf.size() != g) {
            throw new IllegalArgumentException("GRID_DATES has " + g + " dates, GRID_ROLE " + roles.size() + " and LOOKBACK_OF " + lookbackOf.size()
                    + ": one per date");
        }
        LocalDate[] d = new LocalDate[g];
        boolean[] p = new boolean[g];
        int[] lb = new int[g];
        for (int k = 0; k < g; k++) {
            d[k] = dates.get(k);
            if (!d[k].isAfter(businessDate)) {
                throw new IllegalArgumentException("the grid date " + d[k] + " is not after the business date " + businessDate);
            }
            if (k > 0 && !d[k].isAfter(d[k - 1])) {
                throw new IllegalArgumentException("GRID_DATES are not strictly increasing at " + d[k]);
            }
            String role = String.valueOf(roles.get(k)).trim().toUpperCase(java.util.Locale.ROOT);
            if (!role.equals("PRIMARY") && !role.equals("LOOKBACK")) {
                throw new IllegalArgumentException("GRID_ROLE of " + d[k] + " is " + roles.get(k) + ": PRIMARY or LOOKBACK");
            }
            p[k] = role.equals("PRIMARY");
            lb[k] = lookbackOf.get(k) == null ? -1 : lookbackOf.get(k);
        }
        boolean[] served = new boolean[g];
        for (int k = 0; k < g; k++) {
            if (p[k]) {
                if (lb[k] != -1) {
                    throw new IllegalArgumentException("the primary date " + d[k] + " has LOOKBACK_OF " + lb[k] + ": -1 for a primary point");
                }
                continue;
            }
            int of = lb[k];
            if (of <= k || of >= g || !p[of]) {
                throw new IllegalArgumentException("the look-back date " + d[k] + " maps to " + of + ", not a later primary point");
            }
            if (served[of]) {
                throw new IllegalArgumentException("the primary date " + d[of] + " has two look-back points");
            }
            served[of] = true;
        }
        if (java.util.stream.IntStream.range(0, g).noneMatch(k -> p[k])) {
            throw new IllegalArgumentException("the grid has no PRIMARY date");
        }
        return new ExposureGrid(businessDate, d, p, lb, tenors == null ? null : tenors.toArray(new String[0]));
    }

    private static final String[] LABELS = {"1W", "2W", "1M", "2M", "3M", "6M", "9M", "1Y", "2Y", "3Y", "4Y", "5Y", "7Y", "10Y", "12Y", "15Y", "20Y",
        "25Y", "30Y"};
    private static final double[] YEARS = {7 / 365.0, 14 / 365.0, 1 / 12.0, 2 / 12.0, 0.25, 0.5, 0.75, 1, 2, 3, 4, 5, 7, 10, 12, 15, 20, 25, 30};

    /** The standard tenor nearest to t (within 4 days or 2%), else the date itself (EXP.md section 9.1). */
    static String label(double t, LocalDate date) {
        for (int i = 0; i < LABELS.length; i++) {
            if (Math.abs(t - YEARS[i]) <= Math.max(4 / 365.0, 0.02 * YEARS[i])) {
                return LABELS[i];
            }
        }
        return date.toString();
    }

    public LocalDate businessDate() {
        return businessDate;
    }

    /** G, every grid date. */
    public int size() {
        return dates.length;
    }

    public LocalDate date(int k) {
        return dates[k];
    }

    public boolean primary(int k) {
        return primary[k];
    }

    public int lookbackOf(int k) {
        return lookbackOf[k];
    }

    /** The look-back point of primary point k, -1 when it has none (today's collateral applies). */
    public int lookbackFor(int k) {
        return lookbackFor[k];
    }

    /** The grid indices of the primary points, in order. */
    public int[] primaries() {
        return primaries.clone();
    }

    /** The primary points' count. */
    public int primaryCount() {
        return primaries.length;
    }

    /** Grid index of the i-th primary point. */
    public int primaryAt(int i) {
        return primaries[i];
    }

    /** Position among the primary points of grid index k, -1 when k is a look-back point. */
    public int primaryIndex(int k) {
        return Arrays.binarySearch(primaries, k) >= 0 ? Arrays.binarySearch(primaries, k) : -1;
    }

    public double time(int k) {
        return times[k];
    }

    /** The label of a primary point ({@code 1M}, {@code 5Y}, or its date). */
    public String tenor(int k) {
        return tenors[k];
    }

    /** The grid as the run's row says it (for the hot file and the answers). */
    public java.util.Map<String, Object> describe() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("businessDate", businessDate.toString());
        m.put("dates", Arrays.stream(dates).map(LocalDate::toString).toList());
        List<String> roles = new ArrayList<>();
        List<Integer> lb = new ArrayList<>();
        List<String> tn = new ArrayList<>();
        for (int k = 0; k < dates.length; k++) {
            roles.add(primary[k] ? "PRIMARY" : "LOOKBACK");
            lb.add(lookbackOf[k]);
            tn.add(tenors[k]);
        }
        m.put("roles", roles);
        m.put("lookbackOf", lb);
        m.put("tenors", tn);
        return m;
    }

    /** The grid read back from {@link #describe()}. */
    @SuppressWarnings("unchecked")
    public static ExposureGrid fromDescription(java.util.Map<String, Object> m) {
        List<LocalDate> ds = ((List<Object>) m.get("dates")).stream().map(x -> LocalDate.parse(String.valueOf(x))).toList();
        List<String> roles = ((List<Object>) m.get("roles")).stream().map(String::valueOf).toList();
        List<Integer> lb = ((List<Object>) m.get("lookbackOf")).stream().map(x -> ((Number) x).intValue()).toList();
        List<String> tn = ((List<Object>) m.get("tenors")).stream().map(x -> x == null ? null : String.valueOf(x)).toList();
        return of(LocalDate.parse(String.valueOf(m.get("businessDate"))), ds, roles, lb, tn);
    }
}
