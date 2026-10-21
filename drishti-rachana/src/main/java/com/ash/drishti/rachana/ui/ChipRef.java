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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A chip and its version, {@code name@version}, as a board's {@code use:} names it.
 *
 * @param name the chip's name
 * @param version its version
 */
public record ChipRef(String name, int version) {

    /** A chip or instance name: lower-case letters, digits and hyphens, starting with a letter, up to 64. */
    public static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    private static final Pattern USE = Pattern.compile("([a-z][a-z0-9-]{0,63})@([1-9][0-9]{0,8})");

    /** {@code name@version}, or empty when the text is not one (an unversioned {@code use} is refused, BC-02). */
    public static Optional<ChipRef> parse(String use) {
        if (use == null) {
            return Optional.empty();
        }
        Matcher m = USE.matcher(use.strip());
        return m.matches() ? Optional.of(new ChipRef(m.group(1), Integer.parseInt(m.group(2)))) : Optional.empty();
    }

    @Override
    public String toString() {
        return name + "@" + version;
    }
}
