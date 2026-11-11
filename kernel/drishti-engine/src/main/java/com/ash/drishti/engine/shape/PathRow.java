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
package com.ash.drishti.engine.shape;

import java.util.List;

/**
 * One path of the shape report.
 *
 * @param path JSONPath-like: {@code $.a.b}, {@code []} for list elements, {@code {}} for map values
 * @param type the JSON types seen, {@code |}-separated ({@code number|null}), or {@code map}
 * @param role the role, or null when the path has none
 * @param reason why the role was chosen
 * @param presence share of its parent records that hold it (1 = always)
 * @param examples up to a few example values; a masked value stays masked
 * @param files the documents that contributed it, when not all did
 * @param conflict the samples disagree on its type
 * @param masked some values were masked
 */
public record PathRow(String path, String type, String role, String reason, double presence, List<String> examples, List<String> files,
        boolean conflict, boolean masked) {}
