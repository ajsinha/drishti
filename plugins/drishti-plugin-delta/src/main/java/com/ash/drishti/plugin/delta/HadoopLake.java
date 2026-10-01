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

import io.delta.kernel.defaults.engine.DefaultEngine;
import io.delta.kernel.engine.Engine;
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
 * Lakes read through Kernel's default engine over Hadoop's file systems ({@code engine: hadoop}): local disk (which
 * on Windows needs Hadoop's {@code winutils.exe}), S3 ({@code s3a://}), Azure ({@code abfs://}), Google Cloud Storage,
 * HDFS. Kept apart from {@link LakeStore} so the native engine never loads a Hadoop class.
 */
final class HadoopLake {

    private HadoopLake() {}

    static LakeStore open(String root, String domain, Map<String, String> settings) {
        Configuration conf = new Configuration();
        settings.forEach((k, v) -> {
            if (k.startsWith("hadoop.")) {
                conf.set(k.substring(7), v);
            }
        });
        s3(conf, settings);
        Engine engine = DefaultEngine.create(conf);
        if (LakeStore.isRemote(root)) {
            String base = root.replaceAll("/+$", "") + (domain.isBlank() ? "" : "/" + domain);
            return new Remote(base, conf, engine);
        }
        Path r = Path.of(root.replaceFirst("^file:(//)?", "")).toAbsolutePath().normalize();
        return new Local(domain.isBlank() ? r : r.resolve(domain).normalize(), engine);
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

    /** A lake in a local directory, read through Hadoop's local file system. */
    record Local(Path base, Engine engine) implements LakeStore {
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

        @Override
        public String engineName() {
            return EngineKind.HADOOP.label();
        }
    }

    /** A lake behind a Hadoop file system: S3 ({@code s3a://}), Azure ({@code abfs://}), Google Cloud Storage, HDFS. */
    record Remote(String base, Configuration hadoop, Engine engine) implements LakeStore {
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

        @Override
        public String engineName() {
            return EngineKind.HADOOP.label();
        }
    }
}
