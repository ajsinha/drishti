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
package com.ash.drishti.lakeload;

import com.ash.drishti.iceberg.io.FileAccess;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongConsumer;

/**
 * A local data file read the way the loader reads it, for looking at it before it is loaded (Admin, Data loads, Upload): a plain, gzip or
 * zstd JSON Lines file, or an archive through {@link Archives} with its guards. The documents are counted (one a non-blank line), and the first
 * documents of each dataset are given as a sample, nothing being held but that sample.
 */
public final class FileRows {

    private static final int BLOCK = 1 << 20;
    /** The name of the one dataset of a file that is not an archive. */
    public static final String SINGLE = "";

    /** How many documents the file holds, in all and per dataset (a tar's folders; {@link #SINGLE} for a plain file). */
    public record Counted(long rows, Map<String, Long> perDataset) {}

    private static final class Enough extends IOException {
        private static final long serialVersionUID = 1L;

        Enough() {
            super("enough", null);
        }
    }

    private FileRows() {}

    /** Counts the documents of {@code file}; {@code progress} is told the running total now and then. */
    public static Counted count(Path file, LongConsumer progress) throws IOException {
        Map<String, Long> per = new TreeMap<>();
        long[] total = {0};
        each(file, null, (dataset, in) -> {
            long n = countLines(in, total, progress);
            per.merge(dataset, n, Long::sum);
        });
        if (progress != null) {
            progress.accept(total[0]);
        }
        return new Counted(total[0], per);
    }

    /** The first {@code limit} non-blank lines of each dataset of {@code file}. */
    public static Map<String, List<String>> sample(Path file, int limit) throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!Archives.isArchive(file.getFileName().toString())) {
            List<String> lines = new ArrayList<>();
            try (InputStream in = plain(file)) {
                take(in, lines, limit);
            }
            out.put(SINGLE, lines);
            return out;
        }
        try (FileAccess io = access()) {
            Archives.Limits limits = Archives.Limits.defaults(System::getenv);
            Archives.Plan plan = Archives.plan(io, file.toString(), Files.size(file), null, limits);
            for (Map.Entry<String, List<Archives.Member>> e : plan.datasets().entrySet()) {
                List<String> lines = new ArrayList<>();
                out.put(e.getKey(), lines);
                try {
                    Archives.read(io, plan, e.getValue(), limits, new Meter("sample", 1), BLOCK, (m, in) -> {
                        take(in, lines, limit);
                        if (lines.size() >= limit) {
                            throw new Enough();
                        }
                    });
                } catch (Enough done) {
                    // the sample is full: the rest of the dataset is not read
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                    throw new IOException("stopped while reading " + file.getFileName(), x);
                }
            }
        }
        return out;
    }

    private interface Sink {
        void accept(String dataset, InputStream jsonl) throws IOException;
    }

    private static void each(Path file, Object unused, Sink sink) throws IOException {
        if (!Archives.isArchive(file.getFileName().toString())) {
            try (InputStream in = plain(file)) {
                sink.accept(SINGLE, in);
            }
            return;
        }
        try (FileAccess io = access()) {
            Archives.Limits limits = Archives.Limits.defaults(System::getenv);
            Archives.Plan plan = Archives.plan(io, file.toString(), Files.size(file), null, limits);
            Archives.read(io, plan, plan.members(), limits, new Meter("count", 1), BLOCK, (m, in) -> sink.accept(m.dataset(), in));
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            throw new IOException("stopped while reading " + file.getFileName(), x);
        }
    }

    private static FileAccess access() throws IOException {
        return new FileAccess(com.ash.drishti.lakeio.S3Settings.from(Map.of()), com.ash.drishti.iceberg.io.Relocation.none(), null);
    }

    /** A file that is not an archive, decompressed by what its first bytes say. */
    private static InputStream plain(Path file) throws IOException {
        InputStream raw = new BufferedInputStream(Files.newInputStream(file), BLOCK);
        raw.mark(4);
        byte[] m = raw.readNBytes(4);
        raw.reset();
        if (m.length >= 2 && (m[0] & 0xff) == 0x1f && (m[1] & 0xff) == 0x8b) {
            return new GzipMembers(raw, BLOCK);
        }
        if (m.length == 4 && (m[0] & 0xff) == 0x28 && (m[1] & 0xff) == 0xb5 && (m[2] & 0xff) == 0x2f && (m[3] & 0xff) == 0xfd) {
            return new com.github.luben.zstd.ZstdInputStream(raw);
        }
        return raw;
    }

    private static void take(InputStream in, List<String> lines, int limit) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), BLOCK);
        String line;
        while (lines.size() < limit && (line = r.readLine()) != null) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
    }

    private static long countLines(InputStream in, long[] total, LongConsumer progress) throws IOException {
        byte[] buf = new byte[BLOCK];
        long n = 0;
        boolean text = false;
        int got;
        while ((got = in.read(buf)) > 0) {
            for (int i = 0; i < got; i++) {
                byte b = buf[i];
                if (b == '\n') {
                    if (text) {
                        n++;
                        total[0]++;
                    }
                    text = false;
                } else if (b != ' ' && b != '\t' && b != '\r') {
                    text = true;
                }
            }
            if (progress != null) {
                progress.accept(total[0]);
            }
        }
        if (text) {
            n++;
            total[0]++;
        }
        return n;
    }
}
