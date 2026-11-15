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
package com.ash.drishti.net.agreement;

/**
 * A change that needs one answer was asked while the cluster cannot decide (no majority of voters, the referee database unreachable):
 * nothing was changed. The server answers it as {@code 503 DRS-5060} with {@link #getMessage()} (CLUSTER_I5_DESIGN.md section 4.3).
 */
public final class AgreementUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AgreementUnavailableException(String message) {
        super(message);
    }

    public AgreementUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
