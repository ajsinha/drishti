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
package com.ash.drishti.rachana.ui;

/**
 * One problem the board check or the chip publish check found (UI_BOARD.md section 5): the rule, where, what a person can
 * do about it.
 *
 * @param rule {@code BC-01} .. {@code BC-11}, {@code BC-W1} .. {@code BC-W5}, or a publish rule {@code CP-01} .. {@code CP-04}
 * @param level error (blocks save and render) or warning
 * @param path where: {@code chips.trades.map.rows.id}, {@code traces[0].to}, {@code layout.desktop.areas[1]}
 * @param message what is wrong
 * @param fix the obvious fix, or null
 */
public record UiProblem(String rule, Level level, String path, String message, String fix) {

    /** Error or warning. */
    public enum Level { ERROR, WARNING }

    public static UiProblem error(String rule, String path, String message, String fix) {
        return new UiProblem(rule, Level.ERROR, path, message, fix);
    }

    public static UiProblem warning(String rule, String path, String message, String fix) {
        return new UiProblem(rule, Level.WARNING, path, message, fix);
    }

    public boolean error() {
        return level == Level.ERROR;
    }

    @Override
    public String toString() {
        return rule + " " + level.name().toLowerCase(java.util.Locale.ROOT) + " " + path + ": " + message + (fix == null ? "" : " Fix: " + fix);
    }
}
