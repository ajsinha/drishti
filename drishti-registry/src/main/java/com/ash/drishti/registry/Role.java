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
package com.ash.drishti.registry;

import java.util.List;
import java.util.Map;

/**
 * A role in the access model (ACCESS_MODEL.md section 3): the lenses it may open and the actions inside each. Permissions are granted per
 * lens and action, never per screen. Read from {@code config/access/roles.yaml}; enforced per lens and action from 2.9.11.i.
 *
 * @param id      its id (the identity layer's role name)
 * @param lenses  the lenses it may open; {@code *} for every lens offered
 * @param actions per lens (or {@code *}), the actions allowed ({@code view}, {@code edit}, {@code approve}, {@code raw}, {@code administer}; {@code *} all)
 */
public record Role(String id, List<String> lenses, Map<String, List<String>> actions) {

    public Role {
        id = id == null ? "" : id.strip();
        lenses = lenses == null ? List.of() : lenses.stream().map(ModuleManifest::norm).toList();
        actions = actions == null ? Map.of() : Map.copyOf(actions);
    }

    /** True when the role may open the lens. */
    public boolean opens(String lens) {
        return lenses.contains("*") || lenses.contains(ModuleManifest.norm(lens));
    }

    /** True when the role may do the action in the lens (it must open the lens; a role naming no actions may view). */
    public boolean allows(String lens, String action) {
        if (!opens(lens)) {
            return false;
        }
        for (String key : List.of(ModuleManifest.norm(lens), "*")) {
            List<String> a = actions.get(key);
            if (a != null && (a.contains("*") || a.contains(action))) {
                return true;
            }
        }
        return actions.isEmpty() && "view".equals(action);
    }
}
