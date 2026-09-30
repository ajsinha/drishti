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
package com.ash.drishti.rachana.format;

import com.ash.drishti.rachana.el.Values;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * The named formats used by {@code fmt:} and {@code fmt(...)}: the bundled {@code formats.yaml}, optionally
 * overridden by a site file. Immutable after construction.
 */
public final class Formats {

    private final Map<String, FormatSpec> specs;

    private Formats(Map<String, FormatSpec> specs) {
        this.specs = Map.copyOf(specs);
    }

    /** The bundled formats, plus overrides from {@code overrideFile} when it exists. */
    public static Formats load(String overrideFile) {
        Map<String, FormatSpec> m = new HashMap<>();
        try (InputStream in = Formats.class.getClassLoader().getResourceAsStream("formats.yaml")) {
            read(in, m);
            if (overrideFile != null && !overrideFile.isBlank() && Files.isRegularFile(Path.of(overrideFile))) {
                try (InputStream o = Files.newInputStream(Path.of(overrideFile))) {
                    read(o, m);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Formats(m);
    }

    public static Formats defaults() {
        return load(null);
    }

    private static void read(InputStream in, Map<String, FormatSpec> into) throws IOException {
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(in).path("formats");
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode f = e.getValue();
            into.put(e.getKey(), new FormatSpec(f.path("type").asText("number"), f.path("decimals").asInt(0),
                    f.path("grouping").asBoolean(true), f.path("sign").asText("auto"), f.path("scale").asDouble(1),
                    f.hasNonNull("suffix") ? f.get("suffix").asText() : null,
                    f.hasNonNull("pattern") ? f.get("pattern").asText() : null));
        }
    }

    public boolean has(String name) {
        return specs.containsKey(name);
    }

    public Set<String> names() {
        return specs.keySet();
    }

    /** Formats {@code value}; an unknown or null format name falls back to plain text. */
    public String format(String name, Object value) {
        FormatSpec s = name == null ? null : specs.get(name);
        return s == null ? Values.text(value) : s.format(value);
    }
}
