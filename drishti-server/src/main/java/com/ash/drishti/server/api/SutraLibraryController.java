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
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.parse.SutraBlocks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The fragment library with its docstrings, for a component browser or an AI agent (Sutra Next phase 1, SUTRA_NEXT.md):
 * {@code GET /api/v1/sutras/library} lists every fragment ({@code <name>.v<N>.fragment.yaml}) with its {@code doc:}, its
 * parameters and defaults and its source file; {@code ?blocks=true} adds the older shared blocks (their doc may be empty).
 * {@code GET /api/v1/sutras/library/{name}/{version}} reads one with its text. Read-only; a fragment is edited in its file.
 */
@RestController
@RequestMapping("/api/v1/sutras/library")
public class SutraLibraryController {

    private final SutraRegistry sutras;

    public SutraLibraryController(SutraRegistry sutras) {
        this.sutras = sutras;
    }

    @GetMapping
    public Map<String, Object> library(@RequestParam(name = "blocks", defaultValue = "false") boolean blocks) {
        List<Map<String, Object>> out = new ArrayList<>();
        SutraBlocks.library().values().forEach(vs -> vs.values().stream()
                .filter(b -> blocks || b.fragment())
                .forEach(b -> out.add(describe(b, false))));
        return Map.of("fragments", out, "count", out.size());
    }

    @GetMapping("/{name}/{version}")
    public Map<String, Object> fragment(@PathVariable String name, @PathVariable int version) {
        return SutraBlocks.find(name + "@" + version).map(b -> describe(b, true))
                .orElseThrow(() -> new DrishtiException(ErrorCode.SUTRA_NOT_FOUND, "no fragment " + name + "@" + version));
    }

    private Map<String, Object> describe(SutraBlocks.Block b, boolean text) {
        Map<String, Object> doc = b.doc();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", b.ref());
        m.put("name", b.name());
        m.put("version", b.version());
        m.put("fragment", b.fragment());
        m.put("summary", doc.getOrDefault("summary", b.description()));
        m.put("params", b.params());
        m.put("doc", doc);
        m.put("source", sutras.relative(b.path()));
        if (text) {
            m.put("text", b.text());
        }
        return m;
    }
}
