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
package com.ash.drishti.enginehost;

import com.ash.engine.EngineApi;
import com.ash.engine.host.Provided;
import com.ash.engine.host.StartChecks;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Function;

/**
 * The host's site settings (ENGINE_INTEGRATION_DESIGN 5.1): where the engine JARs are, the trust level, the licence file, where the engine
 * files are, the instances' work folders, how often health and stats are polled and data versions looked at. Every path is absolute (the
 * server anchors a relative value to {@code drishti.home}, the explicit-paths rule).
 *
 * @param libPath      {@code drishti.engine-lib.path}, default {@code <home>/lib/engines}
 * @param trust        {@code drishti.engine-lib.trust}: {@code folder} (default) or {@code pinned}
 * @param licenceFile  {@code drishti.engine-lib.licence}, default {@code <config>/licence.yaml}
 * @param enginesDir   the engine files' folder ({@code drishti.engines.dir}, default {@code <config>/engines})
 * @param workRoot     each instance's work folder is {@code <workRoot>/<id>} ({@code <data>/engines/work})
 * @param provided     what this server provides to engine JARs
 * @param kitMajor     the major of the kit whose certificates the host accepts
 * @param poll         how often health, stats and data versions are looked at
 */
public record EngineHostSettings(Path libPath, StartChecks.Trust trust, Path licenceFile, Path enginesDir, Path workRoot, Provided provided,
        String kitMajor, Duration poll) {

    /** The platform library versions of this build (drishti-engine-host.properties, filled from the pins of the root pom). */
    public static Provided provided() {
        Properties p = new Properties();
        try (InputStream in = EngineHostSettings.class.getResourceAsStream("/drishti-engine-host.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Provided(EngineApi.VERSION, majorMinor(p.getProperty("tools")), majorMinor(p.getProperty("lake")), majorMinor(p.getProperty("rachana")));
    }

    /**
     * From settings ({@code key -> value or null}), each path anchored to {@code home}. {@code configDir} and {@code dataDir}: the named roots.
     */
    public static EngineHostSettings of(Function<String, String> settings, Path home, Path configDir, Path dataDir) {
        String trust = value(settings, "drishti.engine-lib.trust", "folder").toLowerCase(Locale.ROOT);
        StartChecks.Trust t = switch (trust) {
            case "folder" -> StartChecks.Trust.FOLDER;
            case "pinned" -> StartChecks.Trust.PINNED;
            case "signed" -> throw new IllegalArgumentException("drishti.engine-lib.trust: signed is reserved for when .dmod signing is built; "
                    + "use folder or pinned");
            default -> throw new IllegalArgumentException("drishti.engine-lib.trust: folder or pinned, not " + trust);
        };
        return new EngineHostSettings(anchored(home, value(settings, "drishti.engine-lib.path", "lib/engines")), t,
                anchored(home, value(settings, "drishti.engine-lib.licence", configDir.resolve("licence.yaml").toString())),
                anchored(home, value(settings, "drishti.engines.dir", configDir.resolve("engines").toString())),
                dataDir.resolve("engines").resolve("work").toAbsolutePath().normalize(), provided(), "1",
                Duration.ofSeconds(Long.parseLong(value(settings, "drishti.engine-lib.poll-seconds", "10"))));
    }

    private static String value(Function<String, String> settings, String key, String dflt) {
        String v = settings.apply(key);
        return v == null || v.isBlank() ? dflt : v.strip();
    }

    private static Path anchored(Path home, String v) {
        Path p = Path.of(v);
        return (p.isAbsolute() ? p : home.resolve(p)).toAbsolutePath().normalize();
    }

    /** {@code 1.1.0} to {@code 1.1}; null stays null (the library is not provided). */
    static String majorMinor(String v) {
        if (v == null || v.isBlank() || v.startsWith("${")) {
            return null;
        }
        String[] parts = v.split("-", 2)[0].split("\\.");
        return parts.length < 2 ? parts[0] + ".0" : parts[0] + "." + parts[1];
    }
}
