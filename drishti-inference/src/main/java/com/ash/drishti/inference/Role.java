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
package com.ash.drishti.inference;

/**
 * What a field means, as far as layout is concerned.
 *
 * @param name role name ({@code money}, {@code rate}, {@code date}, ...), or {@code plain}
 * @param fmt the format to show it with, or null
 * @param tone the tone rule, or null
 * @param weight rank for the header strip; higher first
 */
public record Role(String name, String fmt, String tone, int weight) {

    public static final Role PLAIN = new Role("plain", null, null, 10);

    public boolean numeric() {
        return fmt != null && !"date".equals(fmt);
    }
}
