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
package com.ash.drishti.kernel.api.module;

import java.util.Map;
import java.util.Optional;

/**
 * A module's settings (its {@code settings/schema.json}, docs/architecture/KERNEL_AND_MODULES.md section 2.3), validated at start and on
 * change. Never a secret: modules get none.
 */
public interface ModuleSettings {

    /** One setting, by its dotted key. */
    Optional<String> get(String key);

    /** Every setting, flat. */
    Map<String, String> all();

    /** The settings bound to a typed record; refused when they break the schema. */
    <T extends Record> T bind(Class<T> type);
}
