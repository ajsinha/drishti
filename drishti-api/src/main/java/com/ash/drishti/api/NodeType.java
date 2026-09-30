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
    OBJECT('o'),
    ARRAY('a'),
    STRING('s'),
    NUMBER('n'),
    BOOLEAN('b'),
    NULL('z'),
    MISSING('m');

    private final char code;

    NodeType(char code) {
        this.code = code;
    }

    /** A one-character code used in shape signatures. */
    public char code() {
        return code;
    }

    public boolean isScalar() {
        return this == STRING || this == NUMBER || this == BOOLEAN || this == NULL;
    }
}
