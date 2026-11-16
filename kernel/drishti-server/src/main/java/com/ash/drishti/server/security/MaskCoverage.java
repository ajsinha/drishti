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
package com.ash.drishti.server.security;

import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A field mask that masks nothing fails loudly (DRS-6011): every {@code drishti.security.redact} entry is checked against the
 * field paths of the loaded packs' samples ({@code samples/<kind>.jsonl} or {@code samples/<kind>/*.json}), and an entry that
 * names no field of them is logged as a warning at start-up and listed in Admin → Health ({@code masksMatchingNothing}). Names
 * match in any case and convention ({@link Entitlements#matches}), so a camelCase mask still covers the renamed UPPER_SNAKE field;
 * what is reported is a mask that protects nothing (a typo, a field the data no longer has, a pack that is not loaded).
 */
@Component
public final class MaskCoverage {

    private static final Logger LOG = LoggerFactory.getLogger(MaskCoverage.class);
    private static final int MAX_DOCS_PER_FILE = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SecurityProperties props;
    private final PackRegistry packs;

    public MaskCoverage(SecurityProperties props, PackRegistry packs) {
        this.props = props;
        this.packs = packs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnAtStart() {
        if (!props.enabled()) {
            return;
        }
        for (String entry : unmatched()) {
            LOG.warn("{} the field mask '{}' (drishti.security.redact) names no field of the loaded packs' samples, so it masks nothing there:"
                    + " name the field as the data spells it (UPPER_SNAKE since 2.3.0), or remove the entry", ErrorCode.MASK_MATCHES_NOTHING.code(), entry);
        }
    }

    /** The {@code redact} entries that name no field of the loaded packs' samples; empty when no pack has samples (nothing to check against). */
    public List<String> unmatched() {
        if (props.redact().isEmpty()) {
            return List.of();
        }
        Set<String> paths = new LinkedHashSet<>();
        for (Pack p : packs.packs()) {
            collect(p.dir().resolve("samples"), paths);
        }
        if (paths.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String entry : props.redact()) {
            if (paths.stream().noneMatch(path -> Entitlements.matches(List.of(entry), last(path), path))) {
                out.add(entry);
            }
        }
        return out;
    }

    private static String last(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    private static void collect(Path samples, Set<String> paths) {
        if (!Files.isDirectory(samples)) {
            return;
        }
        try (Stream<Path> files = Files.walk(samples, 2)) {
            files.filter(Files::isRegularFile).forEach(f -> read(f, paths));
        } catch (IOException e) {
            LOG.debug("samples under {} could not be listed", samples, e);
        }
    }

    private static void read(Path f, Set<String> paths) {
        String name = f.getFileName().toString();
        if (com.ash.drishti.common.PackSamples.isCatalog(name) || !(name.endsWith(".json") || name.endsWith(".jsonl"))) {
            return;
        }
        try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            if (name.endsWith(".json")) {
                walk(JSON.readTree(r), "", paths);
                return;
            }
            String line;
            for (int n = 0; n < MAX_DOCS_PER_FILE && (line = r.readLine()) != null; n++) {
                if (!line.isBlank()) {
                    walk(JSON.readTree(line), "", paths);
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("sample {} could not be read for the mask check", f, e);
        }
    }

    private static void walk(JsonNode n, String path, Set<String> paths) {
        if (n == null) {
            return;
        }
        if (n.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = n.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                String at = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                paths.add(at);
                walk(e.getValue(), at, paths);
            }
        } else if (n.isArray()) {
            n.forEach(e -> walk(e, path, paths));
        }
    }
}
