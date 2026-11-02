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
package com.ash.drishti.server.cube;

/**
 * A dimension a cube view may put on its rows, columns or filters (MR_CUBE_LENS.md section 4.1): an attribute of the members
 * ({@code DESK}, {@code CURRENCY}), a level of a hierarchy ({@code MGMT.DESK}), a value derived from attributes by configuration
 * ({@code RISK_CLASS}), or the members themselves ({@code POSITION}, {@code TRADE}, the last levels of a drill).
 *
 * @param id the word a view names it by
 * @param label what a chip says
 * @param group {@code attribute}, {@code hierarchy}, {@code derived} or {@code member}
 * @param note a sentence: where its values come from
 */
public record CubeDimension(String id, String label, String group, String note) {}
