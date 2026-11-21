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
package com.ash.drishti.lakehost;

import com.ash.drishti.api.UnreadableData;
import com.ash.lake.LakeRuntime;
import com.ash.lake.io.PageDecodeException;
import com.ash.lake.io.UnsupportedCodec;
import com.ash.lake.read.LakeAddress;
import com.ash.lake.read.LakeReader;
import com.ash.lake.read.ReadProblems;
import com.ash.lake.spi.TableStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What Drishti's lake connectors share over Drishti Lake's read API ({@code com.ash.lake.read}): opening a connector's lake, the
 * failure a read throws to Drishti's callers, and the connector's own listings (its type-ahead ids, rebuilt by its reindex).
 * Drishti Lake records each read's failure and recovery itself ({@link LakeReader#problems()}).
 */
public final class LakeReads {

    private LakeReads() {
    }

    /** The lake a connector's settings name ({@code root}, {@code domain}, {@code table.<name>}, storage, catalog), in {@code format}. */
    public static LakeReader open(String format, Map<String, String> settings) throws IOException {
        LakeRuntime runtime = DrishtiLake.runtime();
        TableStore store = com.ash.lake.load.Stores.of(runtime, format);
        return store.open(runtime, LakeAddress.of(runtime, settings));
    }

    /**
     * What a read of {@code where} ({@code log} or a business date) of the kind's table throws: an {@link UnreadableData} saying what
     * to do when the cause is one the reader can act on (a codec the engine does not decompress, a page that does not decode), else the
     * failure itself.
     */
    public static RuntimeException failure(String kind, String where, Throwable e) {
        if (e instanceof UnreadableData u) {
            return u;
        }
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof UnsupportedCodec || c instanceof PageDecodeException) {
                String at = kind + ("log".equals(where) ? " (the table's log)" : " " + where);
                return new UnreadableData(at + " cannot be read: " + c.getMessage(), e);
            }
        }
        if (e instanceof RuntimeException r) {
            return r;
        }
        if (e instanceof IOException io) {
            return new UncheckedIOException(io);
        }
        return new IllegalStateException(e);
    }

    /** A connector's listings: the kinds whose ids its last reindex could not rebuild, and what it lists meanwhile. */
    public static final class Listings {
        private record Listing(String reason, Instant at, Instant keptFrom) {}

        private static final int MAX_REASON = 300;
        private final Map<String, Listing> listings = new ConcurrentHashMap<>();

        /** The kind's ids could not be listed again; the previous listing (from {@code keptFrom}, null when none) is kept. */
        public void failed(String kind, String reason, Instant keptFrom) {
            Listing was = listings.get(kind);
            String r = reason == null ? "" : reason.replace('\n', ' ');
            listings.put(kind, new Listing(r.length() <= MAX_REASON ? r : r.substring(0, MAX_REASON - 1) + "…", was == null ? Instant.now() : was.at(), keptFrom));
        }

        public void ok(String kind) {
            listings.remove(kind);
        }

        /** Why searches of the kind are incomplete; empty when they are complete. */
        public Optional<String> listing(String kind) {
            Listing l = listings.get(kind);
            if (l == null) {
                return Optional.empty();
            }
            return Optional.of("its list of " + kind + " entities could not be rebuilt (" + l.reason() + "); "
                    + (l.keptFrom() == null ? "it lists none" : "it lists those of " + l.keptFrom()));
        }

        /** True when neither the reads nor the listings have a problem now. */
        public boolean isEmpty(ReadProblems reads) {
            return listings.isEmpty() && reads.isEmpty();
        }

        /** One line for health: the reads' problems, then the kinds whose ids could not be listed. */
        public String summary(ReadProblems reads, int max) {
            List<String> parts = new ArrayList<>();
            reads.all().forEach(p -> parts.add(p.table() + " " + p.where() + ": " + p.reason()));
            listings.forEach((kind, l) -> {
                if (parts.stream().noneMatch(s -> s.startsWith(kind + " "))) {
                    parts.add(kind + " ids: " + l.reason());
                }
            });
            int shown = Math.min(max, parts.size());
            String line = String.join("; ", parts.subList(0, shown));
            return parts.size() > shown ? line + "; and " + (parts.size() - shown) + " more" : line;
        }
    }
}
