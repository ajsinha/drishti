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

import io.delta.kernel.engine.FileReadRequest;
import io.delta.kernel.engine.FileSystemClient;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

/**
 * Kernel's file-system client over {@link NativeFileIO}: sorted listings, path resolution, and byte ranges (Kernel
 * reads deletion vectors this way). Read-only; stateless and thread-safe.
 */
final class NativeFileSystemClient implements FileSystemClient {

    private final NativeFileIO io;

    NativeFileSystemClient(NativeFileIO io) {
        this.io = io;
    }

    @Override
    public CloseableIterator<FileStatus> listFrom(String filePath) throws IOException {
        return io.listFrom(filePath);
    }

    @Override
    public String resolvePath(String path) throws IOException {
        return io.resolvePath(path);
    }

    @Override
    public CloseableIterator<ByteArrayInputStream> readFiles(CloseableIterator<FileReadRequest> readRequests) {
        return readRequests.map(r -> range(r.getPath(), r.getStartOffset(), r.getReadLength()));
    }

    private ByteArrayInputStream range(String path, long offset, int length) {
        byte[] bytes = new byte[length];
        try (RangeReader reader = io.storage(path).open(path, -1)) {
            reader.readFully(offset, ByteBuffer.wrap(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + length + " bytes at " + offset + " of " + path, e);
        }
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public FileStatus getFileStatus(String path) throws IOException {
        return io.getFileStatus(path);
    }

    @Override
    public boolean mkdirs(String path) {
        throw new UnsupportedOperationException(NativeFileIO.READ_ONLY);
    }

    @Override
    public boolean delete(String path) {
        throw new UnsupportedOperationException(NativeFileIO.READ_ONLY);
    }

    @Override
    public void copyFileAtomically(String srcPath, String destPath, boolean overwrite) {
        throw new UnsupportedOperationException(NativeFileIO.READ_ONLY);
    }
}
