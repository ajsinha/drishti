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
package com.ash.drishti.server.bi.python;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;

/** A Python item's own failure (DRS-9070 to 9073), with the author's own traceback when the run was asked by an author (else null). */
public final class PythonFailure extends DrishtiException {

    private static final long serialVersionUID = 1L;
    private final String traceback;

    public PythonFailure(ErrorCode code, String message, String traceback) {
        super(code, message);
        this.traceback = traceback == null || traceback.isBlank() ? null : traceback;
    }

    /** The frames of the author's code and the error, or null. */
    public String traceback() {
        return traceback;
    }
}
