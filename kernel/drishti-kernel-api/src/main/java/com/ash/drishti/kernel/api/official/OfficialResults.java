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
package com.ash.drishti.kernel.api.official;

import java.util.Map;

/**
 * The publishers of official results a module brings (docs/architecture/DOMAIN_MODULES.md, the cycles: J6), found with the
 * {@link java.util.ServiceLoader} before any bean exists: the core names none of them.
 */
public interface OfficialResults {

    /** Each lake of the module's official results (its {@code config/lakes/<lake>.yaml}) to its publisher's settings prefix. */
    Map<String, String> lakes();

    /** Each official kind to its publisher's settings prefix. */
    Map<String, String> kinds();
}
