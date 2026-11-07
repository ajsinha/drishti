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
package com.ash.drishti.plugin.deltasharing;

import com.ash.drishti.api.PluginNotConfigured;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.lakehouse.LakehouseCatalogs;
import com.ash.drishti.plugin.icebergext.IcebergExternalPlugin;
import java.util.List;
import java.util.Map;

/**
 * Tables another organisation shares through the open Delta Sharing protocol, served as Drishti kinds (DELTA_SHARING_CONNECTOR.md): a
 * profile file names the sharing server and the recipient's bearer token (an environment reference); the connector lists the shares,
 * schemas and tables, asks for a table's files (pre-signed URLs, with predicate hints) and reads their Parquet. No storage keys. The
 * columns are mapped as for any plain table ({@code tables.<kind>.id}, {@code date}, {@code columns.*}); the reader is the
 * {@code iceberg-external} connector's. Change data feed and streaming are out of scope. Read-only.
 */
public final class DeltaSharingPlugin extends IcebergExternalPlugin {

    @Override
    protected String defaultName() {
        return "delta-sharing";
    }

    @Override
    protected void checkSettings(SourceContext ctx) {
        if (ctx.setting("profile", "").isBlank() && (ctx.setting("endpoint", "").isBlank() || ctx.setting("bearer-token", "").isBlank())) {
            throw new PluginNotConfigured("delta-sharing needs settings.profile (the provider's profile file, its bearerToken an ${ENV} "
                    + "reference), or settings.endpoint with settings.bearer-token");
        }
        for (String k : ctx.settings().keySet()) {
            if (k.startsWith("metadata-location.") || k.equals("uri") || k.equals("catalog") || k.equals("root") || k.startsWith("s3.")) {
                throw new IllegalArgumentException("delta-sharing does not take " + k + ": a share is reached through its sharing server alone, "
                        + "with no storage keys");
            }
        }
    }

    @Override
    protected Catalog openCatalog(Map<String, String> s, List<String> ns) {
        return LakehouseCatalogs.sharing(s, ns);
    }
}
