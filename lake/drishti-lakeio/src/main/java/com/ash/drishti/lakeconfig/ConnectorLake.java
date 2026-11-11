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
package com.ash.drishti.lakeconfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A Delta or Iceberg connector's view of its lake (docs/architecture/DATA_ARCHITECTURE.md section 4): a connector names {@code lake: <name>}
 * and its kinds; this turns the lake file's root, storage, catalog, table paths, promoted columns and vectors into the settings the
 * plugin reads ({@code root}, {@code domain}, {@code table.<kind>}, {@code layout.<kind>.columns}, {@code layout.<kind>.vectors},
 * {@code s3.<key>}), so the plugins keep one way of reading and the lake file is the only place those values are written. It runs at
 * the one point every connector's settings pass on their way to a plugin (the source registry), whichever file or template they came
 * from.
 *
 * <p>A connector has two forms, both permanent (product owner, 2026-11-07): {@code lake: <name>}, its tables where the lake file says (the
 * lake's {@code root:} a local folder, {@code s3://bucket/prefix} or {@code mem://<store>}); or an explicit {@code root:} (a path or URI:
 * a folder, {@code s3://...}) with {@code domain}, {@code table.<kind>} and {@code layout.<kind>.*}, for tables no lake file describes (a
 * vendor's Delta table on S3). When a connector names a lake and also gives one of those keys, its own explicit value wins over the lake
 * file's ({@link #overridden}: the connector page says so).
 */
public final class ConnectorLake {

    /** The connector setting that names the lake. */
    public static final String LAKE = "lake";
    /** The connector setting that names its tables when a kind has several in the lake ({@code trading.trade_endur}). */
    public static final String TABLES = "lake-tables";
    /** The setting the plugin is handed with the lake's name, so what it says when it cannot reach its tables names the lake. */
    public static final String LAKE_NAME = "lake-name";
    /** The plugins whose settings a lake supplies. */
    public static final Set<String> PLUGINS = Set.of("delta", "iceberg");

    private ConnectorLake() {
    }

    /** Whether a setting key is the lake's to say (a connector that names a lake may not carry it). */
    public static boolean lakeKey(String k) {
        return k.equals("root") || k.equals("domain") || k.startsWith("table.") || k.startsWith("layout.") && !k.endsWith(".type-ahead")
                || k.equals("uri") || k.equals("warehouse") || k.startsWith("s3.") && !k.startsWith("s3.cache");
    }

    /** The keys a connector that names a lake gives itself, over the lake file's (empty: none); the explicit value wins. */
    public static List<String> overridden(Map<String, String> settings) {
        if (settings == null || settings.get(LAKE) == null || settings.get(LAKE).isBlank()) {
            return List.of();
        }
        return settings.keySet().stream().filter(ConnectorLake::lakeKey).sorted().toList();
    }

    /** Whether the settings describe the tables by an explicit path ({@code root:} and the rest) rather than by a lake's name. */
    public static boolean legacy(Map<String, String> settings) {
        return !settings.containsKey(LAKE) && settings.keySet().stream().anyMatch(k -> k.equals("root") || k.startsWith("table.")
                || k.startsWith("layout.") && !k.endsWith(".type-ahead"));
    }

    /**
     * The settings the plugin is given: unchanged for a connector of another plugin; for a Delta or Iceberg connector that names a lake,
     * its own reading settings plus what the lake file says of its kinds.
     *
     * @param connector the connector's name (for the messages)
     * @param plugin    its plugin
     * @param kinds     the kinds it serves (empty: every table the lake describes)
     * @param settings  its settings, environment references resolved
     * @param lakes     the lakes the server knows (read only for a connector that names one)
     * @param warn      where the one migration warning goes
     */
    public static Map<String, String> expand(String connector, String plugin, Collection<String> kinds, Map<String, String> settings,
            java.util.function.Supplier<Lakes> lakes, Consumer<String> warn) {
        if (!PLUGINS.contains(plugin)) {
            return settings;
        }
        String name = settings.get(LAKE);
        if (name == null || name.isBlank()) {
            return settings;                                   // the explicit form: root (a path or URI), domain, table.<kind>, layout
        }
        List<String> own = overridden(settings);
        if (!own.isEmpty()) {
            warn.accept("connector " + connector + " names lake " + name + " and gives " + own + " itself: its own values win over the lake "
                    + "file's (config/lakes/" + name + ".yaml)");
        }
        Lake lake = lakes.get().get(name.strip());
        if (!lake.format().equals(plugin) && lake.tables().values().stream().noneMatch(t -> t.format().equals(plugin))) {
            throw new IllegalArgumentException("connector " + connector + " (plugin " + plugin + ") names lake " + name + ", a " + lake.format()
                    + " lake: use the " + lake.format() + " plugin");
        }
        List<LakeTable> tables = new ArrayList<>();
        String named = settings.getOrDefault(TABLES, "");
        if (!named.isBlank()) {                               // lake-tables: the entries by name, where a kind has several tables
            for (String n : named.split(",")) {
                if (!n.isBlank()) {
                    tables.add(lake.entry(n.trim()).orElseThrow(() -> new IllegalArgumentException("connector " + connector + ": lake " + name
                            + " has no table " + n.trim() + " (" + lake.tables().keySet() + ")")));
                }
            }
        } else if (kinds == null || kinds.isEmpty()) {
            tables.addAll(lake.tables().values());
        } else {
            for (String k : kinds) {
                tables.add(lake.require(null, k));
            }
        }
        Set<String> parents = new LinkedHashSet<>();
        tables.forEach(t -> parents.add(t.parent()));
        if (parents.size() > 1) {
            throw new IllegalArgumentException("connector " + connector + ": its kinds sit in more than one folder of lake " + name + " ("
                    + parents + "); a connector reads one domain: split it into one connector per domain");
        }
        Map<String, String> out = new LinkedHashMap<>(settings);
        out.remove(LAKE);
        out.remove(TABLES);
        out.put(LAKE_NAME, lake.name());
        // the plugins read <root>/<domain>/<folder>: the table's parent folder's last segment is the domain, the rest goes to the root
        String parent = parents.isEmpty() ? "" : parents.iterator().next();
        int cut = parent.lastIndexOf('/');
        if (!lake.root().isEmpty()) {                          // a catalog lake (rest, glue, s3tables) has no root: the catalog says
            out.put("root", cut < 0 ? lake.root() : trim(lake.root()) + "/" + parent.substring(0, cut));
        }
        if (!parent.isEmpty()) {
            out.put("domain", parent.substring(cut + 1));
        }
        // storage keys are the object store's: s3.<key> for an S3 root or a catalog's warehouse; another scheme's keys as written
        boolean s3 = lake.root().isEmpty() || lake.root().startsWith("s3://") || lake.root().startsWith("s3a://");
        lake.storage().forEach((k, v) -> {
            if (v != null && !v.isBlank()) {                   // a reference that resolved to nothing (a publisher's unset s3.region) is no setting
                out.put(s3 && !k.startsWith("s3.") && !k.startsWith("tls.") && !k.startsWith("proxy.") ? "s3." + k : k, v);
            }
        });
        lake.catalog().forEach((k, v) -> {
            if (!k.equals("type")) {
                out.put(k, v);
            } else if (!v.equals("none")) {
                out.put("catalog", v);
            }
        });
        for (LakeTable t : tables) {
            if (!t.folder().equals(t.kind())) {
                out.put("table." + t.kind(), t.folder());
            }
            if (!t.promote().isEmpty()) {
                out.put("layout." + t.kind() + ".columns", String.join(",", t.promote()));
            }
            if (!t.vectors().isEmpty()) {
                out.put("layout." + t.kind() + ".vectors", String.join(",", t.vectors()));
            }
        }
        own.forEach(k -> out.put(k, settings.get(k)));         // the connector's explicit path, domain, tables or layout win
        return out;
    }

    private static String trim(String root) {
        return root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
    }
}
