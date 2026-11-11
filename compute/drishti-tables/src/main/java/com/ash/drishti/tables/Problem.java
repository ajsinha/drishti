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
package com.ash.drishti.tables;

/**
 * One problem with a definition: its code ({@link TableCodes}), level ({@code error}, {@code warning}, {@code info}), the key it is about
 * ({@code columns.MTM.path}), the words, and the file or item it was found in (null when not known).
 *
 * @param code the problem code, {@code DRS-....}
 * @param level {@code error} stops the definition being used; {@code warning} and {@code info} do not
 * @param path the key, dotted, as the YAML spells it
 * @param message what is wrong and what to do
 * @param source the file or stored item, or null
 */
public record Problem(String code, String level, String path, String message, String source) {

    public static final String ERROR = "error";
    public static final String WARNING = "warning";
    public static final String INFO = "info";

    public static Problem error(String code, String path, String message) {
        return new Problem(code, ERROR, path, message, null);
    }

    public static Problem warning(String code, String path, String message) {
        return new Problem(code, WARNING, path, message, null);
    }

    public static Problem info(String code, String path, String message) {
        return new Problem(code, INFO, path, message, null);
    }

    public boolean isError() {
        return ERROR.equals(level);
    }

    /** The same problem, found in {@code where} (kept when already set). */
    public Problem in(String where) {
        return source != null ? this : new Problem(code, level, path, message, where);
    }

    /** {@code file: warning DRS-9603 columns.X.path: ...}: one line, as the command line prints it. */
    public String line() {
        return (source == null ? "" : source + ": ") + level + " " + code + (path == null || path.isEmpty() ? "" : " " + path) + ": " + message;
    }
}
