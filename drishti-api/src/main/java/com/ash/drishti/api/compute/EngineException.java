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
package com.ash.drishti.api.compute;

/**
 * An engine's refusal in words a person may read (<em>the scope is empty</em>, <em>no vectors for 2026-10-23</em>). The panel shows the
 * message; any other exception an engine throws is shown only as <em>the computation failed</em>, with its text in the server's log.
 */
public class EngineException extends Exception {

    public EngineException(String message) {
        super(message);
    }
}
