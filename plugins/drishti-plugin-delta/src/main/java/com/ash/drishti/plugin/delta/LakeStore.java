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
package com.ash.drishti.plugin.delta;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;

/**
 * Where a Delta Lake lives, so the connector reads a lake on local disk and one in object storage the same way. A
 * {@code root} with a scheme ({@code s3a://bucket/lake}, {@code abfs://…}, {@code gs://…}) is opened through Hadoop's
 * file systems; anything else is a local directory. The connector only asks for a table's URI and the list of tables.
 */
public interface LakeStore {

    /** The Delta table URI for {@code kind}. */
    String table(String kind);

    /** The kinds that have a Delta table ({@code <kind>/_delta_log}) under the domain, sorted. */
    List<String> tables() throws IOException;

    /** Whether the domain's folder exists (for health). */
    boolean reachable();

    /** For messages: where this is. */
    String describe();

    /** The Hadoop configuration Delta Kernel reads through. */
    Configuration hadoop();

    /**
     * @param root {@code ./data/delta}, {@code /lakes/risk}, or a URI {@code s3a://bucket/lake}
     * @param domain the data domain folder under the root (may be blank)
     * @param settings the connector settings: {@code hadoop.<key>} is passed to Hadoop as {@code <key>}; for S3,
     *     {@code s3.endpoint}, {@code s3.access-key}, {@code s3.secret-key}, {@code s3.region}, {@code s3.path-style}
     *     are shorthands (credentials otherwise come from the AWS chain: environment, profile, instance role)
     */
    static LakeStore of(String root, String domain, Map<String, String> settings) {
        if (domain.contains("..") || domain.startsWith("/")) {
            throw new IllegalArgumentException("domain escapes the Delta root: " + domain);
        }
        Configuration conf = new Configuration();
        settings.forEach((k, v) -> {
            if (k.startsWith("hadoop.")) {
                conf.set(k.substring(7), v);
            }
        });
        s3(conf, settings);
        if (root.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*") && !root.startsWith("file:")) {
            String base = root.replaceAll("/+$", "") + (domain.isBlank() ? "" : "/" + domain);
            return new HadoopLakeStore(base, conf);
        }
        Path r = Path.of(root.replaceFirst("^file:(//)?", "")).toAbsolutePath().normalize();
        return new LocalLakeStore(domain.isBlank() ? r : r.resolve(domain).normalize(), conf);
    }

    private static void s3(Configuration conf, Map<String, String> s) {
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
    }

    /** A lake in a local directory. */
    record LocalLakeStore(Path base, Configuration hadoop) implements LakeStore {
        @Override
        public String table(String kind) {
            return base.resolve(kind).toUri().toString();
        }

        @Override
        public List<String> tables() throws IOException {
            if (!Files.isDirectory(base)) {
                return List.of();
            }
            try (Stream<Path> s = Files.list(base)) {
                return s.filter(p -> Files.isDirectory(p.resolve("_delta_log"))).map(p -> p.getFileName().toString()).sorted().toList();
            }
        }

        @Override
        public boolean reachable() {
            return Files.isDirectory(base);
        }

        @Override
        public String describe() {
            return base.toString();
        }
    }

    /** A lake behind a Hadoop file system: S3 ({@code s3a://}), Azure ({@code abfs://}), Google Cloud Storage, HDFS. */
    record HadoopLakeStore(String base, Configuration hadoop) implements LakeStore {
        @Override
        public String table(String kind) {
            return base + "/" + kind;
        }

        @Override
        public List<String> tables() throws IOException {
            FileSystem fs = FileSystem.get(URI.create(base + "/"), hadoop);
            org.apache.hadoop.fs.Path dir = new org.apache.hadoop.fs.Path(base);
            if (!fs.exists(dir)) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (FileStatus st : fs.listStatus(dir)) {
                if (st.isDirectory() && fs.exists(new org.apache.hadoop.fs.Path(st.getPath(), "_delta_log"))) {
                    out.add(st.getPath().getName());
                }
            }
            out.sort(null);
            return out;
        }

        @Override
        public boolean reachable() {
            try {
                return FileSystem.get(URI.create(base + "/"), hadoop).exists(new org.apache.hadoop.fs.Path(base));
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }

        @Override
        public String describe() {
            return base;
        }
    }
}
