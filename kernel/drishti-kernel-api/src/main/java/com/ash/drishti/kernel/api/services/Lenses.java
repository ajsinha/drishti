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

import java.util.Map;

/** The blocks a module keeps in a lens's file ({@code config/lenses/<module>/<lens>.yaml}). */
public interface Lenses {

    /**
     * A block of a lens's file as the server reads it (the trading landings' {@code trading:}, the quality landing's {@code quality:});
     * empty for no such lens or block.
     */
    Map<String, Object> block(String lens, String block);
}
