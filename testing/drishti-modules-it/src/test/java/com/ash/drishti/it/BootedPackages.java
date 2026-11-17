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

import com.ash.drishti.api.resolution.ModuleHomes;
import com.ash.drishti.packs.LaunchRoots;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Module packages booted the way a launch boots them, for a test that reads modules without starting a server (a loader, the settings
 * post-processor): the named {@code .dmod}s of this project's packages ({@code drishti.modules.home}, target/modules-home) are copied into a
 * modules folder of the test's own, and {@link LaunchRoots#bootModules} verifies, unpacks and publishes them. The settings it returns
 * ({@link LaunchRoots.Booted#properties()}) are what the launch puts first for every reader: {@code drishti.module.homes} and the
 * {@code drishti.module.dir.<id>} keys. Never the repository's module sources.
 */
public final class BootedPackages {

    private BootedPackages() {
    }

    /** The packages this project's tests run on (the pom's drishti.modules.home). */
    public static Path packages() {
        String home = System.getProperty("drishti.modules.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("drishti.modules.home is not set: the pom names target/modules-home");
        }
        return Path.of(home).toAbsolutePath().normalize();
    }

    /**
     * Boots the packages of {@code ids} from a modules folder under {@code work} ({@code work/modules}, its cache under {@code work/data}).
     *
     * @return the boot's settings, the launch's: {@code drishti.module.homes} and one {@code drishti.module.dir.<id>} per module
     */
    public static Map<String, String> boot(Path work, String... ids) {
        try {
            Path modules = Files.createDirectories(work.resolve("modules"));
            for (String id : ids) {
                Path dmod;
                try (var s = Files.list(packages())) {
                    dmod = s.filter(p -> p.getFileName().toString().startsWith(id + "-") && p.getFileName().toString().endsWith(".dmod")).findFirst()
                            .orElseThrow(() -> new IllegalStateException("no package of " + id + " in " + packages()));
                }
                Files.copy(dmod, modules.resolve(dmod.getFileName().toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            LaunchRoots.Booted b = LaunchRoots.bootModules(modules, Files.createDirectories(work.resolve("data")), false, List.of(),
                    work.resolve("views"), false);
            if (!b.homes().homes().keySet().containsAll(List.of(ids))) {
                throw new IllegalStateException("the boot refused a package: " + b.report());
            }
            return b.properties();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The folder a booted module's files are read from (its unpacked package), from the settings {@link #boot} returned. */
    public static Path dir(Map<String, String> booted, String id) {
        return ModuleHomes.fromProperty(booted.get(ModuleHomes.PROPERTY)).homes().get(id).dir();
    }
}
