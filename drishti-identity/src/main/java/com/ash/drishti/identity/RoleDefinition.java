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
 * @param raw sees every field: nothing in {@code drishti.security.redact} is masked for the role, on any path
 * @param author may write Sutras in Studio
 * @param approve may approve proposed Sutras
 * @param admin may administer users, roles, caches and packs
 * @param calc may use Calc: Python run in the browser on what the holder may open (PYTHON_CALC.md)
 * @param layout may customise layouts: arrange a view's panels and keep the arrangement (USER_GUIDE.md, Layout mode)
 * @param collaborate may share, comment and mention (docs/architecture/COLLABORATION.md); true unless a role is marked otherwise
 * @param compliance may place legal holds, export the collaboration record and read any share; false unless granted
 * @param builtIn defined in configuration or a pack (read-only in the UI) rather than by an administrator
 * @param updatedAt last change (null for built-in roles)
 * @param updatedBy who made it (empty for built-in roles)
 * @param bi business-intelligence powers held ({@link #BI_POWERS}); each one above {@code bi} also needs {@code bi} to open the page
 * @param edit may submit changes through write-back (WRITE_BACK.md › The design and its reasons, section 12: the {@code edit} power); false unless granted
 */
public record RoleDefinition(String name, String description, List<String> kinds, boolean raw, boolean author, boolean approve,
        boolean admin, boolean calc, boolean layout, boolean collaborate, boolean compliance, boolean builtIn, Instant updatedAt, String updatedBy,
        java.util.Set<String> bi, boolean edit) {

    /** A role without the write-back {@code edit} power. */
    public RoleDefinition(String name, String description, List<String> kinds, boolean raw, boolean author, boolean approve, boolean admin,
            boolean calc, boolean layout, boolean collaborate, boolean compliance, boolean builtIn, Instant updatedAt, String updatedBy,
            java.util.Set<String> bi) {
        this(name, description, kinds, raw, author, approve, admin, calc, layout, collaborate, compliance, builtIn, updatedAt, updatedBy, bi, false);
    }

    /** The business-intelligence powers (Rupaka): open BI, author, steward (certify), administer BI settings. */
    public static final List<String> BI_POWERS = List.of("bi", "bi-author", "bi-steward", "bi-admin");

    /** A role without business-intelligence powers. */
    public RoleDefinition(String name, String description, List<String> kinds, boolean raw, boolean author, boolean approve, boolean admin,
            boolean calc, boolean layout, boolean collaborate, boolean compliance, boolean builtIn, Instant updatedAt, String updatedBy) {
        this(name, description, kinds, raw, author, approve, admin, calc, layout, collaborate, compliance, builtIn, updatedAt, updatedBy, java.util.Set.of());
    }

    public RoleDefinition {
        bi = bi == null ? java.util.Set.of() : java.util.Set.copyOf(bi);
        description = description == null ? "" : description;
        kinds = kinds == null ? List.of() : List.copyOf(kinds);
        updatedBy = updatedBy == null ? "" : updatedBy;
    }

    public boolean mayOpen(String kind) {
        return kinds.contains("*") || kinds.contains(kind);
    }
}
