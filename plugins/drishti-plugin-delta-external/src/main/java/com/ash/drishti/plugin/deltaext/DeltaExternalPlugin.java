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
package com.ash.drishti.plugin.deltaext;

import com.ash.drishti.api.PluginNotConfigured;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.lakehouse.LakehouseCatalogs;
import com.ash.drishti.plugin.icebergext.IcebergExternalPlugin;
import java.util.List;
import java.util.Map;

/**
 * Plain Delta tables, as Spark, Databricks, delta-rs or Trino write them (any columns, no {@code id}/{@code doc} shape), served as
 * Drishti kinds (LAKEHOUSE_CONNECTORS.md): the connector maps the columns ({@code tables.<kind>.id}, {@code date}, {@code columns.*},
 * {@code links.*}) and the document view is built on read, so no shaped copy is needed. Tables are found by folder under {@code root}
 * (local disk or {@code s3://}) or by name in Unity Catalog ({@code unity.host}, {@code tables.<kind>.table: catalog.schema.table}), with
 * Unity's vended credentials. The reader is the {@code iceberg-external} connector's: drishti-lakehouse shows each Delta table as Iceberg.
 * Read-only.
 */
public final class DeltaExternalPlugin extends IcebergExternalPlugin {

    @Override
    protected String defaultName() {
        return "delta-external";
    }

    @Override
    protected void checkSettings(SourceContext ctx) {
        if (ctx.setting("root", "").isBlank() && ctx.setting("unity.host", "").isBlank()) {
            throw new PluginNotConfigured("delta-external needs settings.root (the folder or s3:// prefix the tables are under) or "
                    + "settings.unity.host (tables named in Unity Catalog)");
        }
        for (String k : ctx.settings().keySet()) {
            if (k.startsWith("metadata-location.") || k.equals("uri") || k.equals("catalog")) {
                throw new IllegalArgumentException("delta-external does not take " + k + ": that is the iceberg-external connector's (Iceberg "
                        + "catalogs); Delta tables are found under root, or by name in Unity Catalog (unity.*)");
            }
        }
    }

    @Override
    protected Catalog openCatalog(Map<String, String> s, List<String> ns) {
        return LakehouseCatalogs.delta(s, ns);
    }
}
