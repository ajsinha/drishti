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
package com.ash.drishti.server.engines;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.Map;

/**
 * {@code 404 DRS-1020}: the engine is not running on this server, and no other server of the cluster runs it
 * (docs/architecture/COMPUTE_ENGINES.md section 5.1). Not 403: nothing is forbidden, the thing is not here. The answer names the engine:
 * {@code {"code": "DRS-1020", "engine": "frtb-sa", "detail": "FRTB-SA is not running on this server, ..."}}.
 */
public final class EngineOffException extends DrishtiException {

    private static final long serialVersionUID = 1L;
    private final String engine;

    public EngineOffException(String engine, String title) {
        super(ErrorCode.ENGINE_OFF, words(title));
        this.engine = engine;
    }

    /** The words of the refusal, the same on the API, the console and the command line tool. */
    public static String words(String title) {
        return title + " is not running on this server, and no other server of this cluster runs it. An administrator can turn it on in Admin → Engines.";
    }

    public String engine() {
        return engine;
    }

    /** The extra properties of the problem answer. */
    public Map<String, Object> properties() {
        return Map.of("engine", engine);
    }
}
