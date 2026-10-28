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
package com.ash.drishti.server.official;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.plugin.delta.DeltaSourcePlugin;
import com.ash.drishti.plugin.iceberg.IcebergSourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reads a published official table back, Delta or Iceberg, through the connector of its format: id to the document's fields. */
final class OfficialTables {

    static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    private OfficialTables() {
    }

    /** Official-like rows: a node each, with a long, doubles and text. */
    static List<Map<String, Object>> rows(int version, double scale) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (String node : List.of("MGMT~BANK", "MGMT~DESK-X", "LL~LL-A")) {
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("HIERARCHY", node.substring(0, node.indexOf('~')));
            r.put("NODE_ID", node);
            r.put("RESULT_VERSION", (long) version);
            r.put("VAR", 540.25 * scale);
            r.put("ES", 600.5 * scale);
            r.put("TRAFFIC_LIGHT", "green");
            out.add(r);
        }
        return out;
    }

    static Map<String, String> columns() {
        Map<String, String> c = new java.util.LinkedHashMap<>();
        c.put("HIERARCHY", "string");
        c.put("NODE_ID", "string");
        c.put("RESULT_VERSION", "long");
        c.put("VAR", "double");
        c.put("ES", "double");
        c.put("TRAFFIC_LIGHT", "string");
        return c;
    }

    /** Every document of the table on {@link #DAY}: id to its fields. */
    static Map<String, Map<String, Object>> read(String format, String root, String table, Map<String, String> storage) throws Exception {
        Map<String, String> s = new HashMap<>(storage);
        s.put("root", root);
        s.put("domain", OfficialSink.FOLDER);
        s.put("kinds", table);
        s.put("source-name", "official-" + format);
        s.put("engine", "native");
        s.put("refresh-seconds", "3600");
        s.put("id-map-disk.enabled", "false");
        s.put("doc-disk-cache.enabled", "false");
        SourcePlugin p = "iceberg".equals(format) ? new IcebergSourcePlugin() : new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(s));
        try {
            Map<String, Map<String, Object>> out = new TreeMap<>();
            p.documents(table, AsOf.of(DAY), d -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) d.data().unwrap();
                out.put(d.ref().id(), m);
            });
            return out;
        } finally {
            p.close();
        }
    }
}
