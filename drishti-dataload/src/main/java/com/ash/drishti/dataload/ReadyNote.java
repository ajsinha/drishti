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
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;

/**
 * What a {@code <file>.ready} marker may say (DATALOAD_INBOX.md section 3.5; all optional, an empty marker is fine): the routing of this
 * one file over the rules ({@code pack}, {@code kind}, {@code connector}, {@code table}, {@code business-date}, {@code date-field}),
 * {@code mode} ({@code replace}, the loader's only mode) and the checks made before loading ({@code sha256}, {@code rows}).
 */
public record ReadyNote(String pack, String kind, String connector, String table, LocalDate businessDate, String dateField, String mode, String sha256,
        Long rows) {

    private static final java.util.regex.Pattern SHA = java.util.regex.Pattern.compile("(?m)^\\s*[\"']?sha256[\"']?\\s*:\\s*[\"']?([0-9A-Fa-f]{64})[\"']?\\s*,?\\s*$");
    private static final Set<String> KEYS = Set.of("pack", "kind", "connector", "table", "business-date", "date-field", "mode", "sha256", "rows");

    /** An empty marker. */
    public static final ReadyNote EMPTY = new ReadyNote(null, null, null, null, null, null, null, null, null);

    /** The marker's contents (YAML or JSON); an absent or empty file is {@link #EMPTY}; a key it does not know is refused. */
    public static ReadyNote read(Path marker) throws IOException {
        if (marker == null || !Files.isRegularFile(marker) || Files.size(marker) == 0) {
            return EMPTY;
        }
        String text = Files.readString(marker);
        if (text.isBlank()) {
            return EMPTY;
        }
        JsonNode n;
        try {
            n = new YAMLMapper().readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("the .ready file is not YAML or JSON: " + e.getOriginalMessage(), e);
        }
        if (n == null || n.isNull() || n.isMissingNode()) {
            return EMPTY;
        }
        if (!n.isObject()) {
            throw new IllegalArgumentException("the .ready file holds keys (pack:, kind:, business-date:, ...) or nothing");
        }
        n.fieldNames().forEachRemaining(k -> {
            if (!KEYS.contains(k)) {
                throw new IllegalArgumentException("the .ready file's key " + k + " is not one of " + KEYS.stream().sorted().toList());
            }
        });
        LocalDate date = null;
        String d = text(n, "business-date");
        if (d != null) {
            try {
                date = LocalDate.parse(d);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("the .ready file's business-date " + d + ": give YYYY-MM-DD");
            }
        }
        String mode = text(n, "mode");
        if (mode != null) {
            mode = mode.toLowerCase(Locale.ROOT);
            if (!mode.equals("replace")) {
                throw new IllegalArgumentException("the .ready file's mode " + mode + ": the loader replaces each business date the file holds "
                        + "(mode: replace); appending is not offered");
            }
        }
        // the checksum as written: a YAML reader takes 64 digits for a number, so it is read from the text itself
        java.util.regex.Matcher m = SHA.matcher(text);
        String sha = m.find() ? m.group(1) : text(n, "sha256");
        if (sha != null && !sha.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException("the .ready file's sha256 is not 64 hexadecimal characters");
        }
        Long rows = null;
        if (n.hasNonNull("rows")) {
            if (!n.get("rows").canConvertToLong() || n.get("rows").asLong() < 0) {
                throw new IllegalArgumentException("the .ready file's rows is not a whole number");
            }
            rows = n.get("rows").asLong();
        }
        return new ReadyNote(text(n, "pack"), text(n, "kind"), text(n, "connector"), text(n, "table"), date, text(n, "date-field"), mode,
                sha == null ? null : sha.toLowerCase(Locale.ROOT), rows);
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
