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

import io.delta.kernel.engine.Engine;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Where a Delta Lake lives and the Kernel engine that reads it, so the connector reads a lake on local disk and one
 * in object storage the same way. A {@code root} with a scheme ({@code s3a://bucket/lake}, {@code abfs://…},
 * {@code gs://…}) is object storage; anything else (including {@code C:\lakes} and {@code file:} URIs) is a local
 * directory. The connector only asks for a table's URI, the list of tables, and the engine.
 *
 * <p>Two engines ({@link EngineKind}, the {@code engine} setting): {@link NativeLake} reads local disk through
 * {@code java.nio} and S3 through the AWS SDK, with no Hadoop (so it works on Windows without {@code winutils.exe});
 * {@link HadoopLake} reads through Hadoop's file systems (also Azure, Google Cloud Storage and HDFS).
 */
public interface LakeStore extends AutoCloseable {

    /** The Delta table URI for {@code kind}. */
    String table(String kind);

    /** The kinds that have a Delta table ({@code <kind>/_delta_log}) under the domain, sorted. */
    List<String> tables() throws IOException;

    /** Whether the domain's folder exists (for health). */
    boolean reachable();

    /** For messages: where this is. */
    String describe();

    /** The Kernel engine that reads this lake, shared by every read. */
    Engine engine();

    /** {@code native} or {@code hadoop}, for Health. */
    String engineName();

    /** Releases the engine's clients (an S3 connection pool). */
    @Override
    default void close() {}

    /**
     * The lake at {@code root}, read by the engine the settings choose ({@link EngineKind}).
     *
     * @param root {@code ./data/delta}, {@code C:\lakes\risk}, {@code /lakes/risk}, or a URI {@code s3a://bucket/lake}
     * @param domain the data domain folder under the root (may be blank)
     * @param settings the connector settings: {@code engine} ({@code native}, {@code hadoop}, {@code auto}); for S3,
     *     {@code s3.endpoint}, {@code s3.access-key}, {@code s3.secret-key}, {@code s3.region}, {@code s3.path-style}
     *     (credentials otherwise come from the AWS chain: environment, profile, instance role); {@code hadoop.<key>} is
     *     passed to Hadoop as {@code <key>} (and to the native engine's Kernel options)
     */
    static LakeStore of(String root, String domain, Map<String, String> settings) {
        return of(root, domain, settings, EngineKind.of(settings, root));
    }

    /** The same, with the engine chosen by the caller. */
    static LakeStore of(String root, String domain, Map<String, String> settings, EngineKind engine) {
        if (domain.contains("..") || domain.startsWith("/") || domain.startsWith("\\")) {
            throw new IllegalArgumentException("domain escapes the Delta root: " + domain);
        }
        return engine == EngineKind.NATIVE ? NativeLake.open(root, domain, settings) : HadoopLake.open(root, domain, settings);
    }

    /** Whether {@code root} is a URI of object storage (a scheme other than {@code file:}, not a drive letter). */
    static boolean isRemote(String root) {
        return root.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*") && !root.regionMatches(true, 0, "file:", 0, 5);
    }
}
