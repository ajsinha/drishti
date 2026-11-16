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
 * The access model as a store holds it (ACCESS_MODEL.md section 3), before any decision: the module manifests, the administrator's
 * switches, the licence, the lenses, the roles and the assignments.
 *
 * @param modules     every module known here (installed or not), the core excluded
 * @param switches    the administrator's switch per module, where the store keeps one (module id to on)
 * @param licence     the licence
 * @param lenses      the lenses: the shipped ones, then the site's (a site lens replaces a shipped one of the same id)
 * @param roles       the roles of the access model (empty: none declared, every offered lens is open to everyone)
 * @param assignments who holds which roles
 * @param warnings    what the store could not read, in words (a bad file is skipped, never fatal)
 */
public record AccessModel(List<ModuleManifest> modules, Map<String, Boolean> switches, Licence licence, List<Lens> lenses, List<Role> roles,
        List<Assignment> assignments, List<String> warnings) {

    public AccessModel {
        modules = List.copyOf(modules);
        switches = Map.copyOf(switches);
        lenses = List.copyOf(lenses);
        roles = List.copyOf(roles);
        assignments = List.copyOf(assignments);
        warnings = List.copyOf(warnings);
    }
}
