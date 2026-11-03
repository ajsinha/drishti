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
 * A Delta or Iceberg connector's view of its lake (docs/architecture/LAKE_CONFIG.md section 4): a connector names {@code lake: <name>}
 * and its kinds; this turns the lake file's root, storage, catalog, table paths, promoted columns and vectors into the settings the
 * plugin reads ({@code root}, {@code domain}, {@code table.<kind>}, {@code layout.<kind>.columns}, {@code layout.<kind>.vectors},
 * {@code s3.<key>}), so the plugins keep one way of reading and the lake file is the only place those values are written. It runs at
 * the one point every connector's settings pass on their way to a plugin (the source registry), whichever file or template they came
 * from.
 *
 * <p>A connector that still carries the lake's keys itself ({@code root}, {@code domain}, {@code table.<kind>}, {@code layout.<kind>.*})
 * starts as before, with one warning (the first such connector named) naming {@code drishti.py lake migrate-config} (L4: the one configuration alias, read
 * for one release). A connector that names a lake and also carries one of those keys is refused, naming both.
 */
public final class ConnectorLake {

    /** The connector setting that names the lake. */
    public static final String LAKE = "lake";
    /** The connector setting that names its tables when a kind has several in the lake ({@code trading.trade_endur}). */
    public static final String TABLES = "lake-tables";
    /** The plugins whose settings a lake supplies. */
    public static final Set<String> PLUGINS = Set.of("delta", "iceberg");

    private static final java.util.concurrent.atomic.AtomicBoolean WARNED = new java.util.concurrent.atomic.AtomicBoolean();

    private ConnectorLake() {
    }

    /** Whether a setting key is the lake's to say (a connector that names a lake may not carry it). */
    public static boolean lakeKey(String k) {
        return k.equals("root") || k.equals("domain") || k.startsWith("table.") || k.startsWith("layout.") && !k.endsWith(".type-ahead")
                || k.equals("uri") || k.equals("warehouse") || k.startsWith("s3.") && !k.startsWith("s3.cache");
    }

    /** Whether the settings carry the lake's keys the old way (the connector has not been migrated). */
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
            if (legacy(settings) && WARNED.compareAndSet(false, true)) {
                warn.accept("connector " + connector + " (and any other like it) describes its lake itself (root, domain, table.<kind>, "
                        + "layout.<kind>.*): run drishti.py lake migrate-config to move that into config/lakes/ (read this way for one more release)");
            }
            return settings;
        }
        List<String> clash = settings.keySet().stream().filter(ConnectorLake::lakeKey).sorted().toList();
        if (!clash.isEmpty()) {
            throw new IllegalArgumentException("connector " + connector + " names lake " + name + " and also sets " + clash + ": those are the "
                    + "lake file's (config/lakes/" + name + ".yaml); remove them from the connector");
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
        // the plugins read <root>/<domain>/<folder>: the table's parent folder's last segment is the domain, the rest goes to the root
        String parent = parents.isEmpty() ? "" : parents.iterator().next();
        int cut = parent.lastIndexOf('/');
        out.put("root", cut < 0 ? lake.root() : trim(lake.root()) + "/" + parent.substring(0, cut));
        if (!parent.isEmpty()) {
            out.put("domain", parent.substring(cut + 1));
        }
        boolean s3 = lake.root().startsWith("s3://") || lake.root().startsWith("s3a://");
        lake.storage().forEach((k, v) -> out.put(s3 && !k.startsWith("s3.") ? "s3." + k : k, v));
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
        return out;
    }

    private static String trim(String root) {
        return root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
    }
}
