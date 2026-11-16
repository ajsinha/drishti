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
package com.ash.drishti.plugin.file;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DatedHitIndex;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * The file connector's multi-day type-ahead ({@code index.days} above 1; see {@link DatedHitIndex}): which business days
 * each kind has (dated folders {@code root/<date>/<kind>/<id>.json} and {@code root/<date>/<kind>.jsonl} files), the ids of
 * a day read from a folder listing or the day's JSON-lines index, and a restated day (its folder or file changed since the
 * last scan) rebuilt in the background. Nothing here is built at the default of one day.
 */
final class FileDatedIndex {

    private final Path root;
    private final String sourceName;
    private final BiFunction<String, LocalDate, Collection<String>> jsonlIds;
    private final DatedHitIndex dated;
    private final Map<String, Long> stamps = new HashMap<>();

    FileDatedIndex(Path root, String sourceName, Map<String, String> settings, BiFunction<String, LocalDate, Collection<String>> jsonlIds) {
        this.root = root;
        this.sourceName = sourceName;
        this.jsonlIds = jsonlIds;
        this.dated = new DatedHitIndex(sourceName, DatedHitIndex.defaultRoot(), settings, this::dayHits, (kind, day) -> Long.toString(stamp(kind, day)));
    }

    boolean enabled() {
        return dated.enabled();
    }

    DatedHitIndex index() {
        return dated;
    }

    /** One scan: the days of every kind, the newest day put, the window rolled, restated days rebuilt. */
    void refresh(Collection<LocalDate> folders, Map<String, NavigableSet<LocalDate>> jsonlDays, java.util.Set<String> effectiveKinds) {
        if (!dated.enabled()) {
            return;
        }
        Map<String, TreeSet<LocalDate>> kindDays = new TreeMap<>();
        for (LocalDate d : folders) {
            try (Stream<Path> in = Files.list(root.resolve(d.toString()))) {
                for (Path p : in.toList()) {
                    String n = p.getFileName().toString();
                    if (Files.isDirectory(p)) {
                        kindDays.computeIfAbsent(n, k -> new TreeSet<>()).add(d);
                    }
                }
            } catch (IOException e) {
                // a folder that vanished mid-scan is picked up next time
            }
        }
        jsonlDays.forEach((kind, ds) -> {
            if (!effectiveKinds.contains(kind)) {
                ds.stream().filter(d -> !d.equals(LocalDate.MIN)).forEach(d -> kindDays.computeIfAbsent(kind, k -> new TreeSet<>()).add(d));
            }
        });
        kindDays.forEach((kind, ds) -> {
            LocalDate newest = ds.last();
            boolean fresh = !dated.has(kind, newest) || changed(kind, newest);
            if (fresh) {
                dated.putDay(kind, newest, dayHits(kind, newest));
            }
            dated.plan(kind, ds);
            int kept = dated.settingsFor(kind).days();
            for (LocalDate d : ds.descendingSet().stream().limit(kept).toList()) {
                if (!d.equals(newest) && changed(kind, d)) {
                    dated.restated(kind, d);                       // the day's folder or file was rewritten
                }
            }
        });
    }

    /** True when the day's folder or file has a new modification time since the last call (the first call stamps it). */
    private boolean changed(String kind, LocalDate day) {
        long now = stamp(kind, day);
        Long was = stamps.put(kind + "|" + day, now);
        return was != null && was != now;
    }

    private long stamp(String kind, LocalDate day) {
        Path dir = root.resolve(day.toString()).resolve(kind);
        Path file = root.resolve(day.toString()).resolve(kind + ".jsonl");
        try {
            long m = Files.isDirectory(dir) ? Files.getLastModifiedTime(dir).toMillis() : 0;
            if (Files.isRegularFile(file)) {
                m = Math.max(m, Files.getLastModifiedTime(file).toMillis());
            }
            return m;
        } catch (IOException e) {
            return 0;
        }
    }

    private List<EntityHit> dayHits(String kind, LocalDate day) {
        List<EntityHit> out = new ArrayList<>();
        String subtitle = kind + " · " + sourceName;
        Path dir = root.resolve(day.toString()).resolve(kind);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".json") || n.endsWith(".csv")).forEach(n -> {
                    String id = n.substring(0, n.lastIndexOf('.'));
                    out.add(new EntityHit(EntityRef.of(kind, id), id, subtitle));
                });
            } catch (IOException e) {
                // the next scan tries again
            }
        }
        if (Files.isRegularFile(root.resolve(day.toString()).resolve(kind + ".jsonl"))) {
            try {
                for (String id : jsonlIds.apply(kind, day)) {
                    out.add(new EntityHit(EntityRef.of(kind, id), id, subtitle));
                }
            } catch (RuntimeException e) {
                // an unreadable file: its day is not suggested until it is fixed
            }
        }
        return out;
    }

    /** The date's own ids when the kept days hold it; empty when the caller answers from the newest day's index. */
    Optional<List<EntityHit>> search(String kind, String text, int limit, AsOf asOf) {
        if (!dated.enabled()) {
            return Optional.empty();
        }
        return dated.search(kind, text, limit, asOf == null ? null : asOf.businessDate());
    }
}
