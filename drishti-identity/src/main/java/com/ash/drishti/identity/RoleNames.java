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

import java.util.Set;
import java.util.function.Supplier;

/** The role names users may be given right now: built-in roles plus those administrators defined. */
@FunctionalInterface
public interface RoleNames extends Supplier<Set<String>> {

    static RoleNames of(Set<String> names) {
        Set<String> copy = Set.copyOf(names);
        return () -> copy;
    }
}
