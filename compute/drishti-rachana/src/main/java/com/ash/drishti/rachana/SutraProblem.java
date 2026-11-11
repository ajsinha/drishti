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
package com.ash.drishti.rachana;

import com.ash.drishti.rachana.model.SourceLocation;

/**
 * One thing wrong with a Sutra file.
 *
 * @param code stable {@code DRS-2nnn} code
 * @param message what is wrong and how to fix it
 * @param location where
 */
public record SutraProblem(String code, String message, SourceLocation location) {

    @Override
    public String toString() {
        return location + " " + code + " " + message;
    }
}
