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
package com.ash.drishti.plugin.file;

import com.ash.drishti.api.LoadGuard;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Writes rows as the file connector's JSON-lines files: {@code java -cp <plugin classpath>
 * com.ash.drishti.plugin.file.JsonlLoader FILE|- [root] [--future-days N] [--zone Z]}. Each input line is {@code {"domain", "kind", "id", "date",
 * "doc", "columns"}}, as {@code make_data.py --jsonl} and {@code bulk_trades.py --jsonl} write it; it is copied as it is
 * to {@code <root>/<domain>/<date>/<kind>.jsonl}. A file the stream reaches is replaced whole: lines go to a
 * {@code .tmp} file beside it, moved into place at the end, so a running server never reads half a day. At most 256
 * files are open at once. A row dated after tomorrow in the business zone is not written, and the load ends with an
 * error naming it ({@link LoadGuard}). {@code tools/load-files.sh} runs it.
 */
public final class JsonlLoader {

    private static final int OPEN = 256;

    private JsonlLoader() {
    }

    public static void main(String[] args) throws IOException {
        String file = args[0];
        Path root = Path.of(args.length > 1 && !args[1].startsWith("--") ? args[1] : com.ash.drishti.api.DataDir.under("files"));
        LoadGuard guard = LoadGuard.fromArgs(args);
        JsonFactory json = new JsonFactory();
        Set<Path> written = new LinkedHashSet<>();
        Map<Path, BufferedWriter> open = new LinkedHashMap<>(OPEN, 0.75f, true);   // least recently written first
        long n = 0;
        long t0 = System.nanoTime();
        try (BufferedReader in = file.equals("-") ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8), 1 << 20)
                : Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] where = envelope(json, line);
                if (!guard.accept(java.time.LocalDate.parse(where[2]), where[0] + "/" + where[2] + "/" + where[1])) {
                    continue;
                }
                Path target = root.resolve(where[0]).resolve(where[2]).resolve(where[1] + ".jsonl").normalize();
                if (!target.startsWith(root.normalize())) {
                    throw new IOException("a row would be written outside " + root + ": " + where[0] + "/" + where[2] + "/" + where[1]);
                }
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                BufferedWriter w = open.get(tmp);
                if (w == null) {
                    if (open.size() == OPEN) {                                   // close the least recently written
                        var eldest = open.entrySet().iterator().next();
                        eldest.getValue().close();
                        open.remove(eldest.getKey());
                    }
                    Files.createDirectories(tmp.getParent());
                    boolean first = written.add(tmp);                            // the first row of this file: start it afresh
                    w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                            first ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND, StandardOpenOption.WRITE);
                    open.put(tmp, w);
                }
                w.write(line);
                w.write('\n');
                if (++n % 100_000 == 0) {
                    System.err.printf("files: %,d rows%n", n);
                }
            }
        }
        for (BufferedWriter w : open.values()) {
            w.close();
        }
        for (Path tmp : written) {
            String name = tmp.getFileName().toString();
            Files.move(tmp, tmp.resolveSibling(name.substring(0, name.length() - 4)), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        System.out.printf("files: wrote %,d rows into %,d files under %s in %,.0f s%n", n, written.size(), root, (System.nanoTime() - t0) / 1e9);
        guard.finish();
    }

    /** {domain, kind, date} of a row, read without the document. */
    private static String[] envelope(JsonFactory json, String line) throws IOException {
        String[] out = new String[3];
        try (JsonParser p = json.createParser(line)) {
            p.nextToken();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                p.nextToken();
                switch (name) {
                    case "domain" -> out[0] = p.getText();
                    case "kind" -> out[1] = p.getText();
                    case "date" -> out[2] = p.getText();
                    default -> p.skipChildren();
                }
            }
        }
        if (out[0] == null || out[1] == null || out[2] == null || !out[2].matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IOException("a row needs domain, kind and an ISO date: " + line.substring(0, Math.min(120, line.length())));
        }
        return out;
    }
}
