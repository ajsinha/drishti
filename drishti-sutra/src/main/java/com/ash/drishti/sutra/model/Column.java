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
package com.ash.drishti.sutra.model;

/**
 * A table, ladder or kv column.
 *
 * @param label header text
 * @param bind Sutra-EL expression evaluated per row ({@code @} is the row)
 * @param fmt named format, or null
 * @param tone colouring rule, or null
 * @param total sum this column into the total row
 * @param link wrap the value as a link to another entity
 */
public record Column(String label, String bind, String fmt, String tone, boolean total, boolean link) {}
