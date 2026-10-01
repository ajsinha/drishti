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

import io.delta.kernel.utils.FileStatus;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Local disk through {@code java.nio}: no Hadoop, so no {@code winutils.exe} on Windows. Drive letters, backslashes
 * and UNC shares are handled by {@link LocalPaths}. Stateless and thread-safe.
 */
public final class LocalStorage implements Storage {

    private final boolean windows;

    public LocalStorage() {
        this(LocalPaths.WINDOWS);
    }

    LocalStorage(boolean windows) {
        this.windows = windows;
    }

    /** The operating-system path of a path or {@code file:} URI string. */
    public Path path(String path) {
        return Path.of(LocalPaths.toLocal(LocalPaths.decodeUri(path), windows));
    }

    @Override
    public String qualify(String path) {
        Path p = path(path).toAbsolutePath().normalize();
        return LocalPaths.toUri(p.toString(), windows);
    }

    @Override
    public List<FileStatus> listFrom(String path) throws IOException {
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = p.lastIndexOf('/');
        String parent = slash < 0 ? "." : p.substring(0, slash);
        String from = p.substring(slash + 1);
        Path dir = path(parent);
        List<FileStatus> out = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                if (name.compareTo(from) < 0) {
                    continue;
                }
                BasicFileAttributes a;
                try {
                    a = Files.readAttributes(f, BasicFileAttributes.class);
                } catch (NoSuchFileException e) {
                    continue;                                    // removed while listing (a vacuum)
                }
                if (a.isRegularFile()) {
                    out.add(FileStatus.of(parent + "/" + name, a.size(), a.lastModifiedTime().toMillis()));
                }
            }
        } catch (NoSuchFileException | NotDirectoryException e) {
            throw notFound(parent, e);
        }
        out.sort(Comparator.comparing(FileStatus::getPath));
        return out;
    }

    @Override
    public FileStatus status(String path) throws IOException {
        try {
            BasicFileAttributes a = Files.readAttributes(path(path), BasicFileAttributes.class);
            return FileStatus.of(path, a.isDirectory() ? 0 : a.size(), a.lastModifiedTime().toMillis());
        } catch (NoSuchFileException e) {
            throw notFound(path, e);
        }
    }

    @Override
    public RangeReader open(String path, long knownLength) throws IOException {
        try {
            return new ChannelReader(FileChannel.open(path(path), StandardOpenOption.READ), knownLength);
        } catch (NoSuchFileException e) {
            throw notFound(path, e);
        }
    }

    @Override
    public List<String> directories(String path) throws IOException {
        Path dir = path(path);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, Files::isDirectory)) {
            s.forEach(d -> out.add(d.getFileName().toString()));
        }
        out.sort(null);
        return out;
    }

    @Override
    public boolean isDirectory(String path) {
        return Files.isDirectory(path(path));
    }

    private static FileNotFoundException notFound(String path, IOException cause) {
        FileNotFoundException e = new FileNotFoundException("no such file or directory: " + path);
        e.initCause(cause);
        return e;
    }

    /** Positional reads of a local file: {@link FileChannel#read(ByteBuffer, long)} is safe without a lock. */
    private static final class ChannelReader implements RangeReader {
        private final FileChannel channel;
        private final long knownLength;

        ChannelReader(FileChannel channel, long knownLength) {
            this.channel = channel;
            this.knownLength = knownLength;
        }

        @Override
        public long length() throws IOException {
            return knownLength >= 0 ? knownLength : channel.size();
        }

        @Override
        public int read(long position, ByteBuffer dst) throws IOException {
            if (!dst.hasRemaining()) {
                return 0;
            }
            return channel.read(dst, position);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
