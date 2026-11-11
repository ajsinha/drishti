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
package com.ash.drishti.compute.linalg;

/** The bundled OpenBLAS cannot be used here: {@link Blas} binds {@link JavaBlas} instead and says {@link #reason()} loudly. Never stops the server. */
final class BlasUnavailableException extends RuntimeException {

    private final String reason;

    BlasUnavailableException(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    /** Why. */
    String reason() {
        return reason;
    }
}
