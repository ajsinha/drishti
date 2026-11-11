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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.rachana.RachanaSchema;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.format.Formats;
import com.ash.drishti.rachana.parse.SutraBlocks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final SutraRegistry sutras;

    public RachanaController(Formats formats, PackRegistry packs, SutraRegistry sutras) {
        this.formats = formats;
        this.packs = packs;
        this.sutras = sutras;
    }

    @GetMapping(path = "/schema", produces = "application/schema+json")
    public Map<String, Object> schema() {
        TreeSet<String> kinds = new TreeSet<>();
        packs.packs().forEach(p -> kinds.addAll(p.kinds()));
        return RachanaSchema.build(formats.names(), kinds);
    }

    /** The shared panel blocks loaded ({@code <name>.v<N>.block.yaml}): name, version, description, parameters and file, no text. */
    @GetMapping("/blocks")
    public Map<String, Object> blocks() {
        List<Map<String, Object>> out = new ArrayList<>();
        SutraBlocks.blocks().forEach(b -> out.add(describe(b, false)));     // each once (a pack's block answers to two names)
        return Map.of("blocks", out);
    }

    /** One shared block with its text, for the designer's read-only view (a block is edited in its file). */
    @GetMapping("/blocks/{name}/{version}")
    public Map<String, Object> block(@PathVariable String name, @PathVariable int version) {
        return SutraBlocks.find(name + "@" + version).map(b -> describe(b, true))
                .orElseThrow(() -> new DrishtiException(ErrorCode.SUTRA_NOT_FOUND, "no shared block " + name + "@" + version));
    }

    private Map<String, Object> describe(SutraBlocks.Block b, boolean text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", b.name());
        m.put("version", b.version());
        m.put("ref", b.ref());
        m.put("description", b.description());
        m.put("params", b.params());
        m.put("file", b.file());
        m.put("source", sutras.relative(b.path()));
        if (text) {
            m.put("text", b.text());
        }
        return m;
    }
}
