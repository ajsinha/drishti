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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.ash.drishti.common.DrishtiException;
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
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * QA 2026-10-01 GRAM-04, GRAM-06 and GRAM-07: field names in a search are read whatever their case, an unknown field is a
 * search problem with the closest names (not an empty result), and limits and unfinished conditions say what is wrong.
 */
class SearchFieldNamesTest {

    static final List<String> IDS = IntStream.rangeClosed(1, 12).mapToObj(i -> "T" + i).toList();

    static String product(String id) {
        return Integer.parseInt(id.substring(1)) % 3 == 0 ? "REVOLVER" : "IRS_FIXFLOAT";
    }

    /** Twelve trades; every third a revolver. With {@code columns}, productType and mtm are also kept as columns. */
    record Trades(boolean columns) implements SourcePlugin {
        public PluginManifest manifest() {
            return new PluginManifest(columns ? "lake" : "docs", "t", Set.of("trade"), new SourceCapabilities(false, false, true, true));
        }

        public void start(SourceContext c) {}

        public Optional<EntityDocument> fetch(EntityRef ref) {
            return fetch(ref, AsOf.LATEST);
        }

        public Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf) {
            if (!IDS.contains(ref.id())) {
                return Optional.empty();
            }
            int n = Integer.parseInt(ref.id().substring(1));
            Map<String, Object> doc = Map.of("productType", product(ref.id()), "mtm", n * 1_000_000.0,
                    "counterparty", Map.of("name", n % 2 == 0 ? "Northbridge" : "Meridian"), "legs", List.of(Map.of("rate", n / 100.0)));
            return Optional.of(new EntityDocument(ref, DataNode.of(doc), new Provenance(manifest().name(), 1, Instant.now(), false)));
        }

        public List<EntityHit> search(String kind, String text, int limit) {
            return IDS.stream().map(i -> new EntityHit(EntityRef.of("trade", i), i, manifest().name())).toList();
        }

        public Set<String> columnar(String kind) {
            return columns ? Set.of("productType", "mtm") : Set.of();
        }

        public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
            if (!columns) {
                return Optional.empty();
            }
            return Optional.of(new ColumnSet(IDS.toArray(String[]::new),
                    Map.of("mtm", IDS.stream().mapToDouble(i -> Integer.parseInt(i.substring(1)) * 1_000_000.0).toArray()),
                    Map.of("productType", IDS.stream().map(SearchFieldNamesTest::product).toArray(String[]::new)), null));
        }
    }

    private static StructuredSearch search(boolean columns) {
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(2), null, null);
        var router = new SourceRouter(new SourceRegistry(List.of(new Trades(columns)), props, new JsonCodec()), props,
                Executors.newVirtualThreadPerTaskExecutor());
        var mnemonics = new Mnemonics(new CommandsProperties(Map.of("TRD", new CommandsProperties.Mnemonic("trade", "Trade")), null, null, null));
        return new StructuredSearch(router, mnemonics, new ElCompiler(), Formats.defaults(), new SearchProperties(1000, Duration.ofSeconds(2)));
    }

    private static StructuredSearch.Result pick(StructuredSearch s, String q) {
        return s.run(SearchQuery.pick(q), AsOf.LATEST, d -> d);
    }

    @ParameterizedTest(name = "columns kept: {0}")
    @ValueSource(booleans = {false, true})
    void fieldNamesIgnoreCaseAsTheGuidesPromise(boolean columns) {
        StructuredSearch s = search(columns);
        int revolvers = pick(s, "TRD productType=Revolver").matched();
        assertThat(revolvers).isEqualTo(4);
        assertThat(pick(s, "trd producttype=revolver").matched()).isEqualTo(revolvers);
        assertThat(pick(s, "TRD PRODUCTTYPE = REVOLVER order by MTM desc limit 2").rows()).extracting(r -> r.ref().id())
                .containsExactly("T12", "T9");
        assertThat(pick(s, "trd producttype=revolver").columns()).contains("$.productType").doesNotContain("$.producttype");
        assertThat(pick(s, "TRD where Counterparty.NAME contains 'north'").matched()).isEqualTo(6);
        assertThat(pick(s, "TRD where LEGS[0].Rate >= 0.1").matched()).isEqualTo(3);
    }

    @ParameterizedTest(name = "columns kept: {0}")
    @ValueSource(booleans = {false, true})
    void anUnknownFieldIsAProblemWithTheClosestNames(boolean columns) {
        StructuredSearch s = search(columns);
        assertThatThrownBy(() -> pick(s, "TRD where nosuchfield > 1")).isInstanceOfSatisfying(DrishtiException.class, e -> {
            assertThat(e.errorCode().code()).isEqualTo("DRS-4004");
            assertThat(e.getMessage()).contains("no trade has a field 'nosuchfield'");
        });
        assertThatThrownBy(() -> pick(s, "TRD prodcttype=revolver")).hasMessageContaining("did you mean productType");
        assertThatThrownBy(() -> pick(s, "TRD where mtm > 0 order by mtmm")).hasMessageContaining("'mtmm'").hasMessageContaining("mtm");
    }

    @ParameterizedTest(name = "limit {0}")
    @ValueSource(strings = {"0", "-5", "1001", "999999999999", "many"})
    void aLimitOutsideItsRangeSaysTheRange(String limit) {
        assertThatThrownBy(() -> SearchQuery.parse("TRD where mtm > 1m limit " + limit)).isInstanceOfSatisfying(DrishtiException.class, e -> {
            assertThat(e.errorCode().code()).isEqualTo("DRS-4004");
            assertThat(e.getMessage()).contains("limit must be a whole number from 1 to 1000", "'" + limit + "'").doesNotContain("For input string");
        });
        assertThat(SearchQuery.parse("TRD where mtm > 1m limit 1000").limit()).isEqualTo(1000);
        assertThat(SearchQuery.parse("TRD where mtm > 1m limit 1").limit()).isEqualTo(1);
    }

    @Test
    void aConditionThatStopsShortSaysSo() {
        assertThatThrownBy(() -> pick(search(false), "TRD where mtm >"))
                .hasMessageContaining("the condition ends early").hasMessageContaining("after '>'").hasMessageNotContaining("''");
    }
}
