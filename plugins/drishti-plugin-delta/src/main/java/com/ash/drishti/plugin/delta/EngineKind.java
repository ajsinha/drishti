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
package com.ash.drishti.plugin.delta;

import com.ash.drishti.deltalake.LocalPaths;
import com.ash.drishti.deltalake.NativeEngine;
import java.util.Locale;
import java.util.Map;

/**
 * Which Delta Kernel engine reads a lake, from the connector's {@code engine} setting, else the environment variable
 * {@code DRISHTI_DELTA_ENGINE}, else {@code native}:
 *
 * <ul>
 *   <li>{@code native}: {@link NativeEngine}, no Hadoop (local disk and S3); the only one that works on Windows
 *       without {@code winutils.exe};
 *   <li>{@code hadoop}: Kernel's default engine over Hadoop's file systems (also Azure {@code abfs://}, Google
 *       {@code gs://}, HDFS);
 *   <li>{@code auto}: native on Windows, Hadoop elsewhere (and Hadoop for a scheme the native engine does not read).
 * </ul>
 */
enum EngineKind {
    NATIVE,
    HADOOP;

    /** The environment variable that sets the engine for every Delta connector without its own {@code engine}. */
    static final String ENV = "DRISHTI_DELTA_ENGINE";

    /** The engine when nothing is configured. */
    static final String DEFAULT = "native";

    /** The setting's value as shown in Health ({@code native}, {@code hadoop}). */
    String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The engine for a connector's settings, the process environment and this operating system. */
    static EngineKind of(Map<String, String> settings, String root) {
        return choose(settings.get("engine"), System.getenv(ENV), root, LocalPaths.WINDOWS);
    }

    /**
     * @param setting the connector's {@code engine}, or null
     * @param env {@code DRISHTI_DELTA_ENGINE}, or null
     * @param root the lake's root (a path, or a URI such as {@code s3a://bucket/lake})
     * @param windows whether this is Windows (for {@code auto})
     */
    static EngineKind choose(String setting, String env, String root, boolean windows) {
        String v = setting != null && !setting.isBlank() ? setting : env != null && !env.isBlank() ? env : DEFAULT;
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "native" -> {
                if (!NativeEngine.supports(root)) {
                    throw new IllegalArgumentException("the native Delta engine reads local disk and S3 (s3://, s3a://), not " + root
                            + "; set the connector's engine: hadoop (or DRISHTI_DELTA_ENGINE=hadoop) for this lake");
                }
                yield NATIVE;
            }
            case "hadoop" -> HADOOP;
            case "auto" -> windows && NativeEngine.supports(root) ? NATIVE : HADOOP;
            default -> throw new IllegalArgumentException("unknown Delta engine '" + v + "': use native, hadoop or auto");
        };
    }
}
