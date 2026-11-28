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
package com.ash.drishti.kernel.api.home;

import com.ash.drishti.kernel.api.security.Principal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A home table: read-only rows served for the person, under the {@code home/<name>@1} contract (docs/contracts/HOME.md). A module adds
 * its own (the trader's positions and P&amp;L) by registering a bean of this type; the server's own are in {@link HomeTables}.
 */
public interface HomeTable {

    /** The table's name, as a Home Sutra's {@code data:} entry says ({@code home_positions}). */
    String name();

    /**
     * The Homes this table is served to (empty: any Home). A module's own table names the Homes of its domain, so a person who is not offered
     * one of them never reads it, whichever Home they ask through.
     */
    default List<String> homes() {
        return List.of();
    }

    /** The rows for the person; empty when the engine behind it has nothing for them (the fragment says so in its own words). */
    List<Map<String, Object>> rows(Context c);

    /** What a table is asked with. */
    record Context(Principal person, HomeDefinition home, LocalDate today, LocalDate previous, Instant since) {

        /** A table asked without a window: {@code since} is empty and the table reads from the Home's cut-off. */
        public Context(Principal person, HomeDefinition home, LocalDate today, LocalDate previous) {
            this(person, home, today, previous, null);
        }
    }
}
