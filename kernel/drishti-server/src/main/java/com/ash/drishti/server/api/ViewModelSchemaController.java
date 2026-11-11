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
package com.ash.drishti.server.api;

import com.ash.drishti.engine.view.ViewModelSchema;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The contract of every entity screen for clients: {@code GET /api/v1/viewmodel/schema} is the JSON Schema of the ViewModel,
 * its panels' {@code data} and the live stream's frames (docs/architecture/VIEWMODEL.md). Read only; generated from the records.
 */
@RestController
@RequestMapping("/api/v1/viewmodel")
public class ViewModelSchemaController {

    private static final Map<String, Object> SCHEMA = ViewModelSchema.build();

    @GetMapping(path = "/schema", produces = "application/schema+json")
    public Map<String, Object> schema() {
        return SCHEMA;
    }
}
