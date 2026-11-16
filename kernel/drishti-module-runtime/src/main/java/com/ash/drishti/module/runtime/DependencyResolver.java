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
package com.ash.drishti.module.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * V4, the dependencies (KERNEL_AND_MODULES.md section 4.3): every required module present, in range and not refused; no cycle; no
 * conflict. The load order is topological by {@code requires} (optional ones too, when present), then by id for a stable order. A refusal
 * propagates to every module that requires it (<i>refused: requires banking, which is refused</i>).
 */
public final class DependencyResolver {

    /** The order to load, and the modules refused here (id → reason). */
    public record Resolution(List<ModuleCandidate> order, Map<String, ModuleRefusedException> refused) {
    }

    private DependencyResolver() {
    }

    /**
     * Resolves the candidates; {@code alreadyRefused} names modules refused by earlier checks (they count as absent-but-known, so a
     * dependant is told why).
     */
    public static Resolution resolve(List<ModuleCandidate> candidates, Map<String, ModuleRefusedException> alreadyRefused) {
        Map<String, ModuleCandidate> byId = new TreeMap<>();
        candidates.forEach(c -> byId.put(c.id(), c));
        Map<String, ModuleRefusedException> refused = new LinkedHashMap<>();
        // conflicts and direct requirements, then propagation until nothing changes
        boolean changed = true;
        while (changed) {
            changed = false;
            for (ModuleCandidate c : byId.values()) {
                if (refused.containsKey(c.id())) {
                    continue;
                }
                ModuleRefusedException why = check(c, byId, refused, alreadyRefused);
                if (why != null) {
                    refused.put(c.id(), why);
                    changed = true;
                }
            }
        }
        // cycles: Kahn's algorithm over what remains; whatever cannot be ordered is in or behind a cycle
        Map<String, List<String>> deps = new TreeMap<>();
        for (ModuleCandidate c : byId.values()) {
            if (!refused.containsKey(c.id())) {
                List<String> d = new ArrayList<>();
                for (DmodManifest.Requirement r : c.manifest().requires()) {
                    if (byId.containsKey(r.id()) && !refused.containsKey(r.id())) {
                        d.add(r.id());
                    }
                }
                deps.put(c.id(), d);
            }
        }
        List<ModuleCandidate> order = new ArrayList<>();
        TreeSet<String> ready = new TreeSet<>();
        deps.forEach((id, d) -> {
            if (d.isEmpty()) {
                ready.add(id);
            }
        });
        Map<String, Integer> pending = new TreeMap<>();
        deps.forEach((id, d) -> pending.put(id, d.size()));
        while (!ready.isEmpty()) {
            String id = ready.pollFirst();
            order.add(byId.get(id));
            pending.remove(id);
            deps.forEach((other, d) -> {
                if (pending.containsKey(other) && d.contains(id) && pending.merge(other, -1, Integer::sum) == 0) {
                    ready.add(other);
                }
            });
        }
        for (String id : pending.keySet()) {
            refused.put(id, new ModuleRefusedException("V4", byId.get(id).manifest().label() + ": its requirements form a cycle ("
                    + String.join(" → ", cycleFrom(id, deps)) + ")"));
        }
        return new Resolution(List.copyOf(order), refused);
    }

    private static ModuleRefusedException check(ModuleCandidate c, Map<String, ModuleCandidate> byId, Map<String, ModuleRefusedException> refused,
            Map<String, ModuleRefusedException> alreadyRefused) {
        String label = c.manifest().label();
        for (String x : c.manifest().conflicts()) {
            if (byId.containsKey(x) && !refused.containsKey(x)) {
                return new ModuleRefusedException("V4", label + ": cannot run beside " + byId.get(x).manifest().label() + " (conflicts)");
            }
        }
        for (DmodManifest.Requirement r : c.manifest().requires()) {
            ModuleCandidate dep = byId.get(r.id());
            if (refused.containsKey(r.id()) || alreadyRefused.containsKey(r.id())) {
                if (!r.optional()) {
                    return new ModuleRefusedException("V4", label + ": refused: requires " + r.id() + ", which is refused");
                }
            } else if (dep == null) {
                if (!r.optional()) {
                    return new ModuleRefusedException("V4", label + ": needs " + r.id() + " " + r.range() + "; it is not installed");
                }
            } else if (!r.range().contains(dep.manifest().version())) {
                if (!r.optional()) {
                    return new ModuleRefusedException("V4", label + ": needs " + r.id() + " " + r.range() + "; " + dep.manifest().version()
                            + " installed");
                }
            }
        }
        return null;
    }

    private static List<String> cycleFrom(String start, Map<String, List<String>> deps) {
        List<String> path = new ArrayList<>();
        String at = start;
        while (at != null && !path.contains(at)) {
            path.add(at);
            List<String> d = deps.getOrDefault(at, List.of());
            at = d.stream().filter(deps::containsKey).findFirst().orElse(null);
        }
        if (at != null) {
            path.add(at);
        }
        return path;
    }
}
