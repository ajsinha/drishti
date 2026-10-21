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
package com.ash.drishti.plugin.iceberg;

import com.ash.drishti.api.proxy.ProxySettings;
import com.ash.drishti.api.tls.TlsMaterial;
import com.ash.drishti.iceberg.catalog.Catalog;
import com.ash.drishti.iceberg.catalog.Catalogs;
import com.ash.drishti.iceberg.catalog.RestCatalog;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * A data domain's tables, one per kind, through the engine the settings choose ({@link IcebergEngine}): what the
 * connector reads through, whichever engine it is.
 */
interface LakeTables extends Closeable {

    /** The kind's table (it may not exist yet: its reads then find no snapshot). */
    KindTable table(String kind, ExecutorService pool);

    /** The kinds that have a table, sorted. */
    List<String> kinds() throws IOException;

    /** Whether the domain's folder or namespace can be reached (for health). */
    boolean reachable();

    /** For messages: where this is. */
    String describe();

    /** {@code native} or {@code library}. */
    IcebergEngine engine();

    TlsMaterial tls();

    ProxySettings proxy();

    @Override
    void close() throws IOException;

    static LakeTables open(Map<String, String> settings) {
        IcebergEngine e = IcebergEngine.of(settings);
        LakeTables lake = e == IcebergEngine.NATIVE ? new Native(Catalogs.open(settings), settings) : new Library(IcebergLake.of(settings));
        Map<String, String> names = Named.of(settings);
        return names.isEmpty() ? lake : new Named(lake, names);
    }

    /**
     * Tables named in the connector's settings rather than after their kinds, as the Delta connector's: {@code table: {trade: trade_calypso}}
     * (flattened {@code table.trade}) reads kind {@code trade} from the table {@code trade_calypso} of the domain, so one source system's
     * trades can be stored in Iceberg beside the others' in Delta ({@code build_demo_lakes.py --store calypso=iceberg}). Only the named kinds
     * are this lake's tables; a kind without a name keeps its own table.
     */
    record Named(LakeTables lake, Map<String, String> names) implements LakeTables {

        /** The {@code table.<kind>} settings: kind to table name. */
        static Map<String, String> of(Map<String, String> settings) {
            Map<String, String> out = new java.util.LinkedHashMap<>();
            settings.forEach((k, v) -> {
                if (k.startsWith("table.") && v != null && !v.isBlank()) {
                    String table = v.trim();
                    if (table.contains("/") || table.contains("\\") || table.contains("..")) {
                        throw new IllegalArgumentException(k + ": a table is a name under the domain, not a path: " + table);
                    }
                    out.put(k.substring("table.".length()), table);
                }
            });
            return out;
        }

        /** The table a kind is read from. */
        String tableOf(String kind) {
            return names.getOrDefault(kind, kind);
        }

        @Override
        public KindTable table(String kind, ExecutorService pool) {
            return lake.table(tableOf(kind), pool);
        }

        @Override
        public List<String> kinds() throws IOException {
            List<String> tables = lake.kinds();
            return names.entrySet().stream().filter(e -> tables.contains(e.getValue())).map(Map.Entry::getKey).sorted().toList();
        }

        @Override
        public boolean reachable() {
            return lake.reachable();
        }

        @Override
        public String describe() {
            return lake.describe() + " " + names;
        }

        @Override
        public IcebergEngine engine() {
            return lake.engine();
        }

        @Override
        public TlsMaterial tls() {
            return lake.tls();
        }

        @Override
        public ProxySettings proxy() {
            return lake.proxy();
        }

        @Override
        public void close() throws IOException {
            lake.close();
        }
    }

    /** Apache Iceberg Java. */
    record Library(IcebergLake lake) implements LakeTables {
        @Override
        public KindTable table(String kind, ExecutorService pool) {
            return new IcebergTable(kind, lake, pool);
        }

        @Override
        public List<String> kinds() throws IOException {
            return lake.kinds();
        }

        @Override
        public boolean reachable() {
            return lake.reachable();
        }

        @Override
        public String describe() {
            return lake.describe();
        }

        @Override
        public IcebergEngine engine() {
            return IcebergEngine.LIBRARY;
        }

        @Override
        public TlsMaterial tls() {
            return lake.tls();
        }

        @Override
        public ProxySettings proxy() {
            return lake.proxy();
        }

        @Override
        public void close() throws IOException {
            lake.close();
        }
    }

    /** drishti-iceberg. */
    final class Native implements LakeTables {
        private final Catalog catalog;
        private final TlsMaterial tls;
        private final ProxySettings proxy;

        Native(Catalog catalog, Map<String, String> settings) {
            this.catalog = catalog;
            String endpoint = settings.getOrDefault("s3.endpoint", "");
            TlsMaterial t = null;
            try {
                t = com.ash.drishti.lakeio.S3Settings.from(settings).tls();
            } catch (RuntimeException e) {
                // reported when the store is first used
            }
            this.tls = t;
            this.proxy = catalog instanceof RestCatalog r && r.proxy().active() ? r.proxy()
                    : ProxySettings.resolve(settings, endpoint.isBlank() ? "https://s3.amazonaws.com" : endpoint);
        }

        Catalog catalog() {
            return catalog;
        }

        @Override
        public KindTable table(String kind, ExecutorService pool) {
            return new NativeKindTable(kind, catalog, pool);
        }

        @Override
        public List<String> kinds() throws IOException {
            return catalog.tables();
        }

        @Override
        public boolean reachable() {
            return catalog.reachable();
        }

        @Override
        public String describe() {
            return catalog.describe();
        }

        @Override
        public IcebergEngine engine() {
            return IcebergEngine.NATIVE;
        }

        @Override
        public TlsMaterial tls() {
            return tls;
        }

        @Override
        public ProxySettings proxy() {
            return proxy.active() ? proxy : ProxySettings.none();
        }

        @Override
        public void close() {
            catalog.close();
        }
    }
}
