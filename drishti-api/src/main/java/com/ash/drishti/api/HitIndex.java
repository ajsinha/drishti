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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A small in-memory search index for {@link SourcePlugin#search}. Read-mostly and lock-free on reads.
 * Ranking: identifier prefix, then identifier substring, then title/subtitle substring; ties by id.
 */
public final class HitIndex {

    private record Entry(EntityHit hit, String id, String text) {}

    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();

    public void add(EntityHit hit) {
        entries.add(new Entry(hit, hit.ref().id().toLowerCase(Locale.ROOT),
                (hit.title() + " " + hit.subtitle()).toLowerCase(Locale.ROOT)));
    }

    public void replaceAll(List<EntityHit> hits) {
        List<Entry> fresh = new ArrayList<>(hits.size());
        for (EntityHit h : hits) {
            fresh.add(new Entry(h, h.ref().id().toLowerCase(Locale.ROOT),
                    (h.title() + " " + h.subtitle()).toLowerCase(Locale.ROOT)));
        }
        entries.clear();
        entries.addAll(fresh);
    }

    public int size() {
        return entries.size();
    }

    /** Hits of {@code kind} (null or blank for any kind) matching {@code text}; blank text matches all. */
    public List<EntityHit> search(String kind, String text, int limit) {
        String q = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        boolean anyKind = kind == null || kind.isBlank();
        List<Entry> matched = new ArrayList<>();
        for (Entry e : entries) {
            if ((anyKind || e.hit.ref().kind().equals(kind)) && (q.isEmpty() || e.text.contains(q) || e.id.contains(q))) {
                matched.add(e);
            }
        }
        matched.sort(Comparator.comparingInt((Entry e) -> rank(e, q)).thenComparing(e -> e.id));
        return matched.stream().limit(Math.max(0, limit)).map(Entry::hit).toList();
    }

    private static int rank(Entry e, String q) {
        if (q.isEmpty() || e.id.startsWith(q)) {
            return 0;
        }
        return e.id.contains(q) ? 1 : 2;
    }
}
