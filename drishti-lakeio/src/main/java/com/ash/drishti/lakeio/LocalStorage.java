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
package com.ash.drishti.lakeio;

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

    public LocalStorage(boolean windows) {
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
    public List<FileEntry> listFrom(String path) throws IOException {
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = p.lastIndexOf('/');
        String parent = slash < 0 ? "." : p.substring(0, slash);
        String from = p.substring(slash + 1);
        Path dir = path(parent);
        List<FileEntry> out = new ArrayList<>();
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
                    out.add(new FileEntry(parent + "/" + name, a.size(), a.lastModifiedTime().toMillis()));
                }
            }
        } catch (NoSuchFileException | NotDirectoryException e) {
            throw notFound(parent, e);
        }
        out.sort(Comparator.comparing(FileEntry::path));
        return out;
    }

    @Override
    public FileEntry status(String path) throws IOException {
        try {
            BasicFileAttributes a = Files.readAttributes(path(path), BasicFileAttributes.class);
            return new FileEntry(path, a.isDirectory() ? 0 : a.size(), a.lastModifiedTime().toMillis());
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

    @Override
    public java.io.OutputStream create(String path) throws IOException {
        Path p = path(path);
        Files.createDirectories(p.getParent());
        return new java.io.BufferedOutputStream(com.ash.drishti.api.ChannelIo.newOutputStream(p), 1 << 16);
    }

    /**
     * The content is written to a temporary file beside the target, then the target is <em>created</em> as a hard link
     * to it, which fails when the name exists (on every file system Drishti runs on: ext4, xfs, APFS, NTFS, SMB shares).
     * A file system without hard links (FAT, some network mounts) falls back to an exclusive create of the target
     * ({@code CREATE_NEW}), still atomic as a claim on the name.
     */
    @Override
    public boolean createIfAbsent(String path, byte[] content) throws IOException {
        Path target = path(path);
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            return false;
        }
        Path temp = target.resolveSibling(target.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
        Files.write(temp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.createLink(target, temp);
            return true;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            return false;
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            if (e instanceof java.nio.file.FileSystemException fe && Files.exists(target)) {
                return false;                                    // taken meanwhile (some systems report EEXIST this way)
            }
            try {
                Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return true;
            } catch (java.nio.file.FileAlreadyExistsException taken) {
                return false;
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public boolean exclusiveCreates() {
        return true;
    }

    @Override
    public void put(String path, byte[] content) throws IOException {
        Path target = path(path);
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
        Files.write(temp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            // a reader sees the old content or the new, never a partly written file
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public boolean delete(String path) throws IOException {
        return Files.deleteIfExists(path(path));
    }

    private static FileNotFoundException notFound(String path, IOException cause) {
        FileNotFoundException e = new FileNotFoundException("no such file or directory: " + path);
        e.initCause(cause);
        return e;
    }

    /**
     * Positional reads of a local file: {@link FileChannel#read(ByteBuffer, long)} is safe without a lock. Reads go
     * through {@link com.ash.drishti.api.ChannelIo}, so a multi-megabyte read into a heap buffer never makes the JDK
     * cache a direct buffer of that size per thread (the 2.9.6 direct-memory exhaustion).
     */
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
            return com.ash.drishti.api.ChannelIo.read(channel, dst, position);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
