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
    private static final int MAX_REASON = 1200;
    private static final Pattern PATH = Pattern.compile("((?:file:|s3a?://|gs://|abfss?://|/)\\S*?)/(?:metadata|data)/(\\S+)");

    private final String source;
    /** Where a kind's table is now (a path-based lake: {@code <root>/<domain>/<kind>}), or null when that is not known (a catalog). */
    private final Function<String, String> whereNow;
    private final Map<String, String> byKind = new ConcurrentHashMap<>();

    TableProblems(String source, Function<String, String> whereNow) {
        this.source = source;
        this.whereNow = whereNow;
    }

    /** Reading the kind's table failed with {@code e}: recorded, and logged in one line when the kind's problem is new or changed. */
    void failed(String kind, Throwable e) {
        String reason = reason(kind, e);
        String was = byKind.put(kind, reason);
        if (!reason.equals(was)) {
            LOGGER.log(System.Logger.Level.WARNING, "{0}: {1} cannot be read: {2}", source, kind, reason);
        }
    }

    /** A read of the kind's table succeeded: a problem recorded for it is over. */
    void ok(String kind) {
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

    /** One line for health: "trade: why; counterparty: why". */
    String summary() {
        List<String> parts = new ArrayList<>();
        new java.util.TreeMap<>(byKind).forEach((kind, why) -> parts.add(kind + ": " + why));
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
                return shorten("its metadata names files under " + old + " that are not there (" + tail(m.group(2))
                        + "): the table was moved or copied after it was written. Write it again where it is now, or read it with "
                        + "engine: native and relocate: " + old + " -> " + (now == null ? "<where it is now>" : now));
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
