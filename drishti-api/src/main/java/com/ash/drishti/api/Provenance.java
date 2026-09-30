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
package com.ash.drishti.api;

import java.time.Instant;

/**
 * Where a document came from: the source system, its monotonic generation, and when it was read.
 *
 * @param source the source system name as users know it (for example {@code aero-risk})
 * @param generation a monotonic version; a higher generation is newer data
 * @param fetchedAt when the document was read
 * @param live whether the source can push updates for this document
 */
public record Provenance(String source, long generation, Instant fetchedAt, boolean live) {}
