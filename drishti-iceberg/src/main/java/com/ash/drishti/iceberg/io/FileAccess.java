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
package com.ash.drishti.iceberg.io;

import com.ash.drishti.lakeio.LocalPaths;
import com.ash.drishti.lakeio.LocalStorage;
import com.ash.drishti.lakeio.NativeCodecs;
import com.ash.drishti.lakeio.S3Settings;
import com.ash.drishti.lakeio.S3Storage;
import com.ash.drishti.lakeio.Storage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The engine's files: local paths and {@code file:} URIs through {@code java.nio} (Windows drive letters, backslashes
 * and UNC shares included, no {@code winutils.exe}), {@code s3://}, {@code s3a://} and {@code s3n://} through the AWS
 * SDK; nothing else ({@code abfs://}, {@code gs://}, {@code hdfs://} are refused, naming the library engine).
 *
 * <p>Paths in a table's metadata and manifests are absolute. A table copied elsewhere (an S3 table synced to a laptop,
 * a test fixture) is read through {@code relocate}: each {@code from} prefix of a path is replaced by its {@code to}
 * before the file is opened. Thread-safe; the S3 client is made on first use and shared.
 */
public final class FileAccess implements AutoCloseable {

    private static final Set<String> S3_SCHEMES = Set.of("s3", "s3a", "s3n");

    private final LocalStorage local;
    private final S3Settings s3Settings;
    private final Map<String, String> relocate;
    private final NativeCodecs codecs;
    private final ReentrantLock s3Lock = new ReentrantLock();
    private volatile S3Storage s3;

    /**
     * @param s3Settings how to reach S3 when a path asks for it
     * @param relocate path prefixes to replace ({@code s3://prod/warehouse} to {@code file:/C:/copy}), in order
     * @param lz4Decoder {@code safe} or {@code fast} (null: safe)
     */
    public FileAccess(S3Settings s3Settings, Map<String, String> relocate, String lz4Decoder) {
        this(new LocalStorage(), s3Settings, relocate, lz4Decoder);
    }

    FileAccess(LocalStorage local, S3Settings s3Settings, Map<String, String> relocate, String lz4Decoder) {
        this.local = local;
        this.s3Settings = s3Settings;
        this.relocate = new LinkedHashMap<>(relocate);
        this.codecs = NativeCodecs.of(lz4Decoder, "drishti-iceberg", "rewrite the day with ZSTD or Snappy (IcebergMaintenance --relayout --force)");
    }

    /** Local disk only, with no relocation (tests, tools). */
    public static FileAccess local() {
        return new FileAccess(S3Settings.from(Map.of()), Map.of(), null);
    }

    /** The same local disk and relocation, with other S3 credentials (a REST catalog's vended credentials). */
    public FileAccess withS3(S3Settings settings) {
        return new FileAccess(local, settings, relocate, null);
    }

    public S3Settings s3Settings() {
        return s3Settings;
    }

    /** The page codecs reads and writes use. */
    public NativeCodecs codecs() {
        return codecs;
    }

    /** Whether this engine reads {@code path}: local paths and {@code file:}, {@code s3:}, {@code s3a:}, {@code s3n:} URIs. */
    public static boolean supports(String path) {
        String scheme = LocalPaths.scheme(path);
        return scheme.equals("file") || S3_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
    }

    /** {@code path} with the first matching {@code relocate} prefix replaced. */
    public String resolve(String path) {
        for (Map.Entry<String, String> e : relocate.entrySet()) {
            if (path.startsWith(e.getKey())) {
                return e.getValue() + path.substring(e.getKey().length());
            }
        }
        return path;
    }

    /** The storage serving {@code path} (after relocation: call with {@link #resolve(String)}'s result). */
    public Storage storage(String path) {
        String scheme = LocalPaths.scheme(path);
        if (scheme.equals("file")) {
            return local;
        }
        if (S3_SCHEMES.contains(scheme)) {
            return s3();
        }
        throw new UnsupportedOperationException("drishti-iceberg reads local disk and S3 (s3://, s3a://), not " + scheme
                + "://; set the connector's engine: library for this table");
    }

    /** The whole file at {@code path} (relocated). */
    public byte[] readAll(String path) throws IOException {
        String p = resolve(path);
        return storage(p).readAll(p);
    }

    private S3Storage s3() {
        S3Storage s = s3;
        if (s != null) {
            return s;
        }
        s3Lock.lock();
        try {
            if (s3 == null) {
                s3 = new S3Storage(s3Settings);
            }
            return s3;
        } finally {
            s3Lock.unlock();
        }
    }

    /** Releases the S3 client, if one was made. */
    @Override
    public void close() {
        s3Lock.lock();
        try {
            if (s3 != null) {
                s3.close();
                s3 = null;
            }
        } finally {
            s3Lock.unlock();
        }
    }
}
