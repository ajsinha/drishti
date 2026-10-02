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
package com.ash.drishti.plugin.delta;

import com.ash.drishti.api.UnreadableData;
import com.ash.drishti.deltalake.UnsupportedCodec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What of a lake cannot be read right now, per table, from the reads that failed: a business date's files (a truncated
 * Parquet file, pages in a codec the engine does not decompress), a table's log (deleted or corrupt commits), the ids
 * type-ahead lists. Health reports them (DEGRADED, naming the table and date) until a read of the same table and date
 * succeeds or the table gets a new version; searches report a table whose ids could not be listed as incomplete. A
 * failure the reader can act on (an unsupported codec) becomes an {@link UnreadableData}, so the API shows what to do.
 * Thread-safe: reads on many virtual threads record and clear concurrently.
 */
final class TableProblems {

    /** Where in a table a failure was: its log, or a business date's files. */
    static final String LOG = "log";

    private static final System.Logger LOGGER = System.getLogger(TableProblems.class.getName());
    private static final int MAX_REASON = 300;

    /** One failure: the table version it was seen at ({@code -1} when the log itself failed), why, when. */
    record Problem(String kind, String where, long version, String reason, Instant at) {}

    /** The ids of a kind that could not be listed, and since when; the listing kept is from {@code keptFrom} (null: none). */
    record Listing(String reason, Instant at, Instant keptFrom) {}

    private final String source;
    private final Map<String, Problem> byKey = new ConcurrentHashMap<>();
    private final Map<String, Listing> listings = new ConcurrentHashMap<>();

    TableProblems(String source) {
        this.source = source;
    }

    private static String key(String kind, String where) {
        return kind + "\u001f" + where;
    }

    /**
     * Records that reading {@code where} of the kind's table failed, logging it when it is new, and returns what the read
     * should throw: an {@link UnreadableData} saying what to do when the cause is one the reader can act on, else
     * {@code e} itself.
     */
    RuntimeException failed(String kind, String where, long version, RuntimeException e) {
        RuntimeException thrown = classify(kind, where, e);
        // health names the table and date itself: the reason is the advice, or the cause
        String reason = shorten(advice(e).orElseGet(() -> e instanceof UnreadableData ? e.getMessage() : describe(e)));
        Problem was = byKey.put(key(kind, where), new Problem(kind, where, version, reason, Instant.now()));
        if (was == null || !was.reason().equals(reason)) {
            LOGGER.log(System.Logger.Level.WARNING, "{0}: {1} {2} cannot be read: {3}", source, kind, where, reason);
        }
        return thrown;
    }

    /** A read of {@code where} of the kind's table succeeded: a problem recorded there is over. */
    void ok(String kind, String where) {
        if (byKey.isEmpty()) {
            return;                                          // the common case: nothing to clear, no allocation
        }
        Problem was = byKey.remove(key(kind, where));
        if (was != null) {
            LOGGER.log(System.Logger.Level.INFO, "{0}: {1} {2} is readable again", source, kind, where);
        }
    }

    /** The kind's table is now at {@code version}: failures seen at other versions may be fixed, so they are forgotten. */
    void version(String kind, long version) {
        byKey.values().removeIf(p -> p.kind().equals(kind) && p.version() >= 0 && p.version() != version);
    }

    /** The kind's ids could not be listed again; the previous listing (from {@code keptFrom}, null when none) is kept. */
    void listingFailed(String kind, String reason, Instant keptFrom) {
        Listing was = listings.get(kind);
        listings.put(kind, new Listing(shorten(reason), was == null ? Instant.now() : was.at(), keptFrom));
    }

    void listingOk(String kind) {
        listings.remove(kind);
    }

    /** Why searches of the kind are incomplete, for the router to report with them; empty when they are complete. */
    Optional<String> listing(String kind) {
        Listing l = listings.get(kind);
        if (l == null) {
            return Optional.empty();
        }
        return Optional.of("its list of " + kind + " entities could not be rebuilt (" + l.reason() + "); "
                + (l.keptFrom() == null ? "it lists none" : "it lists those of " + l.keptFrom()));
    }

    /** Every table part that cannot be read, oldest first, for health; empty when all reads succeed. */
    List<Problem> all() {
        List<Problem> out = new ArrayList<>(byKey.values());
        out.sort(java.util.Comparator.comparing(Problem::at));
        return out;
    }

    boolean isEmpty() {
        return byKey.isEmpty() && listings.isEmpty();
    }

    /** One line for health: "trade 2026-09-30: why; book log: why". */
    String summary(int max) {
        List<String> parts = new ArrayList<>();
        for (Problem p : all()) {
            parts.add(p.kind() + " " + p.where() + ": " + p.reason());
        }
        listings.forEach((kind, l) -> {
            if (parts.stream().noneMatch(s -> s.startsWith(kind + " "))) {
                parts.add(kind + " ids: " + l.reason());
            }
        });
        int shown = Math.min(max, parts.size());
        String line = String.join("; ", parts.subList(0, shown));
        return parts.size() > shown ? line + "; and " + (parts.size() - shown) + " more" : line;
    }

    /** An {@link UnreadableData} for causes the reader can act on (an unsupported codec, on either engine); else {@code e}. */
    static RuntimeException classify(String kind, String where, RuntimeException e) {
        if (e instanceof UnreadableData) {
            return e;
        }
        String at = kind + (LOG.equals(where) ? " (the table's log)" : " " + where);
        return advice(e).<RuntimeException>map(a -> new UnreadableData(at + " cannot be read: " + a, e)).orElse(e);
    }

    /** What the reader can do about {@code e}, when it is a cause they can act on (an unsupported codec, either engine). */
    private static Optional<String> advice(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof UnsupportedCodec u) {
                return Optional.of(u.getMessage());
            }
            if ("LZ4Exception".equals(c.getClass().getSimpleName())) {       // lz4-java's, shaded or not (no compile dependency)
                // parquet-java reads Hadoop-framed LZ4; delta-rs and Arrow write LZ4 pages framed otherwise (LZ4_RAW it reads)
                return Optional.of("its Parquet pages are LZ4 as delta-rs and Arrow write it, which the hadoop engine does not decompress"
                        + " (it reads Hadoop-framed LZ4 and LZ4_RAW); rewrite the date with Snappy or ZSTD"
                        + " (tools/lake/maintain.py relayout --force --dates <date>)");
            }
        }
        return Optional.empty();
    }

    /** The cause's type and message, innermost first (what health and the log need to find the file). */
    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String m = root.getMessage();
        return root.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }

    private static String shorten(String s) {
        String one = s == null ? "" : s.replace('\n', ' ');
        return one.length() <= MAX_REASON ? one : one.substring(0, MAX_REASON - 1) + "…";
    }
}
