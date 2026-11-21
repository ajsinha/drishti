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
package com.ash.drishti.testroots;

import com.ash.tools.settings.SettingsFile;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where a test finds the repository's files and its own module's: always absolute, never from the folder the JVM was started in. Under Maven
 * the surefire configuration sets {@code drishti.repo.root} and {@code drishti.module.root}; in an IDE, or any other launcher, they may come
 * from the file named by {@code -Ddrishti.settings.file} ({@link SettingsFile}) and otherwise are derived from the calling test class's own code location (its {@code target/test-classes}): the module is the nearest folder
 * above with a pom.xml, the repository the nearest above that with a pom.xml beside a .mvn folder.
 */
public final class TestRoots {

    private TestRoots() {
    }

    /** The repository root, absolute. */
    public static String repo() {
        String set = explicit("drishti.repo.root");
        if (set != null) return set;
        Path module = locate(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass());
        for (Path d = module; d != null; d = d.getParent()) {
            if (Files.isDirectory(d.resolve(".mvn")) && Files.isRegularFile(d.resolve("pom.xml"))) return d.toString();
        }
        throw new IllegalStateException("no repository root (a folder with .mvn and pom.xml) above " + module);
    }

    /** The module root of the calling test class, absolute. */
    public static String module() {
        String set = explicit("drishti.module.root");
        if (set != null) return set;
        return locate(StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).getCallerClass()).toString();
    }

    /** An explicit -D property, else the settings file named by -Ddrishti.settings.file, else null (then the code location decides). */
    private static String explicit(String key) {
        SettingsFile.applyFrom("drishti.settings.file", "drishti.home");
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? null : v;
    }

    private static Path locate(Class<?> caller) {
        try {
            Path at = Path.of(caller.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            for (Path d = at; d != null; d = d.getParent()) {
                if (Files.isRegularFile(d.resolve("pom.xml"))) return d;
            }
            throw new IllegalStateException("no pom.xml above the code location " + at + " of " + caller.getName());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
