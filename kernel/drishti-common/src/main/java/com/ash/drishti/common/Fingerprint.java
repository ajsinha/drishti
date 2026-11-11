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
package com.ash.drishti.common;

/**
 * A 64-bit shape fingerprint. Two documents with the same fields and types share a fingerprint
 * regardless of values or array lengths.
 *
 * @param value the hash
 */
public record Fingerprint(long value) {

    public String hex() {
        return String.format("%016x", value);
    }

    /** The short form shown in views: {@code a91c…71e4}. */
    public String shortForm() {
        String h = hex();
        return h.substring(0, 4) + "…" + h.substring(12);
    }

    @Override
    public String toString() {
        return hex();
    }
}
