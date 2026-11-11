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
package com.ash.drishti.api;

/** The type of a {@link DataNode}. Shape fingerprints are built from these. */
public enum NodeType {
    /** A JSON object. */
    OBJECT('o'),
    /** A JSON array. */
    ARRAY('a'),
    /** A string. */
    STRING('s'),
    /** A number. */
    NUMBER('n'),
    /** A boolean. */
    BOOLEAN('b'),
    /** The JSON null. */
    NULL('z'),
    /** Nothing there: navigation to a field or index that does not exist. */
    MISSING('m');

    private final char code;

    NodeType(char code) {
        this.code = code;
    }

    /** A one-character code used in shape signatures. */
    public char code() {
        return code;
    }

    /** True for a string, number, boolean or null. */
    public boolean isScalar() {
        return this == STRING || this == NUMBER || this == BOOLEAN || this == NULL;
    }
}
