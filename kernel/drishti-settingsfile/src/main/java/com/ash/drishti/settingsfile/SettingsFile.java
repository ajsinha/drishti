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
package com.ash.drishti.settingsfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The optional settings file: {@code -Ddrishti.settings.file=/absolute/path/drishti.ini} on the command line (an IDE's VM options, a JUnit run
 * template, Maven's {@code -D}, surefire's argLine). The path is never discovered: no environment search, no home folder, no working
 * folder. A relative path is refused and a missing file is an error. Format: one {@code key=value} per line, the key a full system-property name
 * (for example {@code drishti.data.dir}); blank lines and lines starting with {@code #} or {@code ;} are comments; a {@code [section]} line is
 * accepted for grouping and ignored (it does not prefix the keys). Every entry becomes a system property unless that property is already set:
 * an explicit {@code -D} beats the file, the file beats the defaults. Pure Java, no dependencies (drishti-common depends on this module, so the
 * server reaches it through that).
 */
public final class SettingsFile {

    /** The system property that names the file. */
    public static final String PROPERTY = "drishti.settings.file";

    private SettingsFile() {
    }

    /**
     * Applies the file named by {@link #PROPERTY}, if any, to the system properties and returns the keys it set, in file order (empty when the
     * property is absent, or when every entry was already set explicitly). Safe to call again: set entries are never overwritten. Call it first
     * thing in {@code main}, before anything reads a property.
     *
     * @throws IllegalArgumentException when the path is relative
     * @throws IllegalStateException when the file is missing or unreadable, or a line is not key=value
     */
    public static List<String> applyFromSystemProperty() {
        String named = System.getProperty(PROPERTY);
        List<String> set = new ArrayList<>();
        if (named == null || named.isBlank()) return set;
        for (Map.Entry<String, String> e : read(named.strip()).entrySet()) {
            if (System.getProperty(e.getKey()) == null) {
                System.setProperty(e.getKey(), e.getValue());
                set.add(e.getKey());
            }
        }
        return set;
    }

    /** Reads the file at an absolute path into its entries, in file order (a later duplicate key wins). */
    public static Map<String, String> read(String path) {
        Path file = Path.of(path);
        if (!file.isAbsolute()) {
            throw new IllegalArgumentException(PROPERTY + " must be a full absolute path, got \"" + path
                    + "\" (it is never searched for and never relative to the working folder)");
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(PROPERTY + " names " + file + ", which is not a file");
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(PROPERTY + ": cannot read " + file + ": " + e.getMessage(), e);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || (line.startsWith("[") && line.endsWith("]"))) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalStateException(file + ":" + (i + 1) + ": expected key=value, got \"" + line + "\"");
            }
            out.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return out;
    }
}
