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
package com.ash.drishti.net.config;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nodes per role ({@code config/net/access.yaml}): {@code roles: { risk: { nodes: [n-a, region:emea] } }}. A role not listed,
 * or listed with no nodes, reaches every node. A caller reaches a node when any of its roles does; the {@code *} role of a
 * server with security off reaches every node. Applied to the next request and gossiped in the digest.
 *
 * @param roles role name to the node ids and tags ({@code key:value}) it may reach
 */
public record AccessSettings(Map<String, List<String>> roles) {

    public static final AccessSettings OPEN = new AccessSettings(Map.of());

    public AccessSettings {
        Map<String, List<String>> m = new LinkedHashMap<>();
        if (roles != null) {
            roles.forEach((k, v) -> m.put(k, v == null ? List.of() : List.copyOf(v)));
        }
        roles = java.util.Collections.unmodifiableMap(m);
    }

    /** True when a caller with {@code callerRoles} may reach the node {@code nodeId} that carries {@code tags}. */
    public boolean reaches(Collection<String> callerRoles, String nodeId, Collection<String> tags) {
        if (callerRoles == null || callerRoles.contains("*")) {
            return true;
        }
        boolean limited = false;
        for (String r : callerRoles) {
            List<String> allowed = roles.get(r);
            if (allowed == null || allowed.isEmpty()) {
                return true;                                    // this role is not limited
            }
            limited = true;
            for (String a : allowed) {
                if (a.equals(nodeId) || (tags != null && tags.contains(a))) {
                    return true;
                }
            }
        }
        return !limited && callerRoles.isEmpty() && roles.isEmpty();
    }
}
