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
package com.ash.drishti.server.compute;

import com.ash.drishti.api.compute.ComputeEngine;
import com.ash.drishti.api.compute.EngineInputs;
import com.ash.drishti.api.compute.EngineManifest;
import com.ash.drishti.api.compute.EngineProgress;
import com.ash.drishti.api.compute.EngineRequest;
import com.ash.drishti.api.compute.EngineResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A Java engine for the server's reactive tests: twice its parameter, who it computed for, how many inputs it was given. */
public final class EchoTestEngine implements ComputeEngine {

    @Override
    public EngineManifest manifest() {
        return new EngineManifest("echo-test", "1", List.of("echo"));
    }

    @Override
    public EngineResult compute(EngineRequest req, EngineInputs inputs, EngineProgress progress) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("SUM", Long.parseLong(String.valueOf(req.param("x", 0))) * 2);
        row.put("USER", req.viewer().user());
        row.put("SIGNATURE", req.viewer().viewSignature());
        row.put("INPUTS", inputs.size());
        return EngineResult.of(row);
    }
}
