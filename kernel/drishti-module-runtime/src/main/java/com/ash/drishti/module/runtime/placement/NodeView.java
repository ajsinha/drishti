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
package com.ash.drishti.module.runtime.placement;

import java.util.List;
import java.util.Locale;

/**
 * One node as placement sees it: its name, its labels (the node tags of {@code config/net/node.yaml}: {@code heavy}, {@code web},
 * {@code role:risk-compute}) and whether it is up now (alive and routable).
 */
public record NodeView(String id, List<String> tags, boolean up) {

    public NodeView {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** True when the node carries the role or resource class: the bare label or {@code role:<label>}; {@code all} is every node. */
    public boolean has(String role) {
        String r = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        if (r.equals("all")) {
            return true;
        }
        String bare = r.startsWith("role:") ? r.substring(5) : r;
        return tags.stream().map(t -> t.trim().toLowerCase(Locale.ROOT)).anyMatch(t -> t.equals(bare) || t.equals("role:" + bare));
    }
}
