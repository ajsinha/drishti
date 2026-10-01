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
package com.ash.drishti.deltalake;

import io.delta.kernel.defaults.engine.DefaultExpressionHandler;
import io.delta.kernel.engine.Engine;
import io.delta.kernel.engine.ExpressionHandler;
import io.delta.kernel.engine.FileSystemClient;
import io.delta.kernel.engine.JsonHandler;
import io.delta.kernel.engine.ParquetHandler;
import java.util.HashMap;
import java.util.Map;

/**
 * A Delta Kernel {@link Engine} that never touches Hadoop's file systems: local disk through {@code java.nio}
 * (Windows drive letters, backslashes and UNC shares included, so no {@code winutils.exe}), S3 through the AWS SDK v2,
 * Parquet through parquet-java over the engine's own input files with its own page decompression, commit JSON and
 * expressions through Kernel's Hadoop-free handlers. It only reads: Drishti never writes a Delta table.
 *
 * <p>One engine serves every read of a connector, from any number of threads: its handlers are stateless, each read
 * opens its own streams, and the S3 client is shared. {@link #close()} releases the S3 client.
 *
 * <p>Settings (the Delta connector's): {@code s3.endpoint}, {@code s3.access-key}, {@code s3.secret-key},
 * {@code s3.region}, {@code s3.path-style}, {@code s3.read-block-kb} (1024: the smallest ranged GET); Kernel's tuning
 * keys as {@code kernel.<key>} or, as for the Hadoop engine, {@code hadoop.<key>} (e.g.
 * {@code kernel.delta.kernel.default.parquet.reader.batch-size}).
 */
public final class NativeEngine implements Engine, AutoCloseable {

    private final NativeFileIO io;
    private final FileSystemClient fileSystem;
    private final JsonHandler json;
    private final ParquetHandler parquet;
    private final ExpressionHandler expressions;

    private NativeEngine(NativeFileIO io) {
        this.io = io;
        this.fileSystem = new NativeFileSystemClient(io);
        this.json = new NativeJsonHandler(io);
        this.parquet = new NativeParquetHandler(io);
        this.expressions = new DefaultExpressionHandler();
    }

    /** An engine for the connector settings {@code settings} (see the class comment). */
    public static NativeEngine create(Map<String, String> settings) {
        Map<String, String> conf = new HashMap<>();
        settings.forEach((k, v) -> {
            if (k.startsWith("hadoop.")) {
                conf.put(k.substring(7), v);
            }
        });
        settings.forEach((k, v) -> {
            if (k.startsWith("kernel.")) {
                conf.put(k.substring(7), v);                     // kernel.* wins over hadoop.* for the same key
            }
        });
        return new NativeEngine(new NativeFileIO(S3Settings.from(settings), conf));
    }

    /** An engine with default settings: local disk, and S3 with the AWS credential and region chains. */
    public static NativeEngine create() {
        return create(Map.of());
    }

    /** Whether this engine reads {@code path}: local paths and {@code file:}, {@code s3:}, {@code s3a:}, {@code s3n:} URIs. */
    public static boolean supports(String path) {
        return NativeFileIO.supports(path);
    }

    /** Listing and reading for {@code path} (the connector lists a lake's tables with it). */
    public Storage storage(String path) {
        return io.storage(path);
    }

    @Override
    public ExpressionHandler getExpressionHandler() {
        return expressions;
    }

    @Override
    public JsonHandler getJsonHandler() {
        return json;
    }

    @Override
    public FileSystemClient getFileSystemClient() {
        return fileSystem;
    }

    @Override
    public ParquetHandler getParquetHandler() {
        return parquet;
    }

    @Override
    public void close() {
        io.close();
    }

    @Override
    public String toString() {
        return "NativeEngine";
    }
}
