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

import io.delta.kernel.defaults.engine.fileio.FileIO;
import io.delta.kernel.defaults.engine.fileio.InputFile;
import io.delta.kernel.defaults.engine.fileio.OutputFile;
import io.delta.kernel.internal.util.Utils;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Kernel's file access without Hadoop: local paths through {@link LocalStorage}, {@code s3://} and {@code s3a://}
 * through {@link S3Storage}. Read-only: Drishti never writes a Delta table (its writers are Python, on delta-rs), so
 * every write is refused with {@link UnsupportedOperationException}. Thread-safe; the S3 client is made on first use.
 */
public final class NativeFileIO implements FileIO {

    /** The message of every refused write. */
    static final String READ_ONLY = "the native Delta engine only reads: Drishti does not write Delta tables (its loaders use delta-rs)";

    private static final Set<String> S3_SCHEMES = Set.of("s3", "s3a", "s3n");

    private final LocalStorage local;
    private final S3Settings s3Settings;
    private final Map<String, String> conf;
    private final ReentrantLock s3Lock = new ReentrantLock();
    private volatile S3Storage s3;

    /**
     * @param s3Settings how to reach S3 when a path asks for it
     * @param conf Kernel's tuning keys ({@code delta.kernel.default.parquet.reader.batch-size}, …), as for Hadoop
     */
    public NativeFileIO(S3Settings s3Settings, Map<String, String> conf) {
        this(new LocalStorage(), s3Settings, conf);
    }

    NativeFileIO(LocalStorage local, S3Settings s3Settings, Map<String, String> conf) {
        this.local = local;
        this.s3Settings = s3Settings;
        this.conf = Map.copyOf(conf);
    }

    /** Whether the native engine can read paths of this scheme ({@code file}, {@code s3}, {@code s3a}, {@code s3n}). */
    public static boolean supports(String path) {
        String scheme = LocalPaths.scheme(path);
        return scheme.equals("file") || S3_SCHEMES.contains(scheme);
    }

    /** The storage that serves {@code path}. */
    public Storage storage(String path) {
        String scheme = LocalPaths.scheme(path);
        if (scheme.equals("file")) {
            return local;
        }
        if (S3_SCHEMES.contains(scheme)) {
            return s3();
        }
        throw new UnsupportedOperationException("the native Delta engine reads local disk and S3 (s3://, s3a://), not " + scheme
                + "://; set the connector's engine to hadoop for this lake");
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

    @Override
    public CloseableIterator<FileStatus> listFrom(String filePath) throws IOException {
        return Utils.toCloseableIterator(storage(filePath).listFrom(filePath).iterator());
    }

    @Override
    public FileStatus getFileStatus(String path) throws IOException {
        return storage(path).status(path);
    }

    @Override
    public String resolvePath(String path) throws IOException {
        return storage(path).qualify(path);
    }

    @Override
    public InputFile newInputFile(String path, long fileSize) {
        return new Streams.KernelFile(storage(path), path, fileSize);
    }

    /** Parquet's view of the same file. */
    org.apache.parquet.io.InputFile newParquetFile(String path, long fileSize) {
        return new Streams.ParquetFile(storage(path), path, fileSize);
    }

    @Override
    public Optional<String> getConf(String confKey) {
        return Optional.ofNullable(conf.get(confKey));
    }

    @Override
    public boolean mkdirs(String path) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public OutputFile newOutputFile(String path) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public boolean delete(String path) {
        throw new UnsupportedOperationException(READ_ONLY);
    }

    @Override
    public void copyFileAtomically(String srcPath, String destPath, boolean overwrite) {
        throw new UnsupportedOperationException(READ_ONLY);
    }
}
