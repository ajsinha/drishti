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
 * What the extractor saw, for the author to read: conflicts and rare fields first, because they are what bites a
 * screen later.
 *
 * @param samples how many documents were merged
 * @param files their names
 * @param conflicts paths whose type differs between samples
 * @param rare optional fields present in few records, rarest first
 * @param paths every path: conflicts first, then rare fields, then the rest in document order
 */
public record ShapeReport(int samples, List<String> files, List<Conflict> conflicts, List<PathRow> rare, List<PathRow> paths) {}
