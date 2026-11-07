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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a {@code .ready} file may say (docs/guides/DATA_LAKE_GUIDE.md section 4; all optional, an empty file is fine): the overrides of
 * the name ({@code business-date}, {@code table}; refused for a tar), the pack ({@code pack}, {@code kind}, {@code connector}; a tar's
 * {@code folders}: folder → pack), {@code mode} ({@code append}, the default, or {@code replace}), {@code new-table}, {@code key}, the checks
 * made before loading ({@code sha256}, {@code rows}), {@code hold} and {@code lake} (the lake the file goes to, over the rule's and the inbox's
 * default; a lake with no file yet is created). An unknown key is refused.
 */
public record ReadyNote(String pack, String kind, String connector, String table, LocalDate businessDate, String mode, String sha256, Long rows,
        boolean newTable, String key, Map<String, String> folders, boolean hold, String lake) {

    /** A marker that names no lake. */
    public ReadyNote(String pack, String kind, String connector, String table, LocalDate businessDate, String mode, String sha256, Long rows,
            boolean newTable, String key, Map<String, String> folders, boolean hold) {
        this(pack, kind, connector, table, businessDate, mode, sha256, rows, newTable, key, folders, hold, null);
    }

    private static final java.util.regex.Pattern SHA = java.util.regex.Pattern.compile("(?m)^\\s*[\"']?sha256[\"']?\\s*:\\s*[\"']?([0-9A-Fa-f]{64})[\"']?\\s*,?\\s*$");
    /** The keys, in the order the documentation lists them. */
    public static final List<String> KEYS = List.of("business-date", "table", "pack", "kind", "connector", "mode", "new-table", "key", "folders",
            "sha256", "rows", "hold", "lake");

    /** An empty marker. */
    public static final ReadyNote EMPTY = new ReadyNote(null, null, null, null, null, null, null, null, false, null, Map.of(), false);

    /** A marker naming only the routing keys (pack, kind, connector, table) and a business date. */
    public static ReadyNote of(String pack, String kind, String connector, String table, LocalDate businessDate) {
        return new ReadyNote(pack, kind, connector, table, businessDate, null, null, null, false, null, Map.of(), false);
    }

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
            throw new IllegalArgumentException("the .ready file holds keys (" + String.join(", ", KEYS) + ") or nothing");
        }
        n.fieldNames().forEachRemaining(k -> {
            if (!KEYS.contains(k)) {
                throw new IllegalArgumentException("the .ready file's key " + k + " is not one of " + KEYS);
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
            if (!mode.equals("replace") && !mode.equals("append")) {
                throw new IllegalArgumentException("the .ready file's mode " + mode + ": append (the default: the rows are added) or replace (each "
                        + "business date of the file replaced)");
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
        Map<String, String> folders = new LinkedHashMap<>();
        JsonNode f = n.get("folders");
        if (f != null && !f.isNull()) {
            if (!f.isObject()) {
                throw new IllegalArgumentException("the .ready file's folders: a mapping of a tar's folder to a pack ({trades: core})");
            }
            f.fields().forEachRemaining(e -> folders.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().asText()));
        }
        String lake = text(n, "lake");
        if (lake != null && !com.ash.drishti.lakeconfig.Lakes.validName(lake)) {
            throw new IllegalArgumentException("the .ready file's lake " + lake + ": a lake's name, lower-case letters, digits, '-' and '_' "
                    + "([a-z0-9][a-z0-9_-]*: risklake); its tables go to <data>/delta/<lake>/<domain>/<table>");
        }
        String table = text(n, "table");
        return new ReadyNote(text(n, "pack"), text(n, "kind"), text(n, "connector"), table == null ? null : table.toLowerCase(Locale.ROOT), date, mode,
                sha == null ? null : sha.toLowerCase(Locale.ROOT), rows, bool(n, "new-table"), text(n, "key"), Map.copyOf(folders), bool(n, "hold"), lake);
    }

    private static boolean bool(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || v.isNull()) {
            return false;
        }
        if (!v.isBoolean()) {
            throw new IllegalArgumentException("the .ready file's " + key + " is true or false");
        }
        return v.asBoolean();
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
