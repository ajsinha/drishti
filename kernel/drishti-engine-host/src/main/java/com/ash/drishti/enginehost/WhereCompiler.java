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
package com.ash.drishti.enginehost;

/**
 * Compiles an engine file's {@code inputs.<name>.where}: one Rachana-EL expression over a row ({@code @.STATUS != 'DEAD'}), hermetic, read by
 * the host only (engine specification 9.3 rule 3). The server's implementation is the one EL compiler every reader uses.
 */
@FunctionalInterface
public interface WhereCompiler {

    /** The rule; IllegalArgumentException in words when the text is not an expression over a row. */
    RowRule compile(String where);
}
