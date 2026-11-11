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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * The order of a search's rows: ties are broken by id on every path (DATA-17), and a field holding numbers in some
 * entities and text in others is ordered and compared the same from columns as from documents (DATA-07).
 */
class SearchOrderTest {

    /**
     * Trades whose mtm is {@code values[i]} (a Double, a String or null) for {@code ids[i]}, listed and kept in the given
     * (not id) order, as a database returns rows in physical order; {@code columnar} says whether it answers columns.
     */
    record Store(String name, List<String> ids, List<Object> values, boolean columnar) implements SourcePlugin {
        public PluginManifest manifest() {
            return new PluginManifest(name, "t", Set.of("trade"), new SourceCapabilities(false, false, true, false));
        }

        public void start(SourceContext c) {}

        public Optional<EntityDocument> fetch(EntityRef ref) {
            int i = ids.indexOf(ref.id());
            if (i < 0) {
                return Optional.empty();
            }
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("id", ref.id());
            doc.put("mtm", values.get(i));
            return Optional.of(new EntityDocument(ref, DataNode.of(doc), new Provenance(name, 1, Instant.now(), false)));
        }

        public List<EntityHit> search(String kind, String text, int limit) {
            return ids.stream().map(i -> new EntityHit(EntityRef.of("trade", i), i, name)).toList();
        }

        public Set<String> columnar(String kind) {
            return columnar ? Set.of("mtm") : Set.of();
        }

        public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
            boolean numbers = values.stream().allMatch(v -> v == null || v instanceof Double);
            boolean texts = values.stream().allMatch(v -> v == null || v instanceof String);
            String[] idArray = ids.toArray(String[]::new);
            if (numbers) {
                return Optional.of(new ColumnSet(idArray, Map.of("mtm", values.stream().mapToDouble(v -> v == null ? Double.NaN : (Double) v).toArray()),
                        Map.of(), null));
            }
            if (texts) {
                return Optional.of(new ColumnSet(idArray, Map.of(), Map.of("mtm", values.toArray(String[]::new)), null));
            }
            return Optional.of(new ColumnSet(idArray, Map.of(), Map.of(), null, null, Map.of("mtm", values.toArray())));
        }
    }

    private static StructuredSearch search(SourcePlugin store) {
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofMillis(500), null, null);
        var router = new SourceRouter(new SourceRegistry(List.of(store), props, new JsonCodec()), props, Executors.newVirtualThreadPerTaskExecutor());
        var mnemonics = new Mnemonics(new CommandsProperties(Map.of("TRD", new CommandsProperties.Mnemonic("trade", "Trade")), null, null, null));
        return new StructuredSearch(router, mnemonics, new ElCompiler(), Formats.defaults(), new SearchProperties(1000, Duration.ofSeconds(2)));
    }

    private static List<String> ids(StructuredSearch s, String q) {
        return s.run(SearchQuery.parse(q), AsOf.LATEST, d -> d).rows().stream().map(r -> r.ref().id()).toList();
    }

    private static Object cell(StructuredSearch s, String q) {
        return s.run(SearchQuery.parse(q), AsOf.LATEST, d -> d).rows().getFirst().values().get("$.mtm");
    }

    /** DATA-17: rows in physical order, three tied at 5 and two at 1: the limit cuts the ties in id order, every time. */
    @Test
    void tiesAreBrokenByIdOnTheColumnsAndTheDocumentsPath() {
        List<String> physical = List.of("T-09", "T-03", "T-07", "T-01", "T-05");
        List<Object> mtm = List.of(5.0, 1.0, 5.0, 1.0, 5.0);
        for (boolean columnar : new boolean[] {true, false}) {
            StructuredSearch s = search(new Store("pg", physical, mtm, columnar));
            assertThat(ids(s, "TRD order by mtm desc limit 2")).as("columnar " + columnar).containsExactly("T-05", "T-07");
            assertThat(ids(s, "TRD order by mtm asc limit 3")).as("columnar " + columnar).containsExactly("T-01", "T-03", "T-05");
            assertThat(ids(s, "TRD order by mtm desc")).as("columnar " + columnar).containsExactly("T-05", "T-07", "T-09", "T-01", "T-03");
        }
    }

    /** DATA-17: entities with no sort value come last in id order, either direction. */
    @Test
    void entitiesWithNoSortValueComeLastInIdOrder() {
        List<String> physical = List.of("T-4", "T-2", "T-3", "T-1");
        List<Object> mtm = java.util.Arrays.asList(null, 7.0, null, 7.0);
        for (boolean columnar : new boolean[] {true, false}) {
            StructuredSearch s = search(new Store("pg", physical, mtm, columnar));
            assertThat(ids(s, "TRD order by mtm desc")).as("columnar " + columnar).containsExactly("T-1", "T-2", "T-3", "T-4");
            assertThat(ids(s, "TRD order by mtm asc")).as("columnar " + columnar).containsExactly("T-1", "T-2", "T-3", "T-4");
        }
    }

    /**
     * DATA-07: mtm holds "N/A" in one entity, 1e20 and 1.2e22 in two others and ordinary numbers elsewhere. Columns and
     * documents order and compare it the same, and the huge numbers keep their value (no clamp to Long.MAX_VALUE).
     */
    @Test
    void aFieldWithNumbersAndTextOrdersAndComparesTheSameFromColumnsAndDocuments() {
        List<String> physical = List.of("T-1", "T-2", "T-3", "T-4", "T-5", "T-6");
        List<Object> mtm = java.util.Arrays.asList(999_478.0, "N/A", 1e20, 1.2e22, 11_641_928.0, null);
        StructuredSearch columns = search(new Store("files", physical, mtm, true));
        StructuredSearch documents = search(new Store("files", physical, mtm, false));
        for (String q : List.of("TRD order by mtm desc", "TRD order by mtm asc", "TRD where mtm > 10000000000000000000",
                "TRD where mtm < 1000000", "TRD where mtm = 'N/A'", "TRD where mtm > 0 order by mtm desc limit 2")) {
            assertThat(ids(columns, q)).as(q).isEqualTo(ids(documents, q));
        }
        assertThat(ids(columns, "TRD where mtm > 10000000000000000000")).containsExactlyInAnyOrder("T-3", "T-4");
        assertThat(ids(columns, "TRD where mtm > 0 order by mtm desc limit 2")).containsExactly("T-4", "T-3");
        assertThat(cell(columns, "TRD where mtm > 1000000000000000000000")).isEqualTo(1.2e22);
        assertThat(((Number) cell(columns, "TRD where mtm < 1000000")).doubleValue())
                .isEqualTo(((Number) cell(documents, "TRD where mtm < 1000000")).doubleValue());
        assertThat(cell(columns, "TRD where mtm = 'N/A'")).isEqualTo("N/A");
    }
}
