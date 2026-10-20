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
package com.ash.drishti.plugin.iceberg;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tables of one Iceberg connector that cannot be read now, one problem per kind, for Admin → Health (QA2-22). A table moved or
 * copied after it was written (its metadata still names the old folder) is said plainly, with what to do; the log has one short line
 * when a kind's problem appears or changes and one when it is over, never a stack trace per refresh.
 */
final class TableProblems {

    private static final System.Logger LOGGER = System.getLogger(TableProblems.class.getName());
    private static final int MAX_REASON = 2000;
    private static final Pattern PATH = Pattern.compile("((?:file:|s3a?://|gs://|abfss?://|/)\\S*?)/(?:metadata|data)/(\\S+)");

    private final String source;
    /** Where a kind's table is now (a path-based lake: {@code <root>/<domain>/<kind>}), or null when that is not known (a catalog). */
    private final Function<String, String> whereNow;
    /** The connector's {@code relocate} (none when not set). */
    private final com.ash.drishti.iceberg.io.Relocation relocate;
    private final Map<String, String> byKind = new ConcurrentHashMap<>();
    /** Per kind with a moved table: the {@code relocate} pair that reads it, so a summary of several moved together gives one. */
    private final Map<String, String> movedBy = new ConcurrentHashMap<>();

    TableProblems(String source, Function<String, String> whereNow) {
        this(source, whereNow, com.ash.drishti.iceberg.io.Relocation.none());
    }

    TableProblems(String source, Function<String, String> whereNow, com.ash.drishti.iceberg.io.Relocation relocate) {
        this.source = source;
        this.whereNow = whereNow;
        this.relocate = relocate == null ? com.ash.drishti.iceberg.io.Relocation.none() : relocate;
    }

    /** Kinds whose table names files that are not there (moved, copied, or files removed). */
    private final java.util.Set<String> missingFiles = ConcurrentHashMap.newKeySet();

    /**
     * Reading the kind's table failed with {@code e}: recorded, and logged in one line when the kind's problem is new or changed. Returns
     * the failure to give the reader: for a table whose files are not there, {@link com.ash.drishti.api.UnreadableData} saying so in words
     * without a path (the 502 says what is wrong; Admin → Health has the folders and the fix), else {@code e} itself.
     */
    Throwable failed(String kind, Throwable e) {
        String reason = reason(kind, e);
        String was = byKind.put(kind, reason);
        if (!reason.equals(was)) {
            LOGGER.log(System.Logger.Level.WARNING, "{0}: {1} cannot be read: {2}", source, kind, reason);
        }
        if (missing(e).isPresent()) {
            missingFiles.add(kind);
            return new com.ash.drishti.api.UnreadableData("the table " + kind + " names files that are not there (it was moved or copied after it "
                    + "was written, or its files were removed); Admin → Health says where they were and the relocate that reads it", e);
        }
        missingFiles.remove(kind);
        return e;
    }

    /** A read of the kind's table succeeded: a problem recorded for it is over. */
    void ok(String kind) {
        movedBy.remove(kind);
        missingFiles.remove(kind);
        if (!byKind.isEmpty() && byKind.remove(kind) != null) {
            LOGGER.log(System.Logger.Level.INFO, "{0}: {1} is readable again", source, kind);
        }
    }

    Optional<String> of(String kind) {
        return Optional.ofNullable(byKind.get(kind));
    }

    boolean isEmpty() {
        return byKind.isEmpty();
    }

    /**
     * One line for health: "trade: why; counterparty: why". Tables moved together (one {@code relocate} reads them all) are one sentence naming
     * them, so Health stays readable with a domain of tables: "credit-limit, cva: their metadata names files … relocate: '/a -> /b'".
     */
    String summary() {
        List<String> parts = new ArrayList<>();
        Map<String, List<String>> byPair = new java.util.TreeMap<>();
        new java.util.TreeMap<>(byKind).forEach((kind, why) -> {
            String pair = movedBy.get(kind);
            if (pair != null) {
                byPair.computeIfAbsent(pair, k -> new ArrayList<>()).add(kind);
            }
        });
        Map<String, String> grouped = new java.util.HashMap<>();
        byPair.forEach((pair, kinds) -> {
            if (kinds.size() > 1) {
                kinds.forEach(k -> grouped.put(k, pair));
            }
        });
        java.util.Set<String> said = new java.util.HashSet<>();
        new java.util.TreeMap<>(byKind).forEach((kind, why) -> {
            String pair = grouped.get(kind);
            if (pair == null) {
                parts.add(kind + ": " + why);
            } else if (said.add(pair)) {
                List<String> kinds = byPair.get(pair);
                parts.add(String.join(", ", kinds) + ": their metadata names files under the folder they were written in, which is not there now: "
                        + "the tables were moved or copied after they were written. Write them again where they are now, or read them with "
                        + "engine: native and relocate: '" + pair + "'");
            }
        });
        return String.join("; ", parts);
    }

    /** Why, in words: a moved table names the folder its metadata points at and what to do; anything else its innermost cause. */
    String reason(String kind, Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        Optional<String> missing = missing(e);
        if (missing.isPresent()) {
            Matcher m = PATH.matcher(missing.get());
            if (m.find()) {
                String old = m.group(1);
                String now = whereNow.apply(kind);
                String shownOld = com.ash.drishti.iceberg.io.Relocation.shown(old);
                // the pair to copy: the folders where the two differ, in the plain form the setting reads (QA3B-04)
                String pair = now == null ? null : com.ash.drishti.iceberg.io.Relocation.suggest(old, now).orElse(null);
                if (pair != null) {
                    movedBy.put(kind, pair);
                } else {
                    movedBy.remove(kind);
                }
                if (relocate.targets(old)) {
                    // the read was relocated, and the files are not in the new folder either
                    return shorten("relocate " + relocate + " sends its reads to " + shownOld + ", and the files are not there either (" + tail(m.group(2))
                            + "): check the new folder in relocate");
                }
                String given = relocate.isEmpty() ? "" : " (relocate " + relocate + " is set, but no old folder in it holds " + shownOld + ")";
                return shorten("its metadata names files under " + shownOld + " that are not there (" + tail(m.group(2))
                        + "): the table was moved or copied after it was written. Write it again where it is now, or read it with "
                        + "engine: native and relocate: '" + (pair == null ? shownOld + " -> <where it is now>" : pair) + "'" + given);
            }
            return shorten("a file its metadata names is not there (" + missing.get() + "): the table was moved or its files removed");
        }
        String msg = root.getMessage();
        return shorten(root.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg));
    }

    /** The path of a file that is not there, when {@code e} is (or is caused by) a file not found. */
    private static Optional<String> missing(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof java.nio.file.NoSuchFileException n && n.getFile() != null) {
                return Optional.of(n.getFile());
            }
            boolean notFound = c instanceof java.io.FileNotFoundException || c.getClass().getSimpleName().equals("NotFoundException");
            if (notFound && c.getMessage() != null) {
                Matcher m = PATH.matcher(c.getMessage());
                if (m.find()) {
                    return Optional.of(m.group());
                }
            }
        }
        return Optional.empty();
    }

    private static String tail(String rest) {
        int slash = rest.lastIndexOf('/');
        return slash < 0 ? rest : rest.substring(slash + 1);
    }

    private static String shorten(String s) {
        String one = s.replace('\n', ' ');
        return one.length() <= MAX_REASON ? one : one.substring(0, MAX_REASON - 1) + "…";
    }
}
