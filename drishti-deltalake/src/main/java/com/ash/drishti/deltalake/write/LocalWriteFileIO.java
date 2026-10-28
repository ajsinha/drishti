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

import com.ash.drishti.lakeio.LocalPaths;
import com.ash.drishti.deltalake.NativeFileIO;
import com.ash.drishti.lakeio.S3Settings;
import io.delta.kernel.defaults.engine.fileio.FileIO;
import io.delta.kernel.defaults.engine.fileio.InputFile;
import io.delta.kernel.defaults.engine.fileio.OutputFile;
import io.delta.kernel.defaults.engine.fileio.PositionOutputStream;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The native engine's file access with writing added, for local disk only: reads go to {@link NativeFileIO} (no Hadoop file
 * system, no {@code winutils.exe}), writes through {@code java.nio}. A commit file is written beside its target and moved into
 * place; when the target must not exist yet (a Delta commit), it is linked rather than moved, so of two writers of the same
 * version exactly one wins and the other gets {@link FileAlreadyExistsException}, which Kernel reads as a conflict.
 */
final class LocalWriteFileIO implements FileIO {

    private final NativeFileIO reads;
    private final Map<String, String> conf;

    LocalWriteFileIO(Map<String, String> conf) {
        this.conf = Map.copyOf(conf);
        this.reads = new NativeFileIO(S3Settings.from(Map.of()), conf);
    }

    static Path local(String path) {
        if (!LocalPaths.isLocal(path)) {
            throw new UnsupportedOperationException("the lake writer writes local tables only, not " + path
                    + " (use --engine deltalake for object storage)");
        }
        return Path.of(LocalPaths.toLocal(path, LocalPaths.WINDOWS));
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
        Files.createDirectories(local(path));
        return true;
    }

    @Override
    public boolean delete(String path) throws IOException {
        return Files.deleteIfExists(local(path));
    }

    @Override
    public void copyFileAtomically(String srcPath, String destPath, boolean overwrite) throws IOException {
        Path dest = local(destPath);
        Path tmp = dest.resolveSibling("tmp-" + dest.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Files.copy(local(srcPath), tmp);
        place(tmp, dest, overwrite);
    }

    @Override
    public OutputFile newOutputFile(String path) {
        Path target = local(path);
        return new OutputFile() {
            @Override
            public String path() {
                return path;
            }

            @Override
            public PositionOutputStream create(boolean putIfAbsent) throws IOException {
                Files.createDirectories(target.getParent());
                OutputStream out = putIfAbsent
                        ? com.ash.drishti.api.ChannelIo.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                        : com.ash.drishti.api.ChannelIo.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                return new Counting(new java.io.BufferedOutputStream(out, 1 << 16));
            }

            @Override
            public void writeAtomically(CloseableIterator<String> data, boolean overwrite) throws IOException {
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling("tmp-" + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
                try (data; BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                    while (data.hasNext()) {
                        w.write(data.next());
                        w.write('\n');
                    }
                }
                place(tmp, target, overwrite);
            }
        };
    }

    private static void place(Path tmp, Path target, boolean overwrite) throws IOException {
        try {
            if (overwrite) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            }
            try {
                Files.createLink(target, tmp);                   // fails when the target exists: exactly one writer wins
            } catch (UnsupportedOperationException e) {
                Files.move(tmp, target);                         // no hard links here: a plain move refuses an existing target too
                return;
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** An output stream that knows its position (Parquet writes its footer offsets from it). */
    private static final class Counting extends PositionOutputStream {
        private final OutputStream out;
        private long pos;

        Counting(OutputStream out) {
            this.out = out;
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
        }
    }
}
