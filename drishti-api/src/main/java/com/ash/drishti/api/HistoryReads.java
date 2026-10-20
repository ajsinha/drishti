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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The default {@link SourcePlugin#history}: one {@link SourcePlugin#fetch(EntityRef, AsOf) dated read} per date, newest first, at most
 * {@link HistoryRequest#parallelDays()} at once on virtual threads, within the request's budget. A date answered by an earlier date (a
 * snapshot source's lookback, an effective kind carrying a change forward) is not a day of the entity: only reads whose provenance names the
 * date asked are kept, as {@link ColumnScans} skips such days. Connectors whose layout can do better override {@code history}; every
 * connector gives the same answers (the testkit's {@code DatedSourceContract} holds them to it).
 */
public final class HistoryReads {

    /** Why a date is in {@link HistoryRead#failed()} when the budget ran out before it was read. */
    public static final String TIMED_OUT = "timeout";

    private HistoryReads() {}

    public static HistoryRead perDay(SourcePlugin source, HistoryRequest r) {
        long deadline = System.nanoTime() + r.budget().toNanos();
        List<LocalDate> dates = dates(source, r);
        List<HistoryDay> days = new ArrayList<>();
        Set<LocalDate> covered = new HashSet<>();
        Map<LocalDate, String> failed = new LinkedHashMap<>();
        List<LocalDate> newestFirst = new ArrayList<>(dates);
        java.util.Collections.reverse(newestFirst);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            int i = 0;
            while (i < newestFirst.size()) {
                if (r.lastDays() > 0 && days.size() >= r.lastDays()) {
                    break;
                }
                int end = Math.min(newestFirst.size(), i + r.parallelDays());
                List<LocalDate> chunk = newestFirst.subList(i, end);
                List<Future<Optional<EntityDocument>>> reads = new ArrayList<>();
                for (LocalDate d : chunk) {
                    reads.add(pool.submit(() -> source.fetch(r.ref(), new AsOf(d, r.knownAt()))));
                }
                for (int k = 0; k < chunk.size(); k++) {
                    LocalDate d = chunk.get(k);
                    try {
                        long left = deadline - System.nanoTime();
                        if (left <= 0) {
                            throw new TimeoutException();
                        }
                        Optional<EntityDocument> doc = reads.get(k).get(left, TimeUnit.NANOSECONDS);
                        if (source.coverage(r.ref().kind(), AsOf.of(d)) == DateCoverage.HELD) {
                            covered.add(d);
                        }
                        doc.filter(e -> d.equals(e.provenance().businessDate()))
                                .ifPresent(e -> days.add(new HistoryDay(d, project(e, r.fields()), null)));
                    } catch (TimeoutException e) {
                        reads.get(k).cancel(true);
                        failed.put(d, TIMED_OUT);
                    } catch (ExecutionException e) {
                        Throwable c = e.getCause() == null ? e : e.getCause();
                        failed.put(d, c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failed.put(d, TIMED_OUT);
                    }
                }
                i = end;
            }
            pool.shutdownNow();
        }
        List<HistoryDay> kept = days;
        if (r.lastDays() > 0 && days.size() > r.lastDays()) {
            kept = days.stream().sorted((a, b) -> b.date().compareTo(a.date())).limit(r.lastDays()).toList();
        }
        return new HistoryRead(kept, covered, failed);
    }

    /** The dates to read, oldest first: the source's own list of the entity's dates when it has one, else the request's dates. */
    public static List<LocalDate> dates(SourcePlugin source, HistoryRequest r) {
        List<LocalDate> candidates = r.candidateDates();
        Optional<NavigableSet<LocalDate>> held = source.days(r.ref(), r.from(), r.to());
        if (held.isEmpty()) {
            return candidates;
        }
        if (r.dates().isEmpty()) {
            return List.copyOf(held.get());
        }
        Set<LocalDate> wanted = new HashSet<>(candidates);
        return held.get().stream().filter(wanted::contains).toList();
    }

    /** The document cut to the top-level fields asked (a dotted path keeps its top-level field); the whole document when none are asked. */
    public static EntityDocument project(EntityDocument doc, Set<String> fields) {
        if (fields.isEmpty() || doc.deleted() || !(doc.data() instanceof DataNode.Obj obj)) {
            return doc;
        }
        Set<String> top = new HashSet<>();
        for (String f : fields) {
            String p = f.startsWith("$.") ? f.substring(2) : f;
            int cut = indexOfAny(p);
            top.add(cut < 0 ? p : p.substring(0, cut));
        }
        LinkedHashMap<String, DataNode> kept = new LinkedHashMap<>();
        obj.fields().forEach((k, v) -> {
            if (top.contains(k)) {
                kept.put(k, v);
            }
        });
        return new EntityDocument(doc.ref(), new DataNode.Obj(kept), doc.provenance(), false);
    }

    private static int indexOfAny(String p) {
        int dot = p.indexOf('.');
        int bracket = p.indexOf('[');
        if (dot < 0) {
            return bracket;
        }
        return bracket < 0 ? dot : Math.min(dot, bracket);
    }
}
