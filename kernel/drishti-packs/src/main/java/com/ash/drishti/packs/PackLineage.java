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
package com.ash.drishti.packs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pack inheritance order. Each pack's linearisation is computed with C3 (as Python orders classes), reading
 * {@code extends: [a, b]} rightmost first, so for a conflict the child wins over its parents and {@code b} over
 * {@code a}; an ancestor shared through several parents appears once, after all of them. The server-wide order is the
 * linearisation of a virtual root that extends every enabled pack, which C3 guarantees agrees with every pack's own.
 * Immutable after construction.
 */
public final class PackLineage {

    private final Map<String, List<String>> linearOf = new HashMap<>();
    private final List<String> global;

    /** @param parents each loaded pack's parents in declaration order @param enabled the packs enabled, in order */
    public PackLineage(Map<String, List<String>> parents, List<String> enabled) {
        for (String p : parents.keySet()) {
            linearise(p, parents, new LinkedHashSet<>());
        }
        List<List<String>> heads = new ArrayList<>();
        List<String> roots = new ArrayList<>(enabled).reversed();
        roots.forEach(r -> heads.add(new ArrayList<>(linearOf.get(r))));
        heads.add(new ArrayList<>(roots));
        this.global = List.copyOf(merge(heads, "the enabled packs " + enabled));
    }

    /** Most specific first: a pack comes before every pack it inherits from. */
    public List<String> order() {
        return global;
    }

    /** {@code pack} and everything it inherits from, most specific first. */
    public List<String> linearisation(String pack) {
        return linearOf.getOrDefault(pack, List.of(pack));
    }

    /**
     * Which of two packs' contributions wins: the one earlier in the server-wide order, provided the two are related
     * (one inherits from the other, or some loaded pack inherits from both). Unrelated packs may not both define the
     * same thing: null.
     */
    public String winner(String a, String b) {
        boolean related = linearOf.values().stream().anyMatch(l -> l.contains(a) && l.contains(b));
        if (!related) {
            return null;
        }
        return global.indexOf(a) <= global.indexOf(b) ? a : b;
    }

    private List<String> linearise(String pack, Map<String, List<String>> parents, Set<String> visiting) {
        List<String> done = linearOf.get(pack);
        if (done != null) {
            return done;
        }
        if (!visiting.add(pack)) {
            throw new IllegalStateException("packs inherit from each other in a cycle: " + String.join(" -> ", visiting) + " -> " + pack);
        }
        List<String> bases = new ArrayList<>(parents.getOrDefault(pack, List.of())).reversed();   // rightmost parent first
        List<List<String>> heads = new ArrayList<>();
        for (String b : bases) {
            heads.add(new ArrayList<>(linearise(b, parents, visiting)));
        }
        heads.add(new ArrayList<>(bases));
        List<String> out = new ArrayList<>();
        out.add(pack);
        out.addAll(merge(heads, "pack '" + pack + "'"));
        visiting.remove(pack);
        List<String> result = List.copyOf(out);
        linearOf.put(pack, result);
        return result;
    }

    /** The C3 merge: repeatedly take the first head that appears in no other list's tail. */
    private static List<String> merge(List<List<String>> lists, String what) {
        List<String> out = new ArrayList<>();
        lists.removeIf(List::isEmpty);
        while (!lists.isEmpty()) {
            String next = null;
            for (List<String> l : lists) {
                String candidate = l.get(0);
                boolean inTail = lists.stream().anyMatch(o -> o.indexOf(candidate) > 0);
                if (!inTail) {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                throw new IllegalStateException("the inheritance order of " + what + " is inconsistent: two packs list the same parents in opposite orders");
            }
            out.add(next);
            String taken = next;
            lists.forEach(l -> l.remove(taken));
            lists.removeIf(List::isEmpty);
        }
        return out;
    }
}
