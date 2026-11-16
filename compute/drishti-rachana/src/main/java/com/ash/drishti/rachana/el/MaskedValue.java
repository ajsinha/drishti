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
package com.ash.drishti.rachana.el;

/**
 * Thrown by a function reading a list element by element when an element is masked ({@link Values#masked}), and turned by
 * {@link #guard} into the masked result: a figure computed from a value the reader may not see is masked, however deep in a list it was.
 * Never leaves the function table (no stack trace is made).
 */
final class MaskedValue extends RuntimeException {

    private static final long serialVersionUID = 1L;

    static final MaskedValue INSTANCE = new MaskedValue();

    private MaskedValue() {
        super("masked", null, false, false);
    }

    /** {@code f}, with a masked element anywhere it reads making its result masked. */
    static ElFunction guard(ElFunction f) {
        return (a, c) -> {
            try {
                return f.apply(a, c);
            } catch (MaskedValue m) {
                return Values.mask();
            }
        };
    }

    /** Throws when {@code v} is masked; returns it otherwise. */
    static Object check(Object v) {
        if (Values.masked(v)) {
            throw INSTANCE;
        }
        return v;
    }
}
