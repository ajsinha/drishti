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
package com.ash.drishti.lakehouse;

import com.ash.drishti.deltalake.NativeEngine;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.io.Relocation;
import com.ash.drishti.lakehouse.delta.DeltaCatalog;
import com.ash.drishti.lakehouse.sharing.SharingCatalog;
import com.ash.drishti.lakehouse.sharing.SharingClient;
import com.ash.drishti.lakehouse.sharing.SharingProfile;
import com.ash.drishti.lakehouse.unity.UnityDeltaCatalog;
import com.ash.drishti.lakehouse.unity.UnityTables;
import com.ash.drishti.lakeio.LocalPaths;
import com.ash.drishti.lakeio.S3Settings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * The catalogs of tables Drishti reads in place from a lakehouse, each table seen as an Iceberg table so that one reader (the
 * external-table reader of the {@code iceberg-external}, {@code delta-external} and {@code delta-sharing} connectors) builds the document
 * view of any of them:
 *
 * <ul>
 * <li>{@link #delta}: Delta tables by folder under {@code root} (local disk, {@code s3://}), or by name in Unity Catalog
 *     ({@code unity.host}; storage location and vended credentials from Unity);</li>
 * <li>{@link #sharing}: tables shared through the Delta Sharing protocol (a profile file; no storage keys).</li>
 * </ul>
 */
public final class LakehouseCatalogs {

    private LakehouseCatalogs() {}

    /** How many recent versions of a Delta table are kept for <em>known at</em> ({@code history-versions}, 30). */
    static int history(Map<String, String> settings) {
        return Integer.parseInt(settings.getOrDefault("history-versions", "30").trim());
    }

    /**
     * Delta tables of {@code namespace}: Unity Catalog's schema {@code catalog.schema} when {@code unity.host} is set, else the folder
     * {@code <root>/<levels…>}.
     */
    public static Catalog delta(Map<String, String> settings, List<String> namespace) {
        if (UnityTables.configured(settings)) {
            return new UnityDeltaCatalog(UnityTables.from(settings), namespace, settings, history(settings));
        }
        String root = settings.getOrDefault("root", "").trim();
        if (root.isEmpty()) {
            throw new IllegalArgumentException("delta-external needs settings.root (the folder or s3:// prefix the tables are under) or "
                    + "settings.unity.host (tables named in Unity Catalog)");
        }
        for (String level : namespace) {
            if (level.contains("..") || level.contains("/") || level.contains("\\")) {
                throw new IllegalArgumentException("a namespace level is a folder name, not a path: " + level);
            }
        }
        NativeEngine engine = NativeEngine.create(settings);
        String base;
        try {
            base = LocalPaths.isLocal(root) ? engine.storage(root).qualify(root) : root.replaceAll("/+$", "");
        } catch (IOException e) {
            engine.close();
            throw new UncheckedIOException(e);
        }
        String path = namespace.isEmpty() ? base : base.replaceAll("/+$", "") + "/" + String.join("/", namespace);
        FileAccess io = new FileAccess(S3Settings.from(settings), Relocation.none(), settings.get("lz4-decoder"));
        return new DeltaCatalog(path, engine, io, history(settings));
    }

    /** The tables of one schema of a Delta Sharing share ({@code namespace}: {@code share.schema}, or {@code schema} with {@code share}). */
    public static Catalog sharing(Map<String, String> settings, List<String> namespace) {
        try {
            SharingClient client = new SharingClient(SharingProfile.from(settings), settings);
            return new SharingCatalog(client, namespace, settings, new FileAccess(S3Settings.from(Map.of()), Relocation.none(),
                    settings.get("lz4-decoder")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
