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
package com.ash.drishti.tables.migrate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One audit line per migrated item (docs/architecture/BI_DESIGN.md section 4.1, {@code tables-migrate}): who, when, the item, and the hashes of the
 * text before and after; the text before itself is kept in the item's history by the server (stored items) or by version control and the
 * migrated folder (files). The server's implementation writes to its audit log; {@link #jsonl} appends to a file for the command line.
 */
@FunctionalInterface
public interface MigrationAudit {

    /** The action name in every line. */
    String ACTION = "tables-migrate";

    /** Records one item. */
    void record(String who, String item, String before, String after);

    /** No audit (a dry run). */
    MigrationAudit NONE = (who, item, before, after) -> { };

    /** Appends JSON lines to {@code file} (made with its folder when absent). */
    static MigrationAudit jsonl(Path file, Clock clock) {
        return (who, item, before, after) -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("at", clock.instant().toString());
            line.put("action", ACTION);
            line.put("who", who == null || who.isBlank() ? "system" : who);
            line.put("item", item);
            line.put("before", hash(before));
            line.put("after", hash(after));
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write the audit line to " + file, e);
            }
        };
    }

    /** {@code sha256:<hex>} of a text, or null for none. */
    static String hash(String text) {
        if (text == null) {
            return null;
        }
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
