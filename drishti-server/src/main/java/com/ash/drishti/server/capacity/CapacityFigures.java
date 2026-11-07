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
package com.ash.drishti.server.capacity;

import com.ash.drishti.server.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * An engine's live figures for "today against the plan" (Admin → Capacity, docs/architecture/DOMAIN_MODULES.md, the cycles: J3): a module's
 * engine contributes them as a bean, under its own key of the live answer; the core names no module's engine.
 */
public interface CapacityFigures {

    /** The key of its figures in the live answer ({@code var}). */
    String key();

    /**
     * Its figures measured now, as the caller sees them, or null when it has none; it may add the planner's inputs it stands for
     * ({@code Positions}, {@code HotDates} ...) to {@code inputs}.
     */
    Map<String, Object> figures(Principal p, Map<String, Object> inputs);

    /** The rows it adds to the measured-figures table, from its figures: {label, value, unit}. */
    List<Object[]> rows(Map<String, Object> figures);
}
