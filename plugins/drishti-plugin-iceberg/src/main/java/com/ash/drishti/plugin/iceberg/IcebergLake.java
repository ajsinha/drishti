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

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.rest.RESTCatalog;

/**
 * Where a data domain's Iceberg tables are, one table per kind, so the connector, the loader and the maintenance job
 * open them the same way whatever the catalog:
 *
 * <ul>
 *   <li>{@code catalog: hadoop} (the default): path-based tables at {@code <root>/<domain>/<kind>}, their metadata in
 *       the table's {@code metadata/} folder. {@code root} is a local folder or a Hadoop URI ({@code s3a://bucket/lake},
 *       {@code abfs://…}, {@code gs://…}, {@code hdfs://…}).</li>
 *   <li>{@code catalog: rest}: an Iceberg REST catalog ({@code uri}, {@code warehouse}, {@code credential} or
 *       {@code token}): Apache Polaris, Snowflake Open Catalog, AWS Glue's Iceberg REST endpoint, Unity Catalog, Nessie,
 *       Lakekeeper, Gravitino. The domain is the namespace ({@code namespace} overrides it; dots separate levels).</li>
 * </ul>
 *
 * <p>Object storage: {@code s3.endpoint}, {@code s3.access-key}, {@code s3.secret-key}, {@code s3.region},
 * {@code s3.path-style} configure both Hadoop's S3A (path-based tables) and Iceberg's S3FileIO (REST catalogs);
 * {@code hadoop.<key>} is passed to Hadoop and {@code catalog.<key>} to the catalog as {@code <key>}.
 */
public interface IcebergLake extends Closeable {

    /** The kind's table, or empty when it does not exist. */
    Optional<Table> load(String kind);

    /** The kinds that have a table in the domain, sorted. */
    List<String> kinds() throws IOException;

    /** Creates the kind's table (for writers). */
    Table create(String kind, Schema schema, PartitionSpec spec, SortOrder order, Map<String, String> properties);

    /** Whether the domain's folder or namespace can be reached (for health). */
    boolean reachable();

    /** For messages: where this is. */
    String describe();

    @Override
    default void close() throws IOException {
    }

    static IcebergLake of(Map<String, String> settings) {
        String domain = settings.getOrDefault("domain", "").trim();
        if (domain.contains("..") || domain.startsWith("/")) {
            throw new IllegalArgumentException("domain escapes the Iceberg root: " + domain);
        }
        Configuration conf = hadoop(settings);
        String catalog = settings.getOrDefault("catalog", "hadoop").trim();
        if (catalog.equalsIgnoreCase("rest")) {
            return RestLake.open(settings, domain, conf);
        }
        if (!catalog.isBlank() && !catalog.equalsIgnoreCase("hadoop")) {
            throw new IllegalArgumentException("catalog must be hadoop or rest, not " + catalog);
        }
        String root = settings.getOrDefault("root", "").isBlank() ? "./data/iceberg" : settings.get("root").trim();
        String base;
        if (root.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*") && !root.startsWith("file:")) {
            base = root.replaceAll("/+$", "");
        } else {
            base = Path.of(root.replaceFirst("^file:(//)?", "")).toAbsolutePath().normalize().toUri().toString().replaceAll("/+$", "");
        }
        return new PathLake(domain.isBlank() ? base : base + "/" + domain, conf);
    }

    /** The Hadoop configuration tables and files are read through. */
    static Configuration hadoop(Map<String, String> s) {
        Configuration conf = new Configuration();
        s.forEach((k, v) -> {
            if (k.startsWith("hadoop.")) {
                conf.set(k.substring(7), v);
            }
        });
        // s3:// paths (what REST catalogs and AWS write) through S3A as well, when read with Hadoop
        conf.setIfUnset("fs.s3.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem");
        String endpoint = s.getOrDefault("s3.endpoint", "");
        if (!endpoint.isBlank()) {
            conf.set("fs.s3a.endpoint", endpoint);
            conf.set("fs.s3a.path.style.access", s.getOrDefault("s3.path-style", "true"));
            conf.set("fs.s3a.connection.ssl.enabled", String.valueOf(endpoint.startsWith("https")));
        }
        if (!s.getOrDefault("s3.access-key", "").isBlank()) {
            conf.set("fs.s3a.access.key", s.get("s3.access-key"));
            conf.set("fs.s3a.secret.key", s.getOrDefault("s3.secret-key", ""));
            conf.set("fs.s3a.aws.credentials.provider", "org.apache.hadoop.fs.s3a.SimpleAWSCredentialsProvider");
        }
        if (!s.getOrDefault("s3.region", "").isBlank()) {
            conf.set("fs.s3a.endpoint.region", s.get("s3.region"));
        }
        return conf;
    }

    /** Path-based tables: {@code <base>/<kind>} with {@code metadata/version-hint.text}. */
    final class PathLake implements IcebergLake {
        private final String base;
        private final Configuration conf;
        private final HadoopTables tables;

        PathLake(String base, Configuration conf) {
            this.base = base;
            this.conf = conf;
            this.tables = new HadoopTables(conf);
        }

        private String location(String kind) {
            return base + "/" + kind;
        }

        @Override
        public Optional<Table> load(String kind) {
            try {
                return Optional.of(tables.load(location(kind)));
            } catch (NoSuchTableException e) {
                return Optional.empty();
            }
        }

        @Override
        public List<String> kinds() throws IOException {
            FileSystem fs = FileSystem.get(URI.create(base + "/"), conf);
            org.apache.hadoop.fs.Path dir = new org.apache.hadoop.fs.Path(base);
            if (!fs.exists(dir)) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (FileStatus st : fs.listStatus(dir)) {
                if (st.isDirectory() && fs.exists(new org.apache.hadoop.fs.Path(st.getPath(), "metadata/version-hint.text"))) {
                    out.add(st.getPath().getName());
                }
            }
            out.sort(null);
            return out;
        }

        @Override
        public Table create(String kind, Schema schema, PartitionSpec spec, SortOrder order, Map<String, String> properties) {
            return tables.create(schema, spec, order, properties, location(kind));
        }

        @Override
        public boolean reachable() {
            try {
                return FileSystem.get(URI.create(base + "/"), conf).exists(new org.apache.hadoop.fs.Path(base));
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }

        @Override
        public String describe() {
            return base;
        }
    }

    /** Tables in a namespace of an Iceberg REST catalog. */
    final class RestLake implements IcebergLake {
        private final RESTCatalog catalog;
        private final Namespace namespace;
        private final String uri;

        private RestLake(RESTCatalog catalog, Namespace namespace, String uri) {
            this.catalog = catalog;
            this.namespace = namespace;
            this.uri = uri;
        }

        static RestLake open(Map<String, String> s, String domain, Configuration conf) {
            String uri = s.getOrDefault("uri", "");
            if (uri.isBlank()) {
                throw new IllegalArgumentException("a REST catalog needs uri");
            }
            Map<String, String> props = new HashMap<>();
            props.put("uri", uri);
            for (String key : new String[] {"warehouse", "credential", "token", "scope", "oauth2-server-uri", "io-impl", "prefix"}) {
                if (!s.getOrDefault(key, "").isBlank()) {
                    props.put(key, s.get(key));
                }
            }
            // S3FileIO's own keys, from the same shorthands S3A uses (a catalog that vends credentials overrides them)
            if (!s.getOrDefault("s3.endpoint", "").isBlank()) {
                props.put("s3.endpoint", s.get("s3.endpoint"));
                props.put("s3.path-style-access", s.getOrDefault("s3.path-style", "true"));
            }
            if (!s.getOrDefault("s3.access-key", "").isBlank()) {
                props.put("s3.access-key-id", s.get("s3.access-key"));
                props.put("s3.secret-access-key", s.getOrDefault("s3.secret-key", ""));
            }
            if (!s.getOrDefault("s3.region", "").isBlank()) {
                props.put("client.region", s.get("s3.region"));
            }
            s.forEach((k, v) -> {
                if (k.startsWith("catalog.")) {
                    props.put(k.substring(8), v);
                }
            });
            RESTCatalog catalog = new RESTCatalog();
            catalog.setConf(conf);
            catalog.initialize(s.getOrDefault("source-name", "drishti"), props);
            String ns = s.getOrDefault("namespace", "").isBlank() ? domain : s.get("namespace");
            return new RestLake(catalog, ns.isBlank() ? Namespace.empty() : Namespace.of(ns.split("\\.")), uri);
        }

        @Override
        public Optional<Table> load(String kind) {
            try {
                return Optional.of(catalog.loadTable(TableIdentifier.of(namespace, kind)));
            } catch (NoSuchTableException e) {
                return Optional.empty();
            }
        }

        @Override
        public List<String> kinds() {
            try {
                return catalog.listTables(namespace).stream().map(TableIdentifier::name).sorted().toList();
            } catch (NoSuchNamespaceException e) {
                return List.of();
            }
        }

        @Override
        public Table create(String kind, Schema schema, PartitionSpec spec, SortOrder order, Map<String, String> properties) {
            if (!namespace.isEmpty() && !catalog.namespaceExists(namespace)) {
                try {
                    catalog.createNamespace(namespace);
                } catch (org.apache.iceberg.exceptions.AlreadyExistsException e) {
                    // another writer created it meanwhile
                }
            }
            return catalog.buildTable(TableIdentifier.of(namespace, kind), schema).withPartitionSpec(spec).withSortOrder(order)
                    .withProperties(properties).create();
        }

        @Override
        public boolean reachable() {
            try {
                return namespace.isEmpty() || catalog.namespaceExists(namespace);
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public String describe() {
            return uri + " namespace " + namespace;
        }

        @Override
        public void close() throws IOException {
            catalog.close();
        }
    }
}
