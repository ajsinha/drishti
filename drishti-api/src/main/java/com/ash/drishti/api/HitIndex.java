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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An in-memory search index for {@link SourcePlugin#search}, sized for millions of entities. Read-mostly and
 * lock-free on reads. Ranking: identifier prefix, then identifier substring, then title/subtitle substring; ties by
 * id. Identifiers are kept sorted per kind, so a prefix is found by binary search and an empty search lists in id
 * order without sorting; substring matches scan, stopping once the limit is reached. Additions go to a small
 * pending list, folded into the sorted arrays in batches.
 */
public final class HitIndex {

    private record Entry(EntityHit hit, String id, String text) {}

    /** One immutable version: entries sorted by id, per kind, and additions not yet folded in. */
    private record State(Map<String, Entry[]> byKind, List<Entry> pending, int size) {}

    private static final int FOLD_AT = 4096;
    private static final Comparator<Entry> BY_ID = Comparator.comparing(Entry::id);
    private final AtomicReference<State> state = new AtomicReference<>(new State(Map.of(), List.of(), 0));

    private static Entry entry(EntityHit h) {
        return new Entry(h, h.ref().id().toLowerCase(Locale.ROOT), (h.title() + " " + h.subtitle()).toLowerCase(Locale.ROOT));
    }

    public void add(EntityHit hit) {
        Entry e = entry(hit);
        state.updateAndGet(old -> {
            List<Entry> pending = new ArrayList<>(old.pending().size() + 1);
            pending.addAll(old.pending());
            pending.add(e);
            State next = new State(old.byKind(), List.copyOf(pending), old.size() + 1);
            return pending.size() >= FOLD_AT ? fold(next) : next;
        });
    }

    public void replaceAll(List<EntityHit> hits) {
        Map<String, List<Entry>> grouped = new HashMap<>();
        for (EntityHit h : hits) {
            grouped.computeIfAbsent(h.ref().kind(), k -> new ArrayList<>()).add(entry(h));
        }
        Map<String, Entry[]> byKind = new HashMap<>();
        grouped.forEach((k, list) -> byKind.put(k, sorted(list)));
        state.set(new State(Map.copyOf(byKind), List.of(), hits.size()));
    }

    /** Pending additions merged into the sorted arrays; a later entry for the same entity replaces the earlier. */
    private static State fold(State s) {
        Map<String, Entry[]> byKind = new HashMap<>(s.byKind());
        Map<String, List<Entry>> added = new HashMap<>();
        s.pending().forEach(e -> added.computeIfAbsent(e.hit().ref().kind(), k -> new ArrayList<>()).add(e));
        added.forEach((k, list) -> {
            Map<String, Entry> merged = new java.util.LinkedHashMap<>();
            for (Entry e : byKind.getOrDefault(k, new Entry[0])) {
                merged.put(e.hit().ref().id(), e);
            }
            list.forEach(e -> merged.put(e.hit().ref().id(), e));
            byKind.put(k, sorted(new ArrayList<>(merged.values())));
        });
        int size = byKind.values().stream().mapToInt(a -> a.length).sum();
        return new State(Map.copyOf(byKind), List.of(), size);
    }

    private static Entry[] sorted(List<Entry> list) {
        Entry[] a = list.toArray(new Entry[0]);
        Arrays.sort(a, BY_ID);
        return a;
    }

    public int size() {
        return state.get().size();
    }

    /** Hits of {@code kind} (null or blank for any kind) matching {@code text}; blank text matches all. */
    public List<EntityHit> search(String kind, String text, int limit) {
        int max = Math.max(0, limit);
        if (max == 0) {
            return List.of();
        }
        String q = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        State s = state.get();
        List<Entry[]> arrays = new ArrayList<>();
        if (kind == null || kind.isBlank()) {
            s.byKind().keySet().stream().sorted().forEach(k -> arrays.add(s.byKind().get(k)));
        } else if (s.byKind().containsKey(kind)) {
            arrays.add(s.byKind().get(kind));
        }
        List<Entry> pending = s.pending().stream().filter(e -> kind == null || kind.isBlank() || e.hit().ref().kind().equals(kind)).toList();
        // rank 0: ids starting with q (binary search in each sorted array), in id order
        List<Entry> out = new ArrayList<>();
        java.util.Set<EntityRef> seen = new java.util.HashSet<>();
        List<Entry> prefix = new ArrayList<>();
        for (Entry[] a : arrays) {
            for (int i = lowerBound(a, q); i < a.length && a[i].id().startsWith(q) && prefix.size() < max + pending.size(); i++) {
                prefix.add(a[i]);
            }
        }
        pending.stream().filter(e -> e.id().startsWith(q)).forEach(prefix::add);
        prefix.sort(BY_ID);
        take(prefix, out, seen, max);
        if (q.isEmpty() || out.size() >= max) {
            return out.stream().map(Entry::hit).toList();
        }
        // rank 1: ids containing q; rank 2: titles containing q (scans, stopping at the limit)
        for (int rank = 1; rank <= 2 && out.size() < max; rank++) {
            List<Entry> found = new ArrayList<>();
            for (Entry[] a : arrays) {
                for (Entry e : a) {
                    if (found.size() >= max) {
                        break;
                    }
                    if (matches(e, q, rank) && !seen.contains(e.hit().ref())) {
                        found.add(e);
                    }
                }
            }
            for (Entry e : pending) {
                if (matches(e, q, rank) && !seen.contains(e.hit().ref())) {
                    found.add(e);
                }
            }
            found.sort(BY_ID);
            take(found, out, seen, max);
        }
        return out.stream().map(Entry::hit).toList();
    }

    private static boolean matches(Entry e, String q, int rank) {
        return rank == 1 ? !e.id().startsWith(q) && e.id().contains(q) : !e.id().contains(q) && e.text().contains(q);
    }

    private static void take(List<Entry> from, List<Entry> out, java.util.Set<EntityRef> seen, int max) {
        for (Entry e : from) {
            if (out.size() >= max) {
                return;
            }
            if (seen.add(e.hit().ref())) {
                out.add(e);
            }
        }
    }

    /** The first index whose id is not less than {@code q}. */
    private static int lowerBound(Entry[] a, String q) {
        int lo = 0;
        int hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid].id().compareTo(q) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
