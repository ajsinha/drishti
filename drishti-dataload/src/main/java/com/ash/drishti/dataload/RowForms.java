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
package com.ash.drishti.dataload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * The self-describing row forms (DATA_ARCHITECTURE.md section 9.5; DATA_GUIDE.md section 2.2): <i>loader rows</i>
 * {@code {domain, kind, id, date, doc, columns}} and <i>lake rows</i> {@code {domain, kind, table, id, date, doc}}. The parallel lake loader
 * reads documents (one entity a line) and has no reader for these forms, and the inbox writes no second one: a file whose first line is
 * such a row is refused with the reason and the tool that loads it. Only the first line of a plain, gzip or zstd file is looked at (an
 * archive's members are the loader's to read; rows of these forms there are rejected by the load for want of the pack's id field).
 */
public final class RowForms {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RowForms() {}

    /** Why {@code file} is refused as a row form, or null when its first line is a document (or it cannot be looked at here). */
    public static String refusal(Path file) {
        JsonNode first;
        try {
            first = firstLine(file);
        } catch (IOException | RuntimeException e) {
            return null;                                                     // not ours to judge: the loader reads it and says what is wrong
        }
        if (first == null || !first.isObject() || !first.has("doc") || !first.has("kind") || !first.has("domain") || !first.has("id")) {
            return null;
        }
        String name = file.getFileName().toString();
        if (first.has("table")) {
            return name + " holds lake rows ({domain, kind, table, id, date, doc}): the parallel lake loader reads documents (one entity a line) "
                    + "and has no reader for lake rows, so the inbox does not load them; load them with tools/samplegen/load_rows.py, or drop "
                    + "the documents themselves";
        }
        return name + " holds loader rows ({domain, kind, id, date, doc, columns}): the parallel lake loader reads documents (one entity a "
                + "line) and has no reader for loader rows, so the inbox does not load them; load them with the store's own loader "
                + "(tools/load-*.sh), or drop the documents themselves";
    }

    /** The first non-blank line as JSON (null: none, or not JSON), through gzip or zstd when the file's first bytes say so. */
    static JsonNode firstLine(Path file) throws IOException {
        String n = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".zip") || n.endsWith(".tar") || n.endsWith(".tgz") || n.endsWith(".tar.gz")) {
            return null;
        }
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file))) {
            raw.mark(4);
            byte[] magic = raw.readNBytes(4);
            raw.reset();
            InputStream in = magic.length >= 2 && (magic[0] & 0xff) == 0x1f && (magic[1] & 0xff) == 0x8b ? new GZIPInputStream(raw)
                    : magic.length == 4 && (magic[0] & 0xff) == 0x28 && (magic[1] & 0xff) == 0xb5 && (magic[2] & 0xff) == 0x2f
                            && (magic[3] & 0xff) == 0xfd ? new com.github.luben.zstd.ZstdInputStream(raw) : raw;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            int looked = 0;
            while ((line = r.readLine()) != null && looked++ < 100) {
                if (!line.isBlank()) {
                    try {
                        return JSON.readTree(line);
                    } catch (IOException e) {
                        return null;
                    }
                }
            }
            return null;
        }
    }
}
