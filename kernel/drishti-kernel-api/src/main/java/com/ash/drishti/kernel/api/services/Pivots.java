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

import com.ash.drishti.kernel.api.module.InlineFigures;
import java.util.List;
import java.util.Map;

/** The pivot engine on rows a module already holds: what {@code POST /api/v1/pivot/inline} answers. */
public interface Pivots {

    /**
     * The engine's cells of the rows, aggregated by its own functions (compensated sums, exact medians); nothing is read, so no mask
     * beyond the rows' own.
     *
     * @throws Kernel.Refused the pivot engine is switched off, or the view does not read
     */
    CellSet inline(Person p, InlineRequest request);

    /**
     * The engine's figures of rows a module holds ({@code POST /api/v1/pivot/inline}, as {@code p}): the fields, the rows, the measures
     * ({@link InlineFigures#measure}) and the arrangement (one level of rows {@code by} and of columns {@code across}, either null; a
     * top N or null). No rows: nothing is asked and every figure is null. The reading of the engine's answer stays
     * {@code InlinePivotFigures}.
     *
     * @throws Kernel.Refused the pivot engine is switched off, or the view does not read
     * @throws IllegalArgumentException more rows than the engine takes inline ({@link InlineFigures#MAX_ROWS})
     */
    InlineFigures.Figures figures(Person p, List<InlineFigures.Field> fields, List<List<Object>> rows, List<Map<String, Object>> values,
            String by, String across, Map<String, Object> top);
}
