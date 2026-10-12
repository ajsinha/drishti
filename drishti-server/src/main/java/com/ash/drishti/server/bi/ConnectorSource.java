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
package com.ash.drishti.server.bi;

import com.ash.drishti.server.connectors.ConnectorManager;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What lies behind a connector, for lineage that goes past it (docs/guides/BI_GOVERNANCE.md, "Lineage"): the connector's plugin and the settings that say
 * <em>where</em> its data is (the table, topic, queue, path, bucket, prefix, collection, keyspace, database and host as the connector file declares them).
 * Credentials never appear: a secret setting is dropped. An administrator reads the declared values; everybody else sees that the connector reads from a
 * table, a topic or a path, with the values replaced by dots, so lineage shows the shape of the source without telling a reader where the system is.
 */
public final class ConnectorSource {

    /** The settings that name the place, in the order they are shown. */
    static final List<String> WHERE = List.of("table", "schema", "topic", "topics", "queue", "exchange", "subject", "path", "root", "directory", "file", "bucket", "prefix", "container",
            "collection", "keyspace", "namespace", "set", "database", "dbname", "index", "url", "uri", "host", "hosts", "bootstrap-servers", "brokers");
    private static final Pattern USERINFO = Pattern.compile("(?<=//)[^/@\\s]+@");
    private static final Set<String> SECRET = Set.of("password", "secret", "token", "key", "credential", "api-key");

    private final ConnectorManager connectors;
    private final Entitlements entitlements;

    public ConnectorSource(ConnectorManager connectors, Entitlements entitlements) {
        this.connectors = connectors;
        this.entitlements = entitlements;
    }

    /** The source behind {@code connector}, or null when it names no connector this server has. */
    public Map<String, Object> of(Principal p, String connector) {
        if (connector == null || connector.isBlank() || connectors == null) {
            return null;
        }
        Map<String, Object> detail;
        try {
            detail = connectors.detail(connector);
        } catch (RuntimeException e) {
            return null;
        }
        boolean admin = entitlements.isAdmin(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connector", connector);
        out.put("plugin", String.valueOf(detail.get("plugin")));
        out.put("masked", !admin);
        Map<String, Object> declared = new LinkedHashMap<>();
        Object s = detail.get("settings");
        if (s instanceof Map<?, ?> settings) {
            for (String key : WHERE) {
                Object v = settings.get(key);
                if (v == null || String.valueOf(v).isBlank() || secret(key)) {
                    continue;
                }
                declared.put(key, admin ? clean(String.valueOf(v)) : "•••");
            }
        }
        out.put("declared", declared);
        return out;
    }

    private static boolean secret(String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT);
        return SECRET.stream().anyMatch(k::contains);
    }

    /** Drops {@code user:password@} from a URL, so a connection string never carries a credential into lineage. */
    static String clean(String v) {
        return USERINFO.matcher(v).replaceFirst("");
    }
}
