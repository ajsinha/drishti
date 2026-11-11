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
package com.ash.drishti.server.admin;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;

/** An action Admin, Artifacts refused because other things depend on the artifact; carries the impact so the answer can list it. */
public final class ArtifactRefused extends DrishtiException {

    private static final long serialVersionUID = 1L;
    private final transient ArtifactImpact.Impact impact;

    public ArtifactRefused(String message, ArtifactImpact.Impact impact) {
        super(ErrorCode.ARTIFACT_IN_USE, message);
        this.impact = impact;
    }

    public ArtifactImpact.Impact impact() {
        return impact;
    }
}
