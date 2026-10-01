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

import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.rachana.RachanaSchema;
import com.ash.drishti.rachana.format.Formats;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Rachana language for editors: {@code GET /api/v1/rachana/schema} is the JSON Schema of a Sutra with this server's
 * formats and kinds filled in. Sutra Studio completes and checks with it; any editor that reads JSON Schema can too.
 */
@RestController
@RequestMapping("/api/v1/rachana")
public class RachanaController {

    private final Formats formats;
    private final PackRegistry packs;

    public RachanaController(Formats formats, PackRegistry packs) {
        this.formats = formats;
        this.packs = packs;
    }

    @GetMapping(path = "/schema", produces = "application/schema+json")
    public Map<String, Object> schema() {
        TreeSet<String> kinds = new TreeSet<>();
        packs.packs().forEach(p -> kinds.addAll(p.kinds()));
        return RachanaSchema.build(formats.names(), kinds);
    }
}
