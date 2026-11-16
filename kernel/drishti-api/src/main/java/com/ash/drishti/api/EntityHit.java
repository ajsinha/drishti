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

/**
 * One search result for command-line suggestions.
 *
 * @param ref the entity
 * @param title primary text, usually the identifier or name (for example {@code IRS-48213})
 * @param subtitle a one-line description (for example {@code Interest rate swap · Northbridge · USD 50m})
 */
public record EntityHit(EntityRef ref, String title, String subtitle) {}
