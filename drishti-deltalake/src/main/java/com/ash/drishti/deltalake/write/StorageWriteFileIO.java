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
package com.ash.drishti.deltalake.write;

import com.ash.drishti.deltalake.NativeFileIO;
import com.ash.drishti.lakeio.LocalPaths;
import com.ash.drishti.lakeio.S3Settings;
import com.ash.drishti.lakeio.Storage;
import io.delta.kernel.defaults.engine.fileio.FileIO;
import io.delta.kernel.defaults.engine.fileio.InputFile;
import io.delta.kernel.defaults.engine.fileio.OutputFile;
import io.delta.kernel.defaults.engine.fileio.PositionOutputStream;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.util.Map;
import java.util.Optional;

/**
 * The lake writer's file access for object storage (2.9.5: official results to {@code s3://}), through drishti-lakeio's {@link Storage}:
 * reads as the native engine reads ({@link NativeFileIO}, no Hadoop), data files streamed with a multipart upload, and a Delta commit
 * ({@code _delta_log/<version>.json}) written with an <b>exclusive create</b> (S3's conditional PUT, {@code If-None-Match: *}): of two
 * writers of the same version exactly one wins and the other gets {@link FileAlreadyExistsException}, which Kernel reads as a conflict
 * and retries on the newer version. Credentials come from {@link S3Settings} (the settings given, else the AWS default chain). Local
 * paths work too, through the same exclusive create.
 */
final class StorageWriteFileIO implements FileIO {

    private final NativeFileIO reads;
    private final Map<String, String> conf;

    StorageWriteFileIO(S3Settings s3, Map<String, String> conf) {
        this.conf = Map.copyOf(conf);
        this.reads = new NativeFileIO(s3, conf);
    }

    private Storage storage(String path) {
        return reads.storage(path);
    }

    @Override
    public CloseableIterator<FileStatus> listFrom(String filePath) throws IOException {
        return reads.listFrom(filePath);
    }

    @Override
    public FileStatus getFileStatus(String path) throws IOException {
        return reads.getFileStatus(path);
    }

    @Override
    public String resolvePath(String path) throws IOException {
        return reads.resolvePath(path);
    }

    @Override
    public InputFile newInputFile(String path, long fileSize) {
        return reads.newInputFile(path, fileSize);
    }

    @Override
    public Optional<String> getConf(String confKey) {
        return Optional.ofNullable(conf.get(confKey));
    }

    @Override
    public boolean mkdirs(String path) throws IOException {
        if (LocalPaths.isLocal(path)) {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(LocalPaths.toLocal(path, LocalPaths.WINDOWS)));
        }
        return true;                                             // an object store has no folders
    }

    @Override
    public boolean delete(String path) throws IOException {
        return storage(path).delete(path);
    }

    @Override
    public void copyFileAtomically(String srcPath, String destPath, boolean overwrite) throws IOException {
        put(destPath, storage(srcPath).readAll(srcPath), overwrite);
    }

    /** Writes whole: replacing, or only when nothing is there (an exclusive create: the loser of a race is told). */
    private void put(String path, byte[] content, boolean overwrite) throws IOException {
        if (overwrite) {
            storage(path).put(path, content);
        } else if (!storage(path).createIfAbsent(path, content)) {
            throw new FileAlreadyExistsException(path);
        }
    }

    @Override
    public OutputFile newOutputFile(String path) {
        return new OutputFile() {
            @Override
            public String path() {
                return path;
            }

            @Override
            public PositionOutputStream create(boolean putIfAbsent) throws IOException {
                if (putIfAbsent) {                               // held, then created exclusively when closed
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    return new Counting(buf, () -> put(path, buf.toByteArray(), false));
                }
                return new Counting(storage(path).create(path), null);
            }

            @Override
            public void writeAtomically(CloseableIterator<String> data, boolean overwrite) throws IOException {
                StringBuilder b = new StringBuilder();
                try (data) {
                    while (data.hasNext()) {
                        b.append(data.next()).append('\n');
                    }
                }
                put(path, b.toString().getBytes(StandardCharsets.UTF_8), overwrite);
            }
        };
    }

    /** What to do when a held stream is closed. */
    private interface OnClose {
        void run() throws IOException;
    }

    /** An output stream that knows its position (Parquet writes its footer offsets from it). */
    private static final class Counting extends PositionOutputStream {
        private final OutputStream out;
        private final OnClose onClose;
        private long pos;

        Counting(OutputStream out, OnClose onClose) {
            this.out = out;
            this.onClose = onClose;
        }

        @Override
        public long getPos() {
            return pos;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            pos++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            pos += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
            if (onClose != null) {
                onClose.run();
            }
        }
    }
}
