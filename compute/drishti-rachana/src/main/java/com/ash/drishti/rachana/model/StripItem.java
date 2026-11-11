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
package com.ash.drishti.rachana.model;

/**
 * One key figure in the header strip.
 *
 * @param label the label
 * @param bind Rachana-EL expression for the value
 * @param fmt named format, or null
 * @param tone colouring rule ({@code sign}, {@code status}), or null
 * @param emphasis draw the value highlighted (MTM in the mockups)
 * @param location where it was declared
 */
public record StripItem(String label, String bind, String fmt, String tone, boolean emphasis, SourceLocation location) {}
