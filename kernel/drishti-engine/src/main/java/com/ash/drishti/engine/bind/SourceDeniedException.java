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
package com.ash.drishti.engine.bind;

import com.ash.drishti.api.EntityRef;

/** A panel's {@code source} names an entity of a kind the caller may not open: the panel shows "no access", not data. */
public final class SourceDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final String kind;

    public SourceDeniedException(EntityRef source) {
        super("no access to " + source.kind(), null, false, false);
        this.kind = source.kind();
    }

    public String kind() {
        return kind;
    }
}
