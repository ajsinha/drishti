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
package com.ash.drishti.server.connectors;

import java.util.Map;
import java.util.Optional;

/**
 * Decides whether a connector whose file has a {@code requires:} block may start (docs/architecture/PYTHON_COMPUTE.md section 6): only when
 * an administrator has approved every library it names. Until then it stays off, its health says why, and a request for approval is filed
 * (Admin → Compute). Implemented by the compute wiring; without it, a connector with requirements never starts.
 */
@FunctionalInterface
public interface RequirementsGate {

    /**
     * Empty when the requirements are approved; otherwise why the connector must stay off, in words (the approval request is filed as a
     * side effect, on behalf of {@code requestedBy}, the person who last saved the file when the server knows it).
     */
    Optional<String> refusal(String connector, Map<String, Object> requires, String requestedBy);

    /** The gate when compute is not wired: nothing with requirements starts. */
    RequirementsGate NONE = (c, r, by) -> Optional.of("compute requirements cannot be approved on this server (drishti-compute is not wired)");
}
