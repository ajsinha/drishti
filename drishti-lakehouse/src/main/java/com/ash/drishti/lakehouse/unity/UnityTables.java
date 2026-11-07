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
package com.ash.drishti.lakehouse.unity;

import com.ash.drishti.lakeio.LocalPaths;
import com.ash.drishti.lakeio.S3Settings;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Delta tables named in Unity Catalog ({@code catalog.schema.table}) rather than by path: the connector's {@code unity.*} settings say
 * where Unity Catalog is and how to sign in; {@link #resolve} asks it for the table's storage location and temporary credentials
 * (credential vending), and the table is then read with Delta Kernel at that location, as any Delta table there.
 *
 * <table>
 * <caption>Settings</caption>
 * <tr><td>{@code unity.host}</td><td>the workspace or server ({@code https://adb-….azuredatabricks.net}, {@code https://<ws>.cloud.databricks.com},
 * {@code http://localhost:8080})</td></tr>
 * <tr><td>{@code unity.token}</td><td>a bearer token, as {@code ${ENV}} or {@code file:}</td></tr>
 * <tr><td>{@code unity.client-id}, {@code unity.client-secret}</td><td>OAuth machine-to-machine (a service principal), the secret as
 * {@code ${ENV}} or {@code file:}</td></tr>
 * <tr><td>{@code unity.oauth-token-uri}</td><td>where OAuth tokens come from (default {@code <host>/oidc/v1/token})</td></tr>
 * <tr><td>{@code unity.catalog}, {@code unity.schema}</td><td>the defaults for a name given as {@code schema.table} or {@code table}</td></tr>
 * <tr><td>{@code unity.vend}</td><td>{@code true} (default): read with the table's vended credentials; {@code false}: with the connector's
 * own storage settings ({@code s3.*}, the AWS chain)</td></tr>
 * <tr><td>{@code unity.renew-before-seconds}</td><td>how long before expiry vended credentials are renewed (300)</td></tr>
 * </table>
 *
 * <p>Vended credentials are read for tables in S3 (and local tables of the open-source server, which vends none); tables in Azure (ADLS)
 * or Google Cloud Storage are refused in words for now (UNDER_CONSTRUCTION.md). Thread-safe.
 */
public final class UnityTables {

    /** A table found: its description, where its files are, and how to reach them. */
    public record Resolved(UnityClient.TableInfo info, String location, S3Settings s3, UnityCredentials credentials) {}

    private final UnityClient client;
    private final String catalog;
    private final String schema;
    private final boolean vend;
    private final long renewBeforeMs;
    private final Map<String, String> settings;

    UnityTables(UnityClient client, Map<String, String> settings) {
        this.client = client;
        this.settings = Map.copyOf(settings);
        this.catalog = settings.getOrDefault("unity.catalog", "").trim();
        this.schema = settings.getOrDefault("unity.schema", "").trim();
        this.vend = Boolean.parseBoolean(settings.getOrDefault("unity.vend", "true").trim());
        this.renewBeforeMs = Long.parseLong(settings.getOrDefault("unity.renew-before-seconds", "300").trim()) * 1000;
    }

    /** Whether the settings name a Unity Catalog ({@code unity.host}). */
    public static boolean configured(Map<String, String> settings) {
        return !settings.getOrDefault("unity.host", "").isBlank();
    }

    /** From the connector settings (secrets already resolved from their references). */
    public static UnityTables from(Map<String, String> settings) {
        String host = settings.getOrDefault("unity.host", "").trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("unity.host is not set: the Unity Catalog workspace or server address");
        }
        return new UnityTables(new UnityClient(host, settings.get("unity.token"), settings.get("unity.client-id"), settings.get("unity.client-secret"),
                settings.get("unity.oauth-token-uri"), settings), settings);
    }

    public UnityClient client() {
        return client;
    }

    /** {@code name} as three parts, the missing leading ones from {@code unity.catalog} and {@code unity.schema}. */
    public String fullName(String name) {
        String[] p = name.trim().split("\\.");
        for (String s : p) {
            if (s.isBlank()) {
                throw new IllegalArgumentException("a Unity Catalog table is named catalog.schema.table, not '" + name + "'");
            }
        }
        return switch (p.length) {
            case 3 -> name.trim();
            case 2 -> need(catalog, "unity.catalog", name) + "." + name.trim();
            case 1 -> need(catalog, "unity.catalog", name) + "." + need(schema, "unity.schema", name) + "." + name.trim();
            default -> throw new IllegalArgumentException("a Unity Catalog table is named catalog.schema.table, not '" + name + "'");
        };
    }

    private static String need(String v, String key, String name) {
        if (v.isEmpty()) {
            throw new IllegalArgumentException("the table '" + name + "' needs " + key + " (or name it catalog.schema.table)");
        }
        return v;
    }

    /** The tables of {@code catalog.schema}. */
    public List<String> tables(String catalogName, String schemaName) throws IOException {
        return client.tables(catalogName, schemaName);
    }

    /**
     * Finds the table and, with {@code unity.vend} (the default), its temporary credentials.
     *
     * @throws UnityException in words: not found, no grant, vending off, a view, not Delta, a store not read
     */
    public Resolved resolve(String name) throws IOException {
        String full = fullName(name);
        UnityClient.TableInfo t = client.table(full);
        String type = t.type().toUpperCase(Locale.ROOT);
        if (type.contains("VIEW")) {
            throw new UnityException(UnityException.Reason.NOT_READABLE, full + " is a " + t.type() + ": a view has no files of its own to read; "
                    + "point the connector at the tables it reads, or have the view written as a table");
        }
        if (!t.format().isEmpty() && !t.format().equalsIgnoreCase("DELTA")) {
            throw new UnityException(UnityException.Reason.NOT_READABLE, full + " is a " + t.format() + " table, not Delta"
                    + (t.format().equalsIgnoreCase("ICEBERG") ? ": read it with the iceberg-external connector through Unity Catalog's Iceberg REST "
                            + "endpoint (catalog: rest)" : ""));
        }
        if (t.storageLocation().isBlank()) {
            throw new UnityException(UnityException.Reason.NOT_READABLE, "Unity Catalog gives no storage location for " + full);
        }
        String location = t.storageLocation().replaceAll("/+$", "");
        String scheme = LocalPaths.scheme(location).toLowerCase(Locale.ROOT);
        if (!scheme.equals("file") && !scheme.startsWith("s3")) {
            throw new UnityException(UnityException.Reason.NOT_READABLE, full + " is stored at " + scheme + "://… : reading Unity tables in "
                    + "Azure (abfss://) or Google Cloud Storage (gs://) with vended credentials is under construction (2.9.11.i); today read such a "
                    + "table by its storage path with the delta connector's hadoop engine, or as Iceberg through Unity's REST endpoint");
        }
        S3Settings base = S3Settings.from(settings);
        if (!vend || scheme.equals("file")) {
            return new Resolved(t, location, base, null);
        }
        UnityClient.Vended v = client.credentials(t);
        if (v.kind().equals("none")) {
            return new Resolved(t, location, base, null);
        }
        if (!v.kind().equals("aws")) {
            throw new UnityException(UnityException.Reason.NOT_READABLE, "Unity Catalog vended " + v.kind() + " credentials for " + full
                    + ", stored in S3: unexpected; set unity.vend: false to read with the connector's own s3.* settings");
        }
        UnityCredentials c = new UnityCredentials(client, t, v, renewBeforeMs, System::currentTimeMillis);
        return new Resolved(t, location, base.withVended(c), c);
    }
}
