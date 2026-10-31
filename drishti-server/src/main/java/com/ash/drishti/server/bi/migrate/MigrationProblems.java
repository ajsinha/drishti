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
package com.ash.drishti.server.bi.migrate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The stored BI items a migration could not convert (docs/architecture/BI_DESIGN.md › The model layer: the specification of wave W1, section 8.3, docs/guides/TROUBLESHOOTING.md "Migration problems"):
 * such an item keeps its text, is never changed silently, and is listed here, by migration step ({@code expressions}, {@code models}), for Admin, BI settings
 * and the item's page. A later run that converts it (after a fix) or finds nothing to do clears its entry. Thread-safe.
 */
public final class MigrationProblems {

    /** One item a step could not convert: what, where in its text, and the problem with its code. */
    public record Problem(String step, String item, String title, String kind, String where, String detail, Instant since) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", step);
            m.put("item", item);
            m.put("title", title);
            m.put("kind", kind);
            m.put("where", where);
            m.put("problem", detail);
            m.put("since", since);
            return m;
        }
    }

    private final Map<String, List<Problem>> byKey = new ConcurrentHashMap<>();

    private static String key(String step, String item) {
        return step + "|" + item;
    }

    /** Replaces what a step found for an item (an empty list clears it). Keeps the first {@code since} of a problem seen again. */
    public void set(String step, String item, List<Problem> found) {
        if (found.isEmpty()) {
            byKey.remove(key(step, item));
            return;
        }
        List<Problem> before = byKey.getOrDefault(key(step, item), List.of());
        List<Problem> out = new ArrayList<>();
        for (Problem p : found) {
            Instant since = before.stream().filter(b -> b.where().equals(p.where()) && b.detail().equals(p.detail())).map(Problem::since).findFirst().orElse(p.since());
            out.add(new Problem(p.step(), p.item(), p.title(), p.kind(), p.where(), p.detail(), since));
        }
        byKey.put(key(step, item), List.copyOf(out));
    }

    public List<Problem> all() {
        return byKey.values().stream().flatMap(List::stream).sorted(Comparator.comparing(Problem::item).thenComparing(Problem::step).thenComparing(Problem::where)).toList();
    }

    public List<Problem> of(String item) {
        return all().stream().filter(p -> p.item().equals(item)).toList();
    }

    public List<Map<String, Object>> view() {
        return all().stream().map(Problem::toMap).toList();
    }
}
