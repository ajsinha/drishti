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

    private static final Map<String, IcebergSourcePlugin> PLUGINS = new java.util.concurrent.ConcurrentHashMap<>();
    static Path root;

    /**
     * Writes rows (any order) with Drishti Lake's one loader ({@code source.format: loader-rows}): a table per kind under
     * {@code <root>/<domain>}, the kind's promoted paths as columns (numbers where {@code true}), each business day replaced.
     */
    static void write(Path root, String domain, List<Row> rows, Map<String, Map<String, Boolean>> promotedByKind) throws Exception {
        JsonCodec codec = new JsonCodec();
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Map<LocalDate, List<String>>> byKind = new LinkedHashMap<>();
        for (Row r : rows) {
            var line = json.createObjectNode().put("domain", domain).put("kind", r.kind()).put("id", r.id()).put("date", r.date().toString())
                    .put("doc", r.json());
            var cols = line.putObject("columns");
            var doc = codec.read(r.json());
            promotedByKind.getOrDefault(r.kind(), Map.of()).forEach((path, numeric) -> {
                var v = doc.get(path);
                if (v.isMissing() || v.isNull()) {
                    cols.putNull(path);
                } else if (numeric) {
                    cols.put(path, v.asDouble());
                } else {
                    cols.put(path, v.asText());
                }
            });
            byKind.computeIfAbsent(r.kind(), k -> new TreeMap<>()).computeIfAbsent(r.date(), d -> new java.util.ArrayList<>()).add(line.toString());
        }
        for (var k : byKind.entrySet()) {
            Path in = Files.createTempDirectory("rows-" + k.getKey());
            for (var d : k.getValue().entrySet()) {
                Files.write(in.resolve(k.getKey() + "." + d.getKey() + ".jsonl"), d.getValue());
            }
            String promote = String.join(", ", promotedByKind.getOrDefault(k.getKey(), Map.of()).keySet());
            Path config = in.resolveSibling(in.getFileName() + ".yaml");
            Files.writeString(config, "source:\n  files: '" + in + "/*.jsonl'\n  format: loader-rows\n" + (promote.isEmpty() ? "" : "  promote: [" + promote + "]\n")
                    + "target:\n  format: iceberg\n  lake: '" + root + "'\n  domain: " + domain + "\n  kind: " + k.getKey() + "\n  mode: replace-date\n");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int code = com.ash.lake.load.LakeLoad.run(com.ash.drishti.lakehost.DrishtiLake.runtime(), new String[] {"--config", config.toString()},
                    new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8));
            if (code != 0) {
                throw new IllegalStateException("the loader failed (" + code + "): " + out);
            }
        }
    }

    /** The engine the connector reads with; {@code IcebergSourcePluginNativeTest} runs every test on the other one. */
    protected String engine() {
        return "library";
    }

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        IcebergSourcePlugin known = PLUGINS.get(engine());
        if (known != null) {
            return known;
        }
        root = Files.createTempDirectory("drishti-iceberg");
        Map<String, Boolean> trade = new LinkedHashMap<>();
        trade.put("mtm", true);
        trade.put("nettingSet", false);
        write(root, "desk", ROWS, Map.of("trade", trade));
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        p.start(context(new HashMap<>(Map.of("root", root.toString(), "domain", "desk", "mode.counterparty", "effective",
                "source-name", "desk-iceberg", "layout.trade.columns", "mtm,nettingSet", "engine", engine()))));
        PLUGINS.put(engine(), p);
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

    /** BI runs' planner (ARCHITECTURE.md §28 4.1): a date's rows from the manifests' record_count, equal to the rows written; a date without files is 0. */
    @Test
    void rowEstimateSumsTheManifestsRecordCounts() throws Exception {
        SourcePlugin p = plugin();
        Map<String, java.util.Set<String>> written = new java.util.TreeMap<>();
        for (Row r : ROWS) {
            written.computeIfAbsent(r.kind() + "|" + r.date(), k -> new java.util.TreeSet<>()).add(r.id());
        }
        written.forEach((k, ids) -> {
            String[] kd = k.split("\\|");
            com.ash.drishti.api.RowEstimate e = p.rowEstimate(kd[0], LocalDate.parse(kd[1])).orElseThrow();
            assertThat(e.basis()).isEqualTo("iceberg-manifests");
            assertThat(e.rows()).as(k).isEqualTo(ids.size());
        });
        assertThat(p.rowEstimate("trade", LocalDate.of(1999, 1, 4)).orElseThrow().rows()).isZero();
    }

    @Test
    void manifestAndLastUpdate() throws Exception {
        SourcePlugin p = plugin();
        assertThat(p.manifest().name()).isEqualTo("desk-iceberg");
        assertThat(p.lastUpdate()).isNotNull();
        assertThat(p.fetch(EntityRef.of("trade", "T-1")).orElseThrow().provenance().source()).isEqualTo("desk-iceberg");
    }
}
