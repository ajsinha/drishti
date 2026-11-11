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

import java.util.Set;

/**
 * The person a question is about: their name, groups and the roles the identity layer gives them. The registry adds the roles the access
 * model assigns ({@code config/access/assignments.yaml}).
 *
 * @param user   the person's name
 * @param groups their groups
 * @param roles  their roles from the identity layer
 * @param admin  true for an administrator (every lens offered is open to them)
 */
public record Subject(String user, Set<String> groups, Set<String> roles, boolean admin) {

    public Subject {
        user = user == null ? "" : user;
        groups = groups == null ? Set.of() : Set.copyOf(groups);
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
