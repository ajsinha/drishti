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

/**
 * A lens (ACCESS_MODEL.md section 3): an experience a person works in, a curated composition of modules. It is offered only when every
 * module it needs is on; a lens whose modules are off does not exist (no menu, no home, no link).
 *
 * @param id      its id ({@code risk})
 * @param title   the name people read
 * @param modules the modules it needs (the core is implied)
 * @param home    its home page (a console path), or blank
 * @param menus   its menu entries (ids), as the console declares them
 * @param help    its Help pages (slugs)
 * @param source  where it was declared, in words
 */
public record Lens(String id, String title, List<String> modules, String home, List<String> menus, List<String> help, String source) {

    public Lens {
        id = ModuleManifest.norm(id);
        title = title == null || title.isBlank() ? id : title.strip();
        modules = modules == null ? List.of() : modules.stream().map(ModuleManifest::norm).filter(s -> !s.isEmpty()).toList();
        home = home == null ? "" : home.strip();
        menus = menus == null ? List.of() : List.copyOf(menus);
        help = help == null ? List.of() : List.copyOf(help);
        source = source == null ? "" : source;
    }
}
