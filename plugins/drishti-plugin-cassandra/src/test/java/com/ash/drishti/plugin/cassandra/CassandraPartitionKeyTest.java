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
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import com.datastax.oss.driver.api.core.CqlIdentifier;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.metadata.schema.ColumnMetadata;
import com.datastax.oss.driver.api.core.metadata.schema.TableMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * No statement reads, deletes or updates without a whole partition key (so nothing scans a table: no {@code ALLOW FILTERING},
 * no {@code token()} range). Checked twice: over every statement the layout can build, against the partition keys it
 * declares (no Cassandra needed); and over every statement the connector and the loader actually prepared while every read
 * path ran, against the partition keys the live tables report.
 */
class CassandraPartitionKeyTest {

    private static final Pattern TABLE = Pattern.compile("(?:FROM|UPDATE|INTO)\\s+\"([^\"]+)\"\\.\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    /** What is wrong with one statement, or null: given the partition key columns of the table it names. */
    static String problem(String cql, Function<String, List<String>> partitionKey) {
        String upper = cql.toUpperCase(Locale.ROOT);
        if (upper.contains("ALLOW FILTERING") || upper.contains("TOKEN(")) {
            return "scans: " + cql;
        }
        if (!(upper.startsWith("SELECT") || upper.startsWith("DELETE") || upper.startsWith("UPDATE"))) {
            return null;                                       // INSERT names every key column (Cassandra refuses otherwise); DDL
        }
        Matcher m = TABLE.matcher(cql);
        if (!m.find()) {
            return "no table named: " + cql;
        }
        int where = upper.indexOf(" WHERE ");
        if (where < 0) {
            return "no WHERE: " + cql;
        }
        String clause = cql.substring(where);
        for (String key : partitionKey.apply(m.group(2))) {
            if (!clause.contains("\"" + key + "\" = ?")) {
                return "partition key column " + key + " not bound by equality: " + cql;
            }
        }
        return null;
    }

    private static List<String> declared(String table) {
        return switch (table) {
            case CassandraLayout.ENTITY_DAYS -> List.of(CassandraLayout.KIND, CassandraLayout.ID);
            case CassandraLayout.KIND_DAYS -> List.of(CassandraLayout.KIND);
            default -> CassandraLayout.PARTITION_KEY;
        };
    }

    @Test
    void everyStatementTheLayoutBuildsNamesAWholePartitionKey() {
        String ks = "trading";
        List<String> all = List.of(CassandraLayout.selectDoc(ks, "trade"), CassandraLayout.selectIds(ks, "trade"),
                CassandraLayout.selectColumns(ks, "trade", List.of("portfolio", "legs__currency")), CassandraLayout.selectDocs(ks, "trade"),
                CassandraLayout.deletePartition(ks, "trade"), CassandraLayout.selectKindDays(ks), CassandraLayout.deleteKindDay(ks),
                CassandraLayout.selectEntityDays(ks), CassandraLayout.selectEntityDaysUpTo(ks),
                CassandraLayout.insertRow(ks, "trade", List.of("portfolio"), true), CassandraLayout.insertEntityDay(ks, false),
                CassandraLayout.insertKindDay(ks, true));
        List<String> problems = new ArrayList<>();
        for (String cql : all) {
            String p = problem(cql, CassandraPartitionKeyTest::declared);
            if (p != null) {
                problems.add(p);
            }
        }
        assertThat(problems).isEmpty();
        // and the checker does catch a scan
        assertThat(problem("SELECT \"doc\" FROM \"trading\".\"trade\" WHERE \"id\" = ? ALLOW FILTERING", CassandraPartitionKeyTest::declared)).isNotNull();
        assertThat(problem("SELECT \"doc\" FROM \"trading\".\"trade\" WHERE \"business_date\" = ? AND \"bucket\" = ?", CassandraPartitionKeyTest::declared))
                .contains("generation");
        assertThat(problem("SELECT \"id\" FROM \"trading\".\"trade\"", CassandraPartitionKeyTest::declared)).startsWith("no WHERE");
    }

    @Test
    void everyStatementPreparedWhileEveryPathRanNamesTheLiveTablesPartitionKey() throws Exception {
        boolean docker;
        try {
            docker = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            docker = false;
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(docker, "Docker is not reachable");
        String node = CassandraNode.cassandra();
        // the loader: create, a reload (old generation dropped), retention
        CassandraNode.load(CassandraNode.args(node, "pk_desk"), CassandraNode.contractLines("desk"));
        CassandraNode.load(CassandraNode.args(node, "pk_desk", "--keep-days", "2", "--force-drop"), CassandraNode.contractLines("desk"));
        // the connector: every read path
        try (CassandraSourcePlugin p = new CassandraSourcePlugin()) {
            p.start(DatedSourceContract.context(CassandraNode.settings(node, "pk_desk", "pk-cassandra")));
            p.fetch(EntityRef.of("trade", "T-1"), AsOf.of(DatedSourceContract.D3));
            p.fetch(EntityRef.of("counterparty", "CP-X"), AsOf.of(DatedSourceContract.D3));
            p.fetch(EntityRef.of("counterparty", "CP-X"));
            p.columns("trade", List.of("mtm", "nettingSet"), AsOf.LATEST);
            p.reverse(EntityRef.of("netting-set", "NS-A"), null, AsOf.LATEST);
            p.documents("trade", AsOf.LATEST, d -> { });
            p.inventory("trade", 5);
            p.search("trade", "t", 5);
            p.purgeCaches();
        }
        List<String> problems = new ArrayList<>();
        try (CqlSession s = CassandraNode.session(node)) {
            Function<String, List<String>> live = table -> s.getMetadata().getKeyspace("pk_desk").flatMap(k -> k.getTable(CqlIdentifier.fromInternal(table)))
                    .map(TableMetadata::getPartitionKey).orElseThrow().stream().map(ColumnMetadata::getName).map(CqlIdentifier::asInternal).toList();
            for (String cql : CassandraStore.PREPARED) {
                Matcher m = TABLE.matcher(cql);
                if (!m.find() || !m.group(1).equals("pk_desk")) {
                    continue;                                  // another test's keyspace: checked by the same rule there
                }
                String p = problem(cql, live);
                if (p != null) {
                    problems.add(p);
                }
            }
        }
        assertThat(CassandraStore.PREPARED).anyMatch(c -> c.startsWith("SELECT \"doc\"")).anyMatch(c -> c.startsWith("DELETE"));
        assertThat(problems).isEmpty();
    }
}
