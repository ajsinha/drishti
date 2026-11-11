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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;

/** A Rachana-EL expression failed to compile ({@code DRS-2101}) or to evaluate ({@code DRS-2102}). */
public class ElException extends DrishtiException {

    private static final long serialVersionUID = 1L;
    private final int position;

    public ElException(String message, int position) {
        super(ErrorCode.EL_SYNTAX, message + " at " + position);
        this.position = position;
    }

    public ElException(ErrorCode code, String message) {
        super(code, message);
        this.position = -1;
    }

    /** 0-based offset in the expression, or -1 for evaluation errors. */
    public int position() {
        return position;
    }
}
