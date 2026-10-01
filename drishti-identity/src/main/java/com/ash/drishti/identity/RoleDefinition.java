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
package com.ash.drishti.identity;

import java.time.Instant;
import java.util.List;

/**
 * A role: the kinds it may open ({@code *} for all) and what else it may do.
 *
 * @param name the role name users are given ({@code [a-z][a-z0-9-]{1,63}})
 * @param description what it is for
 * @param kinds kinds it may open; {@code *} is every kind
 * @param raw may see unredacted raw JSON
 * @param author may write Sutras in Studio
 * @param approve may approve proposed Sutras
 * @param admin may administer users, roles, caches and packs
 * @param calc may use Calc: Python run in the browser on what the holder may open (PYTHON_CALC.md)
 * @param builtIn defined in configuration or a pack (read-only in the UI) rather than by an administrator
 * @param updatedAt last change (null for built-in roles)
 * @param updatedBy who made it (empty for built-in roles)
 */
public record RoleDefinition(String name, String description, List<String> kinds, boolean raw, boolean author, boolean approve,
        boolean admin, boolean calc, boolean builtIn, Instant updatedAt, String updatedBy) {

    public RoleDefinition {
        description = description == null ? "" : description;
        kinds = kinds == null ? List.of() : List.copyOf(kinds);
        updatedBy = updatedBy == null ? "" : updatedBy;
    }

    public boolean mayOpen(String kind) {
        return kinds.contains("*") || kinds.contains(kind);
    }
}
