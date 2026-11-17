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
package com.ash.drishti.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * Guard G1 (i7a): the server's executable jar is the kernel and nothing else. It holds no {@code .dmod}, no {@code first-party.yaml} and
 * no class of a module's package ({@code com/ash/drishti/<module>/}): a module is a package beside the jar, never inside it. The jar is
 * the one {@code -Ddrishti.exec.jar=FILE} names (the pom sets it: the exec jar of this reactor, built before this project).
 */
class ServerJarHoldsNoModuleTest {

    /** The module packages a class of the kernel's jar must never be in. */
    static final List<String> MODULE_PACKAGES = List.of("com/ash/drishti/banking/", "com/ash/drishti/trading/", "com/ash/drishti/marketdata/",
            "com/ash/drishti/genomics/");

    /** The jars of the modules (and of the build that packs them): never among the server jar's nested libraries. */
    static final List<String> MODULE_JARS = List.of("drishti-modules-", "drishti-banking-", "drishti-compute-banking-", "drishti-trading-",
            "drishti-market-data-", "genomics-");

    @Test
    void theExecJarHoldsNoModule() throws IOException {
        String named = System.getProperty("drishti.exec.jar", "");
        assertThat(named).as("-Ddrishti.exec.jar=FILE names the server jar").isNotBlank();
        List<String> found = holding(Path.of(named));
        assertThat(found).as("entries of a module inside the server jar").isEmpty();
    }

    /** The entries of {@code jar} that belong to a module: a package, its list or a class of a module's package. */
    static List<String> holding(Path jar) throws IOException {
        assertThat(jar).as("the server jar").isRegularFile();
        List<String> out = new ArrayList<>();
        try (JarFile f = new JarFile(jar.toFile())) {
            f.stream().map(e -> e.getName()).forEach(n -> {
                String leaf = n.substring(n.lastIndexOf('/') + 1);
                boolean inModulePackage = MODULE_PACKAGES.stream().anyMatch(p -> n.contains(p));
                boolean moduleJar = n.startsWith("BOOT-INF/lib/") && MODULE_JARS.stream().anyMatch(leaf::startsWith);
                if (leaf.endsWith(".dmod") || leaf.equals("first-party.yaml") || inModulePackage || moduleJar) {
                    out.add(n);
                }
            });
        }
        return out;
    }

    /** The scan itself works: a jar with a module's class and a package is reported (a guard that cannot fail guards nothing). */
    @Test
    void theScanSeesAModuleInAJar(@org.junit.jupiter.api.io.TempDir Path tmp) throws IOException {
        Path jar = tmp.resolve("bad.jar");
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            for (String n : List.of("BOOT-INF/classes/com/ash/drishti/banking/Var.class", "modules/banking-1.0.0.dmod", "BOOT-INF/lib/drishti-modules-1.jar",
                    "BOOT-INF/lib/drishti-server-1.jar", "ok/Kernel.class")) {
                out.putNextEntry(new java.util.zip.ZipEntry(n));
                out.closeEntry();
            }
        }
        assertThat(holding(jar)).containsExactly("BOOT-INF/classes/com/ash/drishti/banking/Var.class", "modules/banking-1.0.0.dmod",
                "BOOT-INF/lib/drishti-modules-1.jar");
    }
}
