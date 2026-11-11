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
package com.ash.drishti.engine.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.UnreadableData;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.command.CommandsProperties;
import com.ash.drishti.engine.command.Mnemonics;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.format.Formats;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * DATA-01: a source that fails or does not answer while a kind is listed or read must not make a search look exact and
 * empty. The search is partial and names the source and why; a source's own word on what cannot be read (an
 * {@link UnreadableData}, say an unsupported codec) is passed on as it is.
 */
class SearchFailuresTest {

    /** What a fake source does when asked. */
    enum Mode { OK, LIST_FAILS, LIST_SLOW, LIST_INCOMPLETE, READ_UNREADABLE, COLUMNS_UNREADABLE, COLUMNS_INCOMPLETE }

    /** A source of trades holding {@code ids} (mtm = the number after "T", negated when odd), misbehaving per {@code mode}. */
    record Fake(String name, Mode mode, List<String> ids) implements SourcePlugin {
        public PluginManifest manifest() {
            return new PluginManifest(name, "t", Set.of("trade"), new SourceCapabilities(false, false, true, true));
        }

        public void start(SourceContext c) {}

        static double mtm(String id) {
            int n = Integer.parseInt(id.substring(1));
            return n % 2 == 1 ? -n : n;
        }

        public Optional<EntityDocument> fetch(EntityRef ref) {
            return fetch(ref, AsOf.LATEST);
        }

        public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
            if (!ids.contains(ref.id())) {
                return Optional.empty();
            }
            if (mode == Mode.READ_UNREADABLE) {
                throw new UnreadableData("trade 2026-09-30 cannot be read: LZ4 pages; rewrite the date with Snappy", new IllegalStateException("x"));
            }
            return Optional.of(new EntityDocument(ref, DataNode.of(Map.of("mtm", mtm(ref.id()))), new Provenance(name, 1, Instant.now(), false)));
        }

        public List<EntityHit> search(String kind, String text, int limit) {
            switch (mode) {
                case LIST_FAILS -> throw new IllegalStateException("/secret/path/part-0001.parquet is not a Parquet file");
                case LIST_SLOW -> {
                    try {
                        Thread.sleep(3_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                default -> { }
            }
            return ids.stream().map(i -> new EntityHit(EntityRef.of("trade", i), i, name)).toList();
        }

        public Optional<String> listingProblem(String kind) {
            return mode == Mode.LIST_INCOMPLETE ? Optional.of("its list of trade entities could not be rebuilt (the table's log cannot be read)")
                    : Optional.empty();
        }

        public Set<String> columnar(String kind) {
            return mode == Mode.COLUMNS_UNREADABLE || mode == Mode.COLUMNS_INCOMPLETE ? Set.of("mtm") : Set.of();
        }

        public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
            if (mode == Mode.COLUMNS_INCOMPLETE) {                    // the day's columns, without an unreadable line
                double[] mtm = ids.stream().mapToDouble(Fake::mtm).toArray();
                return Optional.of(new ColumnSet(ids.toArray(String[]::new), Map.of("mtm", mtm), Map.of(), null,
                        "1 unreadable line in 2026-09-01/trade.jsonl"));
            }
            throw new UnreadableData("trade 2026-09-30 cannot be read: LZ4 pages", null);
        }
    }

    private static StructuredSearch search(SourcePlugin... plugins) {
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofMillis(500), null, null);
        var router = new SourceRouter(new SourceRegistry(List.of(plugins), props, new JsonCodec()), props, Executors.newVirtualThreadPerTaskExecutor());
        var mnemonics = new Mnemonics(new CommandsProperties(Map.of("TRD", new CommandsProperties.Mnemonic("trade", "Trade")), null, null, null));
        return new StructuredSearch(router, mnemonics, new ElCompiler(), Formats.defaults(), new SearchProperties(1000, Duration.ofMillis(400)));
    }

    private static StructuredSearch.Result run(StructuredSearch s, String q) {
        return s.run(SearchQuery.parse(q), AsOf.LATEST, d -> d);
    }

    @Test
    void aSourceThatFailsWhileListingMakesTheSearchPartialAndSaysWhich() {
        var r = run(search(new Fake("lake", Mode.LIST_FAILS, List.of("T1", "T3"))), "TRD where mtm < 0");
        assertThat(r.matched()).isZero();
        assertThat(r.partial()).as("a failure is not an exact empty answer").isTrue();
        assertThat(r.failed()).containsOnlyKeys("lake");
        assertThat(r.failed().get("lake")).startsWith("failed (IllegalStateException").doesNotContain("/secret/path");
    }

    @Test
    void aSourceThatTimesOutWhileListingIsNamedWithTheBudget() {
        var r = run(search(new Fake("fast", Mode.OK, List.of("T1", "T2")), new Fake("slow", Mode.LIST_SLOW, List.of("T3"))), "TRD where mtm < 0");
        assertThat(r.matched()).isEqualTo(1);
        assertThat(r.partial()).isTrue();
        assertThat(r.failed()).containsOnlyKeys("slow");
        assertThat(r.failed().get("slow")).isEqualTo("did not answer within 400 ms");
    }

    @Test
    void aSourceWhoseListingIsIncompleteSaysSo() {
        var r = run(search(new Fake("lake", Mode.LIST_INCOMPLETE, List.of())), "TRD");
        assertThat(r.scanned()).isZero();
        assertThat(r.partial()).isTrue();
        assertThat(r.failed().get("lake")).contains("could not be rebuilt");
    }

    @Test
    void anUnreadableDocumentPassesOnWhatTheSourceSays() {
        var r = run(search(new Fake("good", Mode.OK, List.of("T1", "T2")), new Fake("lake", Mode.READ_UNREADABLE, List.of("T5"))), "TRD where mtm < 0");
        assertThat(r.matched()).isEqualTo(1);
        assertThat(r.partial()).isTrue();
        assertThat(r.failed().get("lake")).isEqualTo("trade 2026-09-30 cannot be read: LZ4 pages; rewrite the date with Snappy");
    }

    @Test
    void columnsThatCannotBeReadAreReportedAndDocumentsAreReadInstead() {
        var r = run(search(new Fake("lake", Mode.COLUMNS_UNREADABLE, List.of("T1", "T2", "T3"))), "TRD where mtm < 0");
        assertThat(r.matched()).isEqualTo(2);                                    // from the documents
        assertThat(r.partial()).isTrue();
        assertThat(r.failed().get("lake")).contains("cannot be read");
    }

    @Test
    void anIncompleteDayOfColumnsMakesTheSearchPartialWithTheSourcesReason() {
        var r = run(search(new Fake("files", Mode.COLUMNS_INCOMPLETE, List.of("T1", "T2", "T3"))), "TRD where mtm < 0");
        assertThat(r.matched()).isEqualTo(2);                                    // from the columns
        assertThat(r.scanned()).isEqualTo(3);
        assertThat(r.partial()).isTrue();
        assertThat(r.failed()).containsExactly(Map.entry("files", "1 unreadable line in 2026-09-01/trade.jsonl"));
    }

    @Test
    void healthySourcesGiveAnExactAnswer() {
        var r = run(search(new Fake("good", Mode.OK, List.of("T1", "T2", "T3"))), "TRD where mtm < 0");
        assertThat(r.matched()).isEqualTo(2);
        assertThat(r.partial()).isFalse();
        assertThat(r.failed()).isEmpty();
    }
}
