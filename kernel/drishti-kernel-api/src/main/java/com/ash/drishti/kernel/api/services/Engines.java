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
package com.ash.drishti.kernel.api.services;

import java.util.List;
import java.util.Map;

/**
 * The hosted engines (docs/architecture/ENGINES_IN_DRISHTI.md), asked in process as {@code POST /api/v1/engines/{id}/output} answers
 * them: the same access, row rules, masks, licence and audit, for {@code p}. A module asks by engine id and output name and reads plain
 * values back; it never names an engine's type (R2_CUT_PLAN section 2).
 */
public interface Engines {

    /** True when the engine is installed and running on this node. */
    boolean running(String engine);

    /**
     * An answer as plain data: {@code status}, {@code tables} (each {@code name}, {@code columns}, {@code key}, {@code rows}: maps by
     * column), {@code missing} (words), {@code stamp}, {@code notes}. Doubles are the engine's, unrounded.
     *
     * @throws Kernel.Refused the engine is not installed, not running, refuses the ask, or the person may not open it
     */
    Map<String, Object> ask(Person p, String engine, List<String> outputs, Map<String, Object> params, Map<String, String> snapshot);

    /** As {@link #ask(Person, String, List, Map, Map)}, for a module's request in hand (its {@code Principal}). */
    Map<String, Object> ask(com.ash.drishti.kernel.api.security.Principal p, String engine, List<String> outputs, Map<String, Object> params,
            Map<String, String> snapshot);
}
