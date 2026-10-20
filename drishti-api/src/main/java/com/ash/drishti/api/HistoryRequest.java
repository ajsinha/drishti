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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One entity over business dates ({@link SourcePlugin#history}): the dates {@code from}..{@code to}, both included.
 *
 * @param ref the entity
 * @param from the first date the read may reach (with {@code lastDays}, the earliest date of the walk back)
 * @param to the last date, included
 * @param dates the business dates from..to, oldest first, as the caller's calendar gives them (a plugin has no calendar); empty means
 *     every calendar day from..to
 * @param fields the top-level fields wanted; empty means the whole document
 * @param knownAt read every date as the store stood at this instant (time travel), or null for the latest version of each date
 * @param lastDays above 0: only the last N dates with data on or before {@code to}; 0: every date from..to
 * @param budget how long the whole read may take; dates not read by then are reported failed
 * @param parallelDays how many dates the default per-day loop reads at once
 */
public record HistoryRequest(EntityRef ref, LocalDate from, LocalDate to, List<LocalDate> dates, Set<String> fields,
        Instant knownAt, int lastDays, Duration budget, int parallelDays) {

    /** The default number of dates read at once ({@code drishti.history.parallel-days}). */
    public static final int PARALLEL_DAYS = 8;

    public HistoryRequest {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to " + to + " is before from " + from);
        }
        dates = dates == null ? List.of() : List.copyOf(dates);
        fields = fields == null ? Set.of() : Set.copyOf(fields);
        budget = budget == null ? Duration.ofSeconds(10) : budget;
        lastDays = Math.max(0, lastDays);
        parallelDays = parallelDays <= 0 ? PARALLEL_DAYS : parallelDays;
    }

    /** Every date from..to, whole documents, latest versions, a 10 s budget. */
    public static HistoryRequest of(EntityRef ref, LocalDate from, LocalDate to) {
        return new HistoryRequest(ref, from, to, List.of(), Set.of(), null, 0, null, 0);
    }

    /** The same request for other dates (the router hands a connector only the dates earlier ones did not cover). */
    public HistoryRequest withDates(List<LocalDate> newDates) {
        return new HistoryRequest(ref, from, to, newDates, fields, knownAt, lastDays, budget, parallelDays);
    }

    /** The same request with what is left of the budget. */
    public HistoryRequest withBudget(Duration left) {
        return new HistoryRequest(ref, from, to, dates, fields, knownAt, lastDays, left, parallelDays);
    }

    /** The same request for only the last {@code n} dates with data (0: all of them). */
    public HistoryRequest withLastDays(int n) {
        return new HistoryRequest(ref, from, to, dates, fields, knownAt, n, budget, parallelDays);
    }

    /** The same request for only these top-level fields (empty: whole documents). */
    public HistoryRequest withFields(Set<String> newFields) {
        return new HistoryRequest(ref, from, to, dates, newFields, knownAt, lastDays, budget, parallelDays);
    }

    /** The dates to consider, oldest first: {@link #dates()} when given, else every calendar day from..to. */
    public List<LocalDate> candidateDates() {
        if (!dates.isEmpty()) {
            return dates.stream().filter(d -> !d.isBefore(from) && !d.isAfter(to)).distinct().sorted().toList();
        }
        return from.datesUntil(to.plusDays(1)).toList();
    }
}
