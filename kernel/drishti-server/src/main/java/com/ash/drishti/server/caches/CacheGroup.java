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
package com.ash.drishti.server.caches;

/**
 * An engine's caches joining the memory budget once the server is up (docs/architecture/DOMAIN_MODULES.md, the cycles: J2): each module's
 * engine contributes its group as a bean, and Admin → Caches lists them with the rest; the core names no module's cache. Ordered by
 * {@link org.springframework.core.annotation.Order}.
 */
@FunctionalInterface
public interface CacheGroup {

    /** Joins the engine's caches to the budget's pools ({@code engine.answers}, {@code small} ...). */
    void join(MemoryBudget budget);
}
