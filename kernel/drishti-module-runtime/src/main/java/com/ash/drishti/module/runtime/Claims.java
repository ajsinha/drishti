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

/**
 * V6, the claims (KERNEL_AND_MODULES.md sections 4.3 and 9.9): a module's API prefix, path aliases, command words, kinds and lens ids may
 * clash with no kernel reserved name and no other loaded module's. Conflicts are refused at load, never resolved by order: the first
 * loaded keeps the name, the newcomer is refused whole. A module's claims are released when it is unloaded.
 */
public final class Claims {

    private final KernelFacts facts;
    private final Map<String, String> owners = new LinkedHashMap<>();

    public Claims(KernelFacts facts) {
        this.facts = facts;
    }

    /** The names a module claims, as {@code <what> <name>}. */
    public static List<String> of(ModuleCandidate c, List<RouteTable.Route> routes) {
        DmodManifest m = c.manifest();
        List<String> out = new ArrayList<>();
        if (m.api() != null) {
            out.add("API prefix /api/v1/" + m.api().prefix());
            m.api().aliases().forEach(a -> out.add("alias " + a));
        }
        routes.stream().filter(r -> r.alias() != null).forEach(r -> out.add("alias " + r.alias()));
        c.words().forEach(w -> out.add("function " + w));
        m.kinds().values().forEach(l -> l.forEach(k -> out.add("kind " + k)));
        c.lenses().forEach(l -> out.add("lens " + l));
        return out;
    }

    /** Refuses (V6) a claim already held by the kernel or another module; takes them all when none clashes. */
    public synchronized void take(ModuleCandidate c, List<RouteTable.Route> routes) {
        String label = c.manifest().label();
        List<String> mine = of(c, routes);
        for (String claim : mine) {
            String name = claim.substring(claim.indexOf(' ') + 1);
            if (claim.startsWith("alias ") && reservedPath(name)) {
                throw new ModuleRefusedException("V6", label + ": " + claim + " is a reserved name of the kernel");
            }
            if (claim.startsWith("function ") && facts.reservedWords().contains(name)) {
                throw new ModuleRefusedException("V6", label + ": " + claim + " is a reserved word of the kernel");
            }
            String owner = owners.get(claim);
            if (owner != null && !owner.equals(c.id())) {
                throw new ModuleRefusedException("V6", label + ": " + claim + " is claimed by " + owner);
            }
        }
        mine.forEach(claim -> owners.put(claim, c.id()));
    }

    /** Releases every claim of a module. */
    public synchronized void release(String module) {
        owners.values().removeIf(module::equals);
    }

    private boolean reservedPath(String path) {
        for (String r : facts.reservedPaths()) {
            if (path.equals(r) || path.startsWith(r + "/")) {
                return true;
            }
        }
        return false;
    }
}
