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
package com.ash.drishti.server.api;

/**
 * A figure a module's engine adds to {@code /api/v1/health} (docs/architecture/DOMAIN_MODULES.md, the cycles: J4), under its own key: the
 * banking module's {@code hierarchiesSkipped}, for one. The core names no module's figure.
 */
public interface HealthFigures {

    /** Its key in the health answer. */
    String key();

    /** Its value now. */
    Object value();
}
