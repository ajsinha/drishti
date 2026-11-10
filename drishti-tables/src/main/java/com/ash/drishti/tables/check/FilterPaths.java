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
package com.ash.drishti.tables.check;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The directions a filter takes through a model's relationships, and the refusal when a table can reach another by two paths and one of them is a both-ways
 * relationship or a bridge. A filter runs one to many; {@code both} adds many to one. A plain diamond of one-way relationships is the older check's concern
 * ({@code DRS-9124}, any two active paths), so only the paths this release adds are read here.
 */
public final class FilterPaths {

    /**
     * A relationship seen by filters.
     *
     * @param name its name in words
     * @param many the many side
     * @param one the one side (or the left of a bridge's leg)
     * @param both a filter also runs from {@code many} to {@code one}
     * @param bridge this link is a leg of a many-to-many
     */
    public record Link(String name, String many, String one, boolean both, boolean bridge) {}

    private record Step(Link link, String from, String to) {}

    private FilterPaths() {}

    /** The refusal in words, or null when no table reaches another by two paths with one of them both-ways or across a bridge. */
    public static String ambiguous(List<Link> links) {
        Set<String> tables = new LinkedHashSet<>();
        links.forEach(l -> {
            tables.add(l.many());
            tables.add(l.one());
        });
        List<Step> steps = new ArrayList<>();
        for (Link l : links) {
            steps.add(new Step(l, l.one(), l.many()));
            if (l.both()) {
                steps.add(new Step(l, l.many(), l.one()));
            }
        }
        for (String a : tables) {
            for (String b : tables) {
                if (a.equals(b)) {
                    continue;
                }
                List<List<Step>> paths = new ArrayList<>();
                walk(a, b, steps, new ArrayList<>(), new LinkedHashSet<>(Set.of(a)), paths);
                if (paths.size() >= 2 && paths.stream().anyMatch(FilterPaths::special)) {
                    return refusal(a, b, paths.get(0), paths.get(1));
                }
            }
        }
        return null;
    }

    private static boolean special(List<Step> path) {
        return path.stream().anyMatch(s -> s.link().bridge() || s.link().both());
    }

    private static void walk(String at, String to, List<Step> steps, List<Step> path, Set<String> seen, List<List<Step>> out) {
        if (out.size() >= 8) {
            return;
        }
        for (Step s : steps) {
            if (!s.from().equals(at) || seen.contains(s.to())) {
                continue;
            }
            List<Step> longer = new ArrayList<>(path);
            longer.add(s);
            if (s.to().equals(to)) {
                out.add(longer);
            } else {
                Set<String> next = new LinkedHashSet<>(seen);
                next.add(s.to());
                walk(s.to(), to, steps, longer, next, out);
            }
        }
    }

    private static String words(String a, List<Step> path) {
        StringBuilder b = new StringBuilder(a);
        path.forEach(s -> b.append(" -> ").append(s.to()));
        return b.toString();
    }

    private static String refusal(String a, String b, List<Step> one, List<Step> two) {
        List<Step> plain = special(one) && !special(two) ? two : one;
        List<Step> other = plain == one ? two : one;
        String off = plain.stream().filter(s -> !s.link().bridge()).map(s -> s.link().name()).findFirst().orElse(plain.get(plain.size() - 1).link().name());
        String leg = other.stream().filter(s -> s.link().both()).map(s -> s.link().name()).findFirst().orElse(other.get(0).link().name());
        return "Two filter paths from " + a + " to " + b + ": 1 · " + words(a, one) + "; 2 · " + words(a, two) + ". A filter on " + a
                + " cannot choose, and the two can keep different rows of " + b + ". Fix: make '" + off + "' inactive and name it with via: on the measures that need it"
                + ", or turn off both ways on '" + leg + "'.";
    }
}
