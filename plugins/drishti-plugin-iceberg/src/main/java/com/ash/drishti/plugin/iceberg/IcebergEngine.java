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
package com.ash.drishti.plugin.iceberg;

import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.lakeio.LocalPaths;
import java.util.Locale;
import java.util.Map;

/**
 * Which engine reads (and writes) a connector's Iceberg tables, from its {@code engine} setting, else the environment
 * variable {@code DRISHTI_ICEBERG_ENGINE}, else the default, {@code native} where it can read the tables:
 *
 * <ul>
 *   <li>{@code native} (the default): drishti-iceberg, no Hadoop (local disk and S3, path-based and REST catalogs); the
 *       only one that works on Windows without {@code winutils.exe}. Asked for by name with a root it cannot read
 *       ({@code abfs://}, {@code gs://}, {@code hdfs://}), the connector refuses to start; left to the default, such a
 *       root is read by the library;</li>
 *   <li>{@code library}: Apache Iceberg Java over Hadoop's file systems (also Azure {@code abfs://}, Google
 *       {@code gs://}, HDFS);</li>
 *   <li>{@code auto}: native on Windows, the library elsewhere (and the library for a root the native engine does not
 *       read).</li>
 * </ul>
 */
enum IcebergEngine {
    NATIVE,
    LIBRARY;

    /** The environment variable that sets the engine for every Iceberg connector without its own {@code engine}. */
    static final String ENV = "DRISHTI_ICEBERG_ENGINE";

    /** The engine when nothing is configured. */
    static final String DEFAULT = "native";

    /** The setting's value as Health shows it ({@code native}, {@code library}). */
    String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The engine for a connector's settings, the process environment and this operating system. */
    static IcebergEngine of(Map<String, String> settings) {
        String catalog = settings.getOrDefault("catalog", "hadoop").trim();
        String root = catalog.equalsIgnoreCase("rest") ? "" : settings.getOrDefault("root", "");
        return choose(settings.get("engine"), System.getenv(ENV), root, LocalPaths.WINDOWS);
    }

    /**
     * @param setting the connector's (or the loader's {@code --engine}) value, or null
     * @param env {@code DRISHTI_ICEBERG_ENGINE}, or null
     * @param root a path-based lake's root (blank for a REST catalog, whose files are named by the catalog)
     * @param windows whether this is Windows (for {@code auto})
     */
    static IcebergEngine choose(String setting, String env, String root, boolean windows) {
        boolean given = setting != null && !setting.isBlank() || env != null && !env.isBlank();
        String v = setting != null && !setting.isBlank() ? setting : env != null && !env.isBlank() ? env : DEFAULT;
        boolean readable = root == null || root.isBlank() || FileAccess.supports(root);
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "native" -> {
                if (!readable && given) {
                    throw new IllegalArgumentException("the native Iceberg engine reads local disk and S3 (s3://, s3a://), not " + root
                            + "; set the connector's engine: library (or DRISHTI_ICEBERG_ENGINE=library) for this lake");
                }
                yield readable ? NATIVE : LIBRARY;
            }
            case "library" -> LIBRARY;
            case "auto" -> windows && readable ? NATIVE : LIBRARY;
            default -> throw new IllegalArgumentException("unknown Iceberg engine '" + v + "': use native, library or auto");
        };
    }
}
