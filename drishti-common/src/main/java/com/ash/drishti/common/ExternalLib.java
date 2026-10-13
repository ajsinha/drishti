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
package com.ash.drishti.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The external library folder (docs/admin/CONFIGURATION.md, "External libraries"): {@code <base>/java} holds jars the
 * server loads at start (put on the class path by the launcher, {@code loader.properties}) and {@code <base>/python}
 * holds modules and packages the console makes importable. The base is {@code DRISHTI_EXTERNAL_LIB} (or the system
 * property {@code drishti.external-lib}), default {@code external_lib} under the working directory.
 */
public final class ExternalLib {

    private ExternalLib() {}

    /** The configured base folder (not checked for existence). */
    public static Path base() {
        String p = System.getProperty("drishti.external-lib");
        if (p == null || p.isBlank()) {
            p = System.getenv("DRISHTI_EXTERNAL_LIB");
        }
        return Path.of(p == null || p.isBlank() ? "external_lib" : p).toAbsolutePath().normalize();
    }

    /** What Health shows: the base, and the jars of {@code <base>/java} with their sizes. */
    public static Map<String, Object> info() {
        Path java = base().resolve("java");
        List<Map<String, Object>> jars = new ArrayList<>();
        if (Files.isDirectory(java)) {
            try (Stream<Path> s = Files.list(java)) {
                s.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().forEach(f -> {
                    Map<String, Object> j = new LinkedHashMap<>();
                    j.put("name", f.getFileName().toString());
                    try {
                        j.put("bytes", Files.size(f));
                    } catch (IOException e) {
                        j.put("bytes", -1L);
                    }
                    jars.add(j);
                });
            } catch (IOException e) {
                // an unreadable folder shows as no jars
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("base", base().toString());
        out.put("java", java.toString());
        out.put("jars", jars);
        return out;
    }
}
