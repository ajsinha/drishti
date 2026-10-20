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
package com.ash.drishti.plugin.cassandra;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Cassandra connector against Apache Cassandra 5 in Docker, loaded with the contract's rows through the loader: the
 * same tests as the Delta Lake, PostgreSQL and Aerospike connectors, and the read paths only a store with promoted columns
 * and a catalogue has. Skipped where Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class CassandraSourcePluginTest extends DatedSourceContract {

    private static CassandraSourcePlugin plugin;

    /** The node under test; ScyllaDB's test overrides it. */
    protected String node() {
        return CassandraNode.cassandra();
    }

    protected String keyspace() {
        return "desk";
    }

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        return started();
    }

    private synchronized CassandraSourcePlugin started() throws Exception {
        if (plugin != null && plugin.manifest().name().equals(keyspace() + "-cassandra")) {
            return plugin;
        }
        CassandraNode.load(CassandraNode.args(node(), keyspace()), CassandraNode.contractLines("desk"));
        CassandraSourcePlugin p = new CassandraSourcePlugin();
        p.start(context(CassandraNode.settings(node(), keyspace(), keyspace() + "-cassandra")));
        plugin = p;
        return p;
    }

    @Test
    void aDaysPromotedColumnsAnswerSearchesAndReverseLookupsWithoutDocuments() throws Exception {
        CassandraSourcePlugin p = started();
        assertThat(p.columnar("trade")).containsExactlyInAnyOrder("mtm", "nettingSet");
        assertThat(p.columnar("counterparty")).isEmpty();                      // effective: its days hold changes only
        ColumnSet c = p.columns("trade", List.of("mtm", "nettingSet"), AsOf.LATEST).orElseThrow();
        assertThat(c.ids()).containsExactly("T-1", "T-2");
        assertThat(c.numbers()).containsKey("mtm");
        for (int i = 0; i < c.size(); i++) {
            var doc = p.fetch(EntityRef.of("trade", c.ids()[i])).orElseThrow().data();
            assertThat((Double) c.value("mtm", i)).isEqualTo(doc.get("mtm").asDouble());
            assertThat(c.value("nettingSet", i)).isEqualTo(doc.get("nettingSet").asText());
        }
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(D1)).orElseThrow().ids()).containsExactly("T-1", "T-2", "T-3");
        assertThat(p.columns("trade", List.of("notional"), AsOf.LATEST)).isEmpty();
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(D1.minusDays(30)))).isEmpty();      // not held: the next connector is asked
    }

    @Test
    void reverseLookupsOfAKindWithoutColumnsReadTheDaysDocuments() throws Exception {
        CassandraSourcePlugin p = started();
        // counterparties have no promoted columns: their documents are read (each entity's latest on or before the date)
        assertThat(p.reverse(EntityRef.of("rating", "CP-X"), "counterparty", AsOf.of(D3))).containsExactly(EntityRef.of("counterparty", "CP-X"));
        assertThat(p.reverse(EntityRef.of("rating", "CP-Y"), "counterparty", AsOf.of(D3))).isEmpty();
    }

    @Test
    void aDaysDocumentsInOnePass() throws Exception {
        CassandraSourcePlugin p = started();
        List<EntityDocument> docs = new ArrayList<>();
        assertThat(p.documents("trade", AsOf.of(D1), docs::add)).isTrue();
        assertThat(docs).extracting(d -> d.ref().id()).containsExactlyInAnyOrder("T-1", "T-2", "T-3");
        assertThat(docs).allSatisfy(d -> assertThat(d.provenance().businessDate()).isEqualTo(D1));
        assertThat(p.documents("counterparty", AsOf.of(D1), docs::add)).isFalse();          // effective: entity by entity
        List<EntityDocument> none = new ArrayList<>();
        assertThat(p.documents("trade", AsOf.of(D1.minusDays(30)), none::add)).isTrue();
        assertThat(none).isEmpty();
    }

    @Test
    void theCatalogueAnswersInventoryLatestDateAndVersions() throws Exception {
        CassandraSourcePlugin p = started();
        assertThat(p.inventory("trade", 10)).containsExactly(java.util.Map.entry(D3, 2L), java.util.Map.entry(D2, 2L), java.util.Map.entry(D1, 3L));
        assertThat(p.inventory("trade", 1)).containsOnlyKeys(D3);
        assertThat(p.latestDate("trade")).contains(D3);
        assertThat(p.latestDate("counterparty")).contains(D3);
        assertThat(p.latestDate("nothing")).isEmpty();
        assertThat(p.dataVersion("trade")).isPresent();
        assertThat(p.dataVersion("trade", D2)).isPresent().isNotEqualTo(p.dataVersion("trade", D3));
        assertThat(p.dataVersion("trade", D3.plusDays(2))).isEqualTo(p.dataVersion("trade", D3));
        assertThat(p.tableIdentity("trade")).hasValueSatisfying(v -> assertThat(v).startsWith("cassandra:").endsWith("/" + keyspace() + ".trade"));
        assertThat(p.holdsNone("trade")).isFalse();
        assertThat(p.cacheStats()).containsEntry("kinds", 2).containsKey("columnSets");
    }

    @Test
    void thePoolIsTheDriversConnectionsPerNodeAndIdleBetweenReads() throws Exception {
        CassandraSourcePlugin p = started();
        p.fetch(EntityRef.of("trade", "T-1"));
        var stats = p.poolStats().orElseThrow();
        assertThat(stats.type()).isEqualTo("cassandra");
        assertThat(stats.max()).isEqualTo(2);                                // connections-per-node (2), one node here
        assertThat(stats.open()).isPositive();
        assertThat(stats.inUse()).isZero();
        assertThat(stats.waiting()).isZero();
        assertThat(p.health()).isEqualTo("UP");
    }
}
