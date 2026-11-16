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
package com.ash.drishti.api.resolution;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The one module layout (KERNEL_AND_MODULES.md sections 3.2 and 8.10): the names a module folder's top may hold, read from the one list,
 * {@code module-layout.yaml} beside this class (the Python side reads the same file). {@link #CONTENT} is the {@code .dmod} content,
 * {@link #BUILD} what only builds the module. Anything else at a module's top (an old build folder left by a {@code git pull}, a stray
 * file) is not part of the module: readers leave it out and {@link #strays} says so in words.
 */
public final class ModuleLayout {

    /** The names packed into the {@code .dmod} as they are ({@code java/}: only the jars directly in it). */
    public static final Set<String> CONTENT;
    /** The names that only build the module: allowed at its top, never packed. */
    public static final Set<String> BUILD;

    static {
        try (InputStream in = ModuleLayout.class.getResourceAsStream("module-layout.yaml")) {
            if (in == null) {
                throw new IllegalStateException("module-layout.yaml is missing beside " + ModuleLayout.class.getName());
            }
            Map<?, ?> m = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readValue(in, Map.class);
            CONTENT = names(m, "content");
            BUILD = names(m, "build");
        } catch (IOException e) {
            throw new UncheckedIOException("module-layout.yaml does not read: " + e.getMessage(), e);
        }
    }

    private ModuleLayout() {
    }

    private static Set<String> names(Map<?, ?> m, String key) {
        if (!(m.get(key) instanceof List<?> l) || l.isEmpty()) {
            throw new IllegalStateException("module-layout.yaml has no list '" + key + "'");
        }
        return Set.copyOf(l.stream().map(String::valueOf).toList());
    }

    /** Whether a name at a module's top is {@code .dmod} content. */
    public static boolean content(String top) {
        return CONTENT.contains(top);
    }

    /**
     * The entries at a module folder's top that are not part of the layout, each in words, e.g. {@code modules/banking/banking-compute: not
     * part of the module layout, left out (an old build folder? delete it)}. {@code label} names the folder ({@code modules/banking}).
     */
    public static List<String> strays(Path module, String label) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(module)) {
            return out;
        }
        try (Stream<Path> s = Files.list(module)) {
            for (Path p : s.sorted().toList()) {
                String n = p.getFileName().toString();
                if (!CONTENT.contains(n) && !BUILD.contains(n)) {
                    out.add(label + "/" + n + ": not part of the module layout, left out (an old build folder? delete it)");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
