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
package com.ash.drishti.compute.worker;

import com.ash.drishti.compute.ComputeException;
import com.ash.drishti.compute.arrow.ArrowIpc;
import com.ash.drishti.compute.arrow.ArrowTable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A request's scratch folder ({@code <data dir>/compute/scratch/<request id>/}, docs/architecture/PYTHON_COMPUTE.md section 7.3): the inputs
 * as Arrow files in {@code in/}, the worker's large results in {@code out/}. Readable only by the server's user, and removed when the request
 * ends, whatever happened; folders left by a crash of the server are removed by {@link #sweep} at start.
 */
public final class Scratch implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Scratch.class);

    private final Path dir;

    private Scratch(Path dir) {
        this.dir = dir;
    }

    /** Creates the request's folder and writes its inputs; returns the input names to their paths relative to the folder. */
    public static Scratch create(Path root, String requestId) {
        try {
            Path d = root.resolve(requestId).normalize();
            if (!d.getParent().equals(root.normalize())) {
                throw new IllegalArgumentException("a request id names a folder directly under the scratch root");
            }
            if (Files.exists(d)) {
                throw new ComputeException(ComputeException.Code.REFUSED, "request " + requestId + " is already running");
            }
            ReadOnly.privateFolder(root);
            ReadOnly.privateFolder(d);
            Files.createDirectories(d.resolve("in"));
            return new Scratch(d);
        } catch (IOException e) {
            throw new UncheckedIOException("could not make the scratch folder of " + requestId, e);
        }
    }

    public Path dir() {
        return dir;
    }

    /** Writes the inputs as Arrow files in {@code in/}; returns name to relative path. */
    public Map<String, String> writeInputs(Map<String, ArrowTable> inputs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, ArrowTable> e : inputs.entrySet()) {
            String rel = "in/" + e.getKey() + ".arrows";
            try {
                ArrowIpc.write(e.getValue(), dir.resolve(rel));
            } catch (IOException ex) {
                throw new UncheckedIOException("could not write input " + e.getKey(), ex);
            }
            out.put(e.getKey(), rel);
        }
        return out;
    }

    /**
     * Reads the result tables the worker named. Each path must be a file under {@code out/} (never elsewhere: the worker's answer is
     * untrusted) and no bigger than {@code maxBytes}.
     */
    public Map<String, ArrowTable> readResults(Map<String, String> tables, long maxBytes) {
        Map<String, ArrowTable> out = new LinkedHashMap<>();
        Path outDir = dir.resolve("out").normalize();
        for (Map.Entry<String, String> e : tables.entrySet()) {
            Path p = dir.resolve(e.getValue()).normalize();
            if (!p.startsWith(outDir) || Files.isSymbolicLink(p) || !Files.isRegularFile(p)) {
                throw new ComputeException(ComputeException.Code.INVALID, "the worker named a result file outside its out folder: " + e.getValue());
            }
            try {
                long size = Files.size(p);
                if (size > maxBytes) {
                    throw new ComputeException(ComputeException.Code.INVALID, "result " + e.getKey() + " is " + size + " bytes, over the limit of "
                            + maxBytes + " (compute.limits.max-result-bytes)");
                }
                out.put(e.getKey(), ArrowIpc.read(p));
            } catch (IOException ex) {
                throw new ComputeException(ComputeException.Code.INVALID, "result " + e.getKey() + " is not a valid Arrow stream: " + ex.getMessage());
            }
        }
        return out;
    }

    @Override
    public void close() {
        try {
            ReadOnly.delete(dir);
        } catch (IOException e) {
            LOG.warn("compute: the scratch folder {} could not be removed now ({}); the next sweep removes it", dir, e.getMessage());
        }
    }

    /** Removes every request folder under the root (at start: nothing is running yet). Returns how many were removed. */
    public static int sweep(Path root) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int n = 0;
        try (DirectoryStream<Path> s = Files.newDirectoryStream(root)) {
            for (Path p : s) {
                try {
                    ReadOnly.delete(p);
                    n++;
                } catch (IOException e) {
                    LOG.warn("compute: could not remove the leftover scratch folder {}: {}", p, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warn("compute: could not sweep {}: {}", root, e.getMessage());
        }
        return n;
    }
}
