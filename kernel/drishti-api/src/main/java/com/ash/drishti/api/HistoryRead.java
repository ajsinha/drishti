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
package com.ash.drishti.api;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What a source answered to a {@link HistoryRequest}.
 *
 * @param days the dates it holds the entity on, oldest first, one per date
 * @param covered the dates this source is authoritative for ({@link DateCoverage#HELD}): a date covered and not in {@code days} has no
 *     data for the entity, and no later source is asked for it
 * @param failed the dates it could not read, each with why ({@link HistoryReads#TIMED_OUT} when the budget ran out)
 */
public record HistoryRead(List<HistoryDay> days, Set<LocalDate> covered, Map<LocalDate, String> failed) {

    /** A read that holds nothing and covers nothing (a source that does not serve the kind). */
    public static final HistoryRead NONE = new HistoryRead(List.of(), Set.of(), Map.of());

    public HistoryRead {
        days = days == null ? List.of() : days.stream().sorted(Comparator.comparing(HistoryDay::date)).toList();
        covered = covered == null ? Set.of() : Collections.unmodifiableSortedSet(new TreeSet<>(covered));
        failed = failed == null ? Map.of() : Collections.unmodifiableSortedMap(new TreeMap<>(failed));
    }
}
