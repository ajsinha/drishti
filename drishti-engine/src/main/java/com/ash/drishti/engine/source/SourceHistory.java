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
package com.ash.drishti.engine.source;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.HistoryDay;
import com.ash.drishti.api.HistoryRead;
import com.ash.drishti.api.HistoryReads;
import com.ash.drishti.api.HistoryRequest;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.ErrorCode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One entity over business dates through a kind's connectors (HISTORY_QUERIES.md section 9.3): the dated connectors in read order, each
 * asked only for the dates no earlier one holds or is authoritative for ({@code covered}), within what is left of the budget; then the
 * Drishti*net peers for the dates no local connector holds. A connector that fails a date keeps it failed: another store's data is never
 * shown in place of the data a failing store holds, as with single reads. Dates two answers share take the higher generation.
 */
public final class SourceHistory {

    private final SourceRouter router;

    SourceHistory(SourceRouter router) {
        this.router = router;
    }

    /** What the connectors answered: the days, oldest first, and the dates that could not be read with the connector and why. */
    public record Routed(List<HistoryDay> days, List<Failure> failures, List<String> asked) {}

    /**
     * A date a connector could not read; {@code reason} is {@link HistoryReads#TIMED_OUT} when the budget ran out. The source
     * {@link #UNREACHABLE} stands for the Drishti*net nodes that hold the kind but cannot be reached now (down, suspect, drained): no peer
     * that answered held the date, so the date may be on one of them.
     */
    public record Failure(String source, LocalDate date, String reason) {
        public boolean unreachable() {
            return UNREACHABLE.equals(source);
        }
    }

    /** The source of a {@link Failure} for the nodes that hold the kind but cannot be reached. */
    public static final String UNREACHABLE = "net";

    Routed read(HistoryRequest r, List<SourcePlugin> candidates, SourceRouter.Fallback fb) {
        long deadline = System.nanoTime() + r.budget().toNanos();
        List<LocalDate> remaining = new ArrayList<>(r.candidateDates());
        TreeMap<LocalDate, HistoryDay> days = new TreeMap<>();
        List<Failure> failures = new ArrayList<>();
        List<String> asked = new ArrayList<>();
        List<SourcePlugin> dated = candidates.stream().filter(p -> p.manifest().capabilities().dated()).toList();
        for (SourcePlugin p : dated) {
            if (remaining.isEmpty()) {
                break;
            }
            String name = p.manifest().name();
            if (r.knownAt() != null && !p.timeTravel()) {
                if (!mayHold(p, r.ref().kind(), remaining)) {
                    continue;                    // holds none of these dates: it has nothing to answer "as known at" (as single reads)
                }
                refuseKnownAt(p, r, name);
            }
            Duration left = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
            asked.add(name);
            Set<LocalDate> settled = new HashSet<>();
            long t0 = System.nanoTime();
            try {
                HistoryRead read = p.history(r.withDates(remaining).withBudget(left));
                router.stats().found(name, System.nanoTime() - t0);
                for (HistoryDay d : read.days()) {
                    merge(days, new HistoryDay(d.date(), SourceRouter.named(d.document(), name), d.versions()));
                    settled.add(d.date());
                }
                read.failed().forEach((d, why) -> {
                    failures.add(new Failure(name, d, why));
                    settled.add(d);
                });
                if (!router.isUnion(name)) {
                    settled.addAll(read.covered());
                }
            } catch (Exception e) {
                router.stats().failed(name, System.nanoTime() - t0, e);
                String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                for (LocalDate d : remaining) {
                    failures.add(new Failure(name, d, why));
                }
                settled.addAll(remaining);
            }
            remaining.removeIf(settled::contains);       // a later connector may hold newer dates: the last N are cut at the end
        }
        if (!remaining.isEmpty() && fb != null) {
            List<SourcePlugin> peers = fb.peers(r.ref(), AsOf.of(r.to()));
            for (SourcePlugin p : peers) {
                if (remaining.isEmpty()) {
                    break;
                }
                String name = p.manifest().name();
                asked.add(name);
                Duration left = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
                long t0 = System.nanoTime();
                try {
                    HistoryRead read = p.history(r.withDates(remaining).withBudget(left));
                    router.stats().found(name, System.nanoTime() - t0);
                    Set<LocalDate> got = new HashSet<>();
                    for (HistoryDay d : read.days()) {
                        merge(days, d);
                        got.add(d.date());
                    }
                    read.failed().forEach((d, why) -> failures.add(new Failure(name, d, why)));
                    remaining.removeIf(got::contains);
                } catch (Exception e) {
                    router.stats().failed(name, System.nanoTime() - t0, e);
                    for (LocalDate d : remaining) {
                        failures.add(new Failure(name, d, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                    }
                }
            }
            // dates no node that answered holds, while a node that holds the kind is down: they may be there, so they are not "no data"
            List<String> down = remaining.isEmpty() ? List.of() : fb.downHolders(r.ref().kind());
            if (!down.isEmpty()) {
                String why = "the nodes that hold " + r.ref().kind() + " cannot be reached now: " + String.join(", ", down);
                Set<LocalDate> failed = new HashSet<>();
                failures.forEach(f -> failed.add(f.date()));
                for (LocalDate d : remaining) {
                    if (!failed.contains(d)) {
                        failures.add(new Failure(UNREACHABLE, d, why));
                    }
                }
            }
        }
        List<HistoryDay> out = new ArrayList<>(days.values());
        if (r.lastDays() > 0 && out.size() > r.lastDays()) {
            out = new ArrayList<>(out.subList(out.size() - r.lastDays(), out.size()));
        }
        // with the last N days found, a date older than all of them that failed changes nothing shown
        LocalDate oldest = r.lastDays() > 0 && out.size() == r.lastDays() ? out.get(0).date() : null;
        List<Failure> kept = failures.stream().filter(f -> oldest == null || !f.date().isBefore(oldest)).toList();
        return new Routed(List.copyOf(out), dedupe(kept), List.copyOf(asked));
    }

    /** A date two sources both answer (shards that overlap, a peer and a local store) keeps the higher generation. */
    private static void merge(TreeMap<LocalDate, HistoryDay> days, HistoryDay d) {
        days.merge(d.date(), d, (a, b) -> b.document().provenance().generation() > a.document().provenance().generation() ? b : a);
    }

    private static List<Failure> dedupe(List<Failure> in) {
        Map<String, Failure> seen = new java.util.LinkedHashMap<>();
        for (Failure f : in) {
            seen.putIfAbsent(f.source() + "|" + f.date(), f);
        }
        return List.copyOf(seen.values());
    }

    /**
     * Whether a dated store may hold one of {@code dates} of the kind: not when it holds nothing of the kind ({@link SourcePlugin#holdsNone})
     * or says of every date that it is {@link com.ash.drishti.api.DateCoverage#NOT_HELD} (the shipped {@code file} connector before any file
     * of the kind appears). Such a store is not asked, so a read "as known at" an instant is not refused for it (QA1-04).
     */
    private static boolean mayHold(SourcePlugin p, String kind, List<LocalDate> dates) {
        try {
            if (p.holdsNone(kind)) {
                return false;
            }
            for (LocalDate d : dates) {
                if (p.coverage(kind, AsOf.of(d)) != com.ash.drishti.api.DateCoverage.NOT_HELD) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return true;                                                  // it cannot say: it may hold them
        }
    }

    /** A read "as known at" an instant cannot be put to a dated store that keeps no earlier versions (DRS-1007, as single reads). */
    private static void refuseKnownAt(SourcePlugin p, HistoryRequest r, String name) {
        if (r.knownAt() != null && !p.timeTravel()) {
            throw new SourceFailure(ErrorCode.NO_TIME_TRAVEL, name, SourceRouter.NO_VERSIONS,
                    name + " " + SourceRouter.NO_VERSIONS + "; the history of " + r.ref() + " was asked for as known at " + r.knownAt(), null);
        }
    }
}
