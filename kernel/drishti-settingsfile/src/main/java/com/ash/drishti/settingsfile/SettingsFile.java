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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The optional settings files: {@code -Ddrishti.settings.file=/abs/base.ini[,/abs/secrets.ini,...]} on the command line (an IDE's VM
 * options, a JUnit run template, Maven's {@code -D}, surefire's argLine, {@code bin/start-server --settings}). The paths are never
 * discovered: no environment search, no home folder, no working folder. A relative path is refused and a missing file is an error.
 *
 * <p>Format (docs/admin/CONFIGURATION.md, "The settings file"): one {@code key=value} per line; blank lines and lines starting with
 * {@code #} or {@code ;} are comments; the first {@code =} splits. A section whose name has a dot, {@code [lake.risk]},
 * {@code [pack.market-risk]}, {@code [connector.trade-murex]}, {@code [db.reporting]}, {@code [broker.kafka-eu]}, is a <b>namespace</b>:
 * its keys are prefixed with it ({@code root=/x} under {@code [lake.risk]} is {@code lake.risk.root}), so one lake, pack, connector,
 * database or broker never reads another's settings. A section without a dot ({@code [roots]}, {@code [server]}, {@code [global]}) is a
 * label only: its keys are full names ({@code drishti.data.dir}).
 *
 * <p>Precedence: an explicit {@code -D} beats every file; among the files a later one beats an earlier one (a {@code secrets.ini} after a
 * {@code base.ini}). Every entry becomes a system property, so the one placeholder resolver ({@link Placeholders}) and Spring both see it.
 * {@link #sourceOf} names the file a key came from; {@link #isSecret} tells which values are never shown. Pure Java, no dependencies.
 */
public final class SettingsFile {

    /** The system property that names the files (one absolute path, or several separated by commas). */
    public static final String PROPERTY = "drishti.settings.file";

    /** A key whose value is never shown (logs, Admin, the resolved-paths view): a password, a secret, a token, a credential, a key. */
    private static final Pattern SECRET = Pattern.compile("(?i)(^|[._-])(password|passwd|passphrase|secret|secrets|token|credential|credentials|apikey|api-key|private-key|key)($|[._-])");
    /** The mask shown instead of a secret's value. */
    public static final String MASK = "••••";

    /** Key to the file it was taken from, for the keys this process took from the files. */
    private static final Map<String, String> SOURCES = Collections.synchronizedMap(new LinkedHashMap<>());
    /** Key to the value this class set, so a later call tells its own value from an explicit -D. */
    private static final Map<String, String> SET = Collections.synchronizedMap(new LinkedHashMap<>());
    /** The files read, in order. */
    private static final List<String> FILES = Collections.synchronizedList(new ArrayList<>());

    private SettingsFile() {
    }

    /**
     * Applies the files named by {@link #PROPERTY}, if any, to the system properties and returns the keys they set, in order (empty when the
     * property is absent, or when every entry was already set explicitly). A later file overrides an earlier one; an explicit {@code -D}
     * (a property set before this ran) is never overwritten. Safe to call again. Call it first thing in {@code main}.
     *
     * @throws IllegalArgumentException when a path is relative
     * @throws IllegalStateException when a file is missing or unreadable, or a line is not key=value
     */
    public static synchronized List<String> applyFromSystemProperty() {
        String named = System.getProperty(PROPERTY);
        List<String> set = new ArrayList<>();
        if (named == null || named.isBlank()) return set;
        Map<String, String> merged = new LinkedHashMap<>();
        Map<String, String> from = new LinkedHashMap<>();
        List<String> files = files(named);
        for (String f : files) {
            for (Map.Entry<String, String> e : read(f).entrySet()) {
                merged.put(e.getKey(), e.getValue());                 // a later file wins
                from.put(e.getKey(), f);
            }
        }
        for (Map.Entry<String, String> e : merged.entrySet()) {
            String k = e.getKey();
            String now = System.getProperty(k);
            boolean ours = now != null && now.equals(SET.get(k));     // set by an earlier call of this method, not by -D
            if (now == null || ours) {
                System.setProperty(k, e.getValue());
                SET.put(k, e.getValue());
                SOURCES.put(k, from.get(k));
                if (!ours) set.add(k);
            }
        }
        if (FILES.isEmpty()) FILES.addAll(files);
        return set;
    }

    /** The absolute paths named by a value of {@link #PROPERTY}, in order. */
    public static List<String> files(String named) {
        List<String> out = new ArrayList<>();
        for (String part : named.split(",")) {
            String p = part.strip();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /** The files this process read, in order (empty when none). */
    public static List<String> filesRead() {
        synchronized (FILES) {
            return List.copyOf(FILES);
        }
    }

    /** The file a key was taken from, or null when it did not come from a settings file. */
    public static String sourceOf(String key) {
        return SOURCES.get(key);
    }

    /** Every key taken from the files, with its file, in order. */
    public static Map<String, String> sources() {
        synchronized (SOURCES) {
            return Map.copyOf(SOURCES);
        }
    }

    /** Whether a key's value is a secret: shown as {@link #MASK} everywhere. */
    public static boolean isSecret(String key) {
        return key != null && SECRET.matcher(key).find();
    }

    /** A value as it may be shown: {@link #MASK} for a secret (empty stays empty). */
    public static String shown(String key, String value) {
        return isSecret(key) && value != null && !value.isEmpty() ? MASK : value;
    }

    /** Reads one file at an absolute path into its entries, in file order (a later duplicate key wins); sections as above. */
    public static Map<String, String> read(String path) {
        Path file = Path.of(path);
        if (!file.isAbsolute()) {
            throw new IllegalArgumentException(PROPERTY + " must name full absolute paths, got \"" + path
                    + "\" (a settings file is never searched for and never relative to the working folder)");
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
        return parse(lines, file.toString());
    }

    /** The entries of a file's lines ({@code where} names the file in an error). */
    static Map<String, String> parse(List<String> lines, String where) {
        Map<String, String> out = new LinkedHashMap<>();
        String prefix = "";
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                String section = line.substring(1, line.length() - 1).strip();
                prefix = section.contains(".") ? section + "." : "";   // [lake.risk] is a namespace; [roots] a label
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalStateException(where + ":" + (i + 1) + ": expected key=value, got \"" + line + "\"");
            }
            out.put(prefix + line.substring(0, eq).strip(), line.substring(eq + 1).strip());
        }
        return out;
    }

    /** The environment variable name of a key, as Spring reads it ({@code lake.risk.root} is {@code LAKE_RISK_ROOT}). */
    public static String envName(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    /** For tests: forgets what was read. */
    static synchronized void reset() {
        SOURCES.clear();
        SET.clear();
        FILES.clear();
    }
}
