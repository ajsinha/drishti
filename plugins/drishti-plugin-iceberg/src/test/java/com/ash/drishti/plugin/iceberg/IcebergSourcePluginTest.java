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

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.iceberg.Table;
import org.junit.jupiter.api.Test;

/**
 * The Iceberg connector over path-based tables on local disk, loaded with the contract's rows (trades with
 * {@code mtm} and {@code nettingSet} promoted, counterparties effective-dated): the same tests as the Delta Lake,
 * PostgreSQL and Aerospike connectors.
 */
class IcebergSourcePluginTest extends DatedSourceContract {

    private static IcebergSourcePlugin plugin;
    static Path root;

    /** Writes rows (any order) as the layout does: a table per kind, each business day sorted by id, one commit a day. */
    static void write(IcebergLake lake, List<Row> rows, Map<String, Map<String, Boolean>> promotedByKind) {
        JsonCodec codec = new JsonCodec();
        Map<String, Map<LocalDate, TreeMap<String, IcebergLayout.Row>>> byKind = new LinkedHashMap<>();
        for (Row r : rows) {
            Map<String, Object> cols = new LinkedHashMap<>();
            var doc = codec.read(r.json());
            promotedByKind.getOrDefault(r.kind(), Map.of()).forEach((path, numeric) -> {
                var v = doc.get(path);
                cols.put(path, v.isMissing() || v.isNull() ? null : numeric ? (Object) v.asDouble() : v.asText());
            });
            byKind.computeIfAbsent(r.kind(), k -> new TreeMap<>()).computeIfAbsent(r.date(), d -> new TreeMap<>())
                    .put(r.id(), new IcebergLayout.Row(r.id(), r.json(), cols));
        }
        byKind.forEach((kind, days) -> {
            Table t = IcebergLayout.open(lake, kind, promotedByKind.getOrDefault(kind, Map.of()), IcebergLayout.DEFAULT_ROW_GROUP_BYTES, 1000);
            days.forEach((date, sorted) -> IcebergLayout.commitDay(t, date, IcebergLayout.writeDay(t, date, sorted.values().iterator(), 1000)));
        });
    }

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        if (plugin != null) {
            return plugin;
        }
        root = Files.createTempDirectory("drishti-iceberg");
        Map<String, Boolean> trade = new LinkedHashMap<>();
        trade.put("mtm", true);
        trade.put("nettingSet", false);
        try (IcebergLake lake = IcebergLake.of(Map.of("root", root.toString(), "domain", "desk"))) {
            write(lake, ROWS, Map.of("trade", trade));
        }
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        p.start(context(new HashMap<>(Map.of("root", root.toString(), "domain", "desk", "mode.counterparty", "effective",
                "source-name", "desk-iceberg", "layout.trade.columns", "mtm,nettingSet"))));
        plugin = p;
        return p;
    }

    @Test
    void aDaysPromotedColumnsAnswerSearchesAndReverseLookupsWithoutDocuments() throws Exception {
        SourcePlugin p = plugin();
        assertThat(p.columnar("trade")).containsExactlyInAnyOrder("mtm", "nettingSet");
        assertThat(p.columnar("counterparty")).isEmpty();
        ColumnSet c = p.columns("trade", List.of("mtm", "nettingSet"), AsOf.LATEST).orElseThrow();
        assertThat(c.size()).isEqualTo(2);
        for (int i = 0; i < c.size(); i++) {
            var doc = p.fetch(EntityRef.of("trade", c.ids()[i])).orElseThrow().data();
            assertThat((Double) c.value("mtm", i)).isEqualTo(doc.get("mtm").asDouble());
            assertThat(c.value("nettingSet", i)).isEqualTo(doc.get("nettingSet").asText());
        }
        assertThat(p.columns("trade", List.of("notional"), AsOf.LATEST)).isEmpty();
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(LocalDate.of(2026, 9, 1)))).isEmpty();   // a day it does not hold
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(D1)).orElseThrow().size()).isEqualTo(3);
        assertThat(p.cacheStats()).containsEntry("partitions", 0L);   // no whole day of documents was read
    }

    @Test
    void manifestAndLastUpdate() throws Exception {
        SourcePlugin p = plugin();
        assertThat(p.manifest().name()).isEqualTo("desk-iceberg");
        assertThat(p.lastUpdate()).isNotNull();
        assertThat(p.fetch(EntityRef.of("trade", "T-1")).orElseThrow().provenance().source()).isEqualTo("desk-iceberg");
    }
}
