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
import io.delta.kernel.defaults.engine.fileio.FileIO;
import io.delta.kernel.defaults.engine.fileio.InputFile;
import io.delta.kernel.utils.CloseableIterator;
import io.delta.kernel.utils.FileStatus;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/** A writing {@link FileIO}'s read side: every read as the native engine reads ({@link NativeFileIO}), the subclass adding the writes. */
abstract class ReadingFileIO implements FileIO {

    protected final NativeFileIO reads;
    private final Map<String, String> conf;

    ReadingFileIO(NativeFileIO reads, Map<String, String> conf) {
        this.reads = reads;
        this.conf = Map.copyOf(conf);
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
}
