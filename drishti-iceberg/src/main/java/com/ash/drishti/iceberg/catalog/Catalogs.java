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
package com.ash.drishti.iceberg.catalog;

import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.lakeio.LocalPaths;
import com.ash.drishti.lakeio.LocalStorage;
import com.ash.drishti.lakeio.S3Settings;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opens the catalog a connector's settings describe, the same settings the library engine reads
 * (ICEBERG_CONNECTOR.md section 15):
 *
 * <ul>
 *   <li>{@code catalog: hadoop} (the default): path-based tables under {@code <root>/<domain>}; {@code root} a local
 *       folder (a Windows path too) or {@code s3://}, {@code s3a://};</li>
 *   <li>{@code metadata-location.<table>}: a table named by its metadata file (read-only), alone or beside the others;</li>
 *   <li>{@code catalog: rest}: a REST catalog ({@code uri}, {@code warehouse}, {@code credential} or {@code token},
 *       {@code scope}, {@code oauth2-server-uri}, {@code prefix}, {@code catalog.<key>}); the domain is the namespace
 *       ({@code namespace} overrides it; dots separate levels).</li>
 * </ul>
 *
 * <p>Object storage: {@code s3.endpoint}, {@code s3.access-key}, {@code s3.secret-key}, {@code s3.region},
 * {@code s3.path-style}, {@code s3.read-block-kb}, TLS ({@code tls.*}) and proxy ({@code proxy.*}) as for Delta.
 * {@code relocate: <from> -> <to>; …} reads a table copied from one place to another (its metadata still names the
 * old one); each side is a plain folder ({@code /data/ice1}, {@code C:\lake}) or a URI ({@code file:/data/ice1}, {@code s3://bucket/wh}).
 */
public final class Catalogs {

    private Catalogs() {}

    public static Catalog open(Map<String, String> settings) {
        String domain = settings.getOrDefault("domain", "").trim();
        if (domain.contains("..") || domain.startsWith("/")) {
            throw new IllegalArgumentException("domain escapes the Iceberg root: " + domain);
        }
        FileAccess io = new FileAccess(S3Settings.from(settings), relocate(settings.getOrDefault("relocate", "")), settings.get("lz4-decoder"));
        Map<String, String> files = new LinkedHashMap<>();
        settings.forEach((k, v) -> {
            if (k.startsWith("metadata-location.") && !v.isBlank()) {
                files.put(k.substring("metadata-location.".length()), v.trim());
            }
        });
        String catalog = settings.getOrDefault("catalog", "hadoop").trim();
        if (catalog.equalsIgnoreCase("rest")) {
            return RestCatalog.open(settings, domain, io);
        }
        if (!catalog.isBlank() && !catalog.equalsIgnoreCase("hadoop") && !catalog.equalsIgnoreCase("path")) {
            io.close();
            throw new IllegalArgumentException("catalog must be hadoop or rest, not " + catalog);
        }
        if (!files.isEmpty() && settings.getOrDefault("root", "").isBlank()) {
            return new StaticCatalog(files, io);
        }
        String root = settings.getOrDefault("root", "").isBlank() ? com.ash.drishti.api.DataDir.under("iceberg") : settings.get("root").trim();
        if (!FileAccess.supports(root)) {
            io.close();
            throw new UnsupportedOperationException("drishti-iceberg reads local disk and S3 (s3://, s3a://), not " + root
                    + "; set the connector's engine: library for this lake");
        }
        String base = LocalPaths.isLocal(root) ? new LocalStorage().qualify(root) : root.replaceAll("/+$", "");
        String d = domain.replace('\\', '/').replaceAll("^/+|/+$", "");
        return new PathCatalog(d.isEmpty() ? base : base + "/" + d, io);
    }

    /**
     * {@code from -> to; from2 -> to2}, each side a plain folder or a URI ({@link com.ash.drishti.iceberg.io.Relocation}); a pair that cannot be
     * read fails the start, naming it (QA3B-04: it was ignored without a word).
     */
    static com.ash.drishti.iceberg.io.Relocation relocate(String spec) {
        return com.ash.drishti.iceberg.io.Relocation.parse(spec);
    }
}
