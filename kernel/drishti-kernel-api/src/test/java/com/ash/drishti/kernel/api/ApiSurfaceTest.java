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
package com.ash.drishti.kernel.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The published surface of the kernel API (docs/architecture/KERNEL_AND_MODULES.md section 2.2): the types and the public methods, against
 * {@code src/test/resources/kernel-api-surface.txt}. A change here is an API change: regenerate with {@code -Dsurface.write=true} and say so.
 * Until 1.0 (i2, with japicmp) this baseline and the {@code 0.x} version in META-INF/drishti/kernel-api.properties are the whole check.
 */
class ApiSurfaceTest {

    /** The classes an SPI's signatures carry that are values rather than contracts (records would do; today's shape is kept). */
    private static final Set<String> VALUE_CLASSES = Set.of("pivot.AggFunction", "pivot.AggregationPlan", "pivot.Batch", "pivot.CellKey", "pivot.CellSet",
            "pivot.Dim", "pivot.Partials", "pivot.PivotView", "pivot.Windows", "explain.Explanation", "official.OfficialResults", "shard.ShardGather");

    private static final String ROOT = "com.ash.drishti.kernel.api";

    private static List<Class<?>> types() throws IOException, URISyntaxException, ClassNotFoundException {
        Path root = Path.of(ApiSurfaceTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().resolve("classes");
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                String name = root.relativize(f).toString().replace('/', '.').replace('\\', '.').replaceAll("\\.class$", "");
                Class<?> c = Class.forName(name, false, ApiSurfaceTest.class.getClassLoader());
                if (Modifier.isPublic(c.getModifiers()) && !c.isAnonymousClass()) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    private static String shortName(Class<?> c) {
        return c.getName().substring(ROOT.length() + 1);
    }

    @Test
    void onlyContractsAndNamedValues() throws Exception {
        for (Class<?> c : types()) {
            if (c.getEnclosingClass() != null || c.getSimpleName().equals("package-info")) {
                continue;
            }
            boolean contract = c.isInterface() || c.isRecord() || c.isEnum() || c.isAnnotation();
            assertThat(contract || VALUE_CLASSES.contains(shortName(c))).as("%s: an implementation class in the kernel API", c.getName()).isTrue();
        }
    }

    @Test
    void surfaceIsTheBaseline() throws Exception {
        List<String> now = new ArrayList<>();
        for (Class<?> c : types()) {
            now.add(shortName(c));
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()) {
                    now.add("  " + shortName(c) + " " + m.toGenericString());
                }
            }
        }
        java.util.Collections.sort(now);
        Path file = Path.of("src/test/resources/kernel-api-surface.txt");
        if (Boolean.getBoolean("surface.write")) {
            Files.createDirectories(file.getParent());
            Files.write(file, now, StandardCharsets.UTF_8);
        }
        assertThat(now).isEqualTo(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    @Test
    void versionIsZeroDotSomething() throws Exception {
        Properties p = new Properties();
        try (var in = ApiSurfaceTest.class.getResourceAsStream("/META-INF/drishti/kernel-api.properties")) {
            p.load(in);
        }
        assertThat(p.getProperty("kernelApi")).matches("0\\.\\d+");
    }
}
