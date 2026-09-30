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
 * A position in a Sutra file, for error messages and Studio highlighting.
 *
 * @param file the file name
 * @param line 1-based line
 * @param column 1-based column
 */
public record SourceLocation(String file, int line, int column) {

    public static final SourceLocation UNKNOWN = new SourceLocation("?", 0, 0);

    @Override
    public String toString() {
        return file + ":" + line + ":" + column;
    }
}
