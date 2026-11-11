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
package com.ash.drishti.rachana.design.ops;

/**
 * One thing wrong with one operation of a list. The operation was not applied; the Sutra text is as it was before it.
 *
 * @param op index of the operation in the list (0-based)
 * @param name the operation's name ({@code addPanel}, {@code setOption}, ...)
 * @param code stable {@code DRS-5nnn} (the operation) or {@code DRS-2nnn} (the Sutra the operation would have made)
 * @param message what is wrong and how to fix it
 * @param line 1-based line in the Sutra text the operation worked on, or 0
 */
public record OpProblem(int op, String name, String code, String message, int line) {}
