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
package com.ash.drishti.server.exposure;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * The tiered retention of exposure data (EXPOSURE_GUIDE.md section 27.3, accepted by the product owner on 2026-10-26), counted back from the newest
 * date:
 *
 * <table>
 *   <caption>what is kept how long</caption>
 *   <tr><th>Data</th><th>Kept</th><th>Applied by</th></tr>
 *   <tr><td>trade paths ({@code exposure-path})</td><td>the last {@code trade-paths-days} business dates, and each month-end for
 *       {@code trade-paths-month-end-years}</td><td>the lake's owner (Drishti reads them, never deletes them): {@code GET
 *       /api/v1/exposure/retention} lists the dates beyond</td></tr>
 *   <tr><td>netting-set matrices (the hot files)</td><td>every date for {@code set-matrices-years}, each month-end for
 *       {@code set-matrices-month-end-years}</td><td>the hot tier: a kept date's file is never trimmed by {@code disk-dates}</td></tr>
 *   <tr><td>statistics (the official records) and trade contributions</td><td>every date for {@code statistics-years} and
 *       {@code trade-contributions-years}</td><td>the official store, pruned after each capture</td></tr>
 * </table>
 *
 * A month-end is the last weekday of its month.
 */
final class ExposureRetention {

    private ExposureRetention() {}

    static boolean monthEnd(LocalDate d) {
        LocalDate next = d.plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        return next.getMonthValue() != d.getMonthValue();
    }

    static boolean keepsSetMatrices(ExposureProperties.Retention r, LocalDate d, LocalDate newest) {
        return !d.isBefore(newest.minusYears(r.setMatricesYears())) || monthEnd(d) && !d.isBefore(newest.minusYears(r.setMatricesMonthEndYears()));
    }

    static boolean keepsStatistics(ExposureProperties.Retention r, LocalDate d, LocalDate newest) {
        return !d.isBefore(newest.minusYears(r.statisticsYears()));
    }

    static boolean keepsTradeContributions(ExposureProperties.Retention r, LocalDate d, LocalDate newest) {
        return !d.isBefore(newest.minusYears(r.tradeContributionsYears()));
    }

    /** Whether a date's trade paths are within the window: among the last {@code trade-paths-days} held dates, or a recent month-end. */
    static boolean keepsTradePaths(ExposureProperties.Retention r, LocalDate d, NavigableSet<LocalDate> held, LocalDate newest) {
        int within = held.tailSet(d, false).size();
        return within < r.tradePathsDays() || monthEnd(d) && !d.isBefore(newest.minusYears(r.tradePathsMonthEndYears()));
    }

    /** The plan for every date held: what is kept and what is beyond its window. */
    static Map<String, Object> plan(ExposureProperties.Retention r, Collection<LocalDate> dates) {
        NavigableSet<LocalDate> held = new TreeSet<>(dates);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tradePathsDays", r.tradePathsDays());
        out.put("tradePathsMonthEndYears", r.tradePathsMonthEndYears());
        out.put("setMatricesYears", r.setMatricesYears());
        out.put("setMatricesMonthEndYears", r.setMatricesMonthEndYears());
        out.put("statisticsYears", r.statisticsYears());
        out.put("tradeContributionsYears", r.tradeContributionsYears());
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> beyond = new ArrayList<>();
        if (!held.isEmpty()) {
            LocalDate newest = held.last();
            for (LocalDate d : held.descendingSet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("businessDate", d.toString());
                m.put("monthEnd", monthEnd(d));
                boolean paths = keepsTradePaths(r, d, held, newest);
                m.put("tradePaths", paths ? "keep" : "beyond");
                m.put("setMatrices", keepsSetMatrices(r, d, newest) ? "keep" : "beyond");
                m.put("statistics", keepsStatistics(r, d, newest) ? "keep" : "beyond");
                m.put("tradeContributions", keepsTradeContributions(r, d, newest) ? "keep" : "beyond");
                rows.add(m);
                if (!paths) {
                    beyond.add(d.toString());
                }
            }
        }
        out.put("dates", rows);
        out.put("tradePathsBeyond", beyond);
        return out;
    }
}
