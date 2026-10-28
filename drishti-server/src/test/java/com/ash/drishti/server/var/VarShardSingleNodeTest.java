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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.compute.var.ShardMap;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.vector.OrdinalSet;
import com.ash.drishti.compute.vector.ShardedVectors;
import com.ash.drishti.server.PinnedBusinessDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * VaR sharding on one server (Drishti*net off, VAR_SHARDING.md section 12): the server is the only member, so it owns every shard; the date
 * is cut into one shard per legal entity and desk, each built from its own rows into its own hot file (with its {@code RANG} section), and
 * the date put together from them has the trades, positions and nodes of the whole date read by the same server, every node's aggregate to
 * the bit, every set's vector equal, and every row read from the shards' files. With no {@code sharding:} block nothing changes: the other VaR
 * tests run with sharding off.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors-dir=target/var-shard-single/connectors",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.var-limit=risk-lake", "drishti.sources.routes.scenario-pv=risk-lake",
        "drishti.sources.routes.fx-scenario=risk-lake", "drishti.sources.routes.fx-spot=risk-lake",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.hierarchies.LL.title=Limit letters", "drishti.var.hierarchies.LL.levels=LIMIT_LETTER > DESK",
        "drishti.var.hierarchies.LL.word=LL", "drishti.var.hierarchies.LL.limits=var-limit",
        "drishti.var.official.on-start=false", "drishti.var.official.root=target/var-shard-single/official",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0", "drishti.var.currencies[0]=USD",
        "drishti.var.sharding.enabled=true", "drishti.var.sharding.target-shard-mb=0.0003", "drishti.var.sharding.stable-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@Import(PinnedBusinessDate.class)
class VarShardSingleNodeTest {

    static final Path BASE = Path.of("target", "var-shard-single").toAbsolutePath();
    static final Path LAKE = BASE.resolve("lake");
    static final LocalDate DAY = VarHierarchyFixture.DAY;

    static {
        try {
            if (Files.exists(BASE)) {
                try (Stream<Path> s = Files.walk(BASE)) {
                    for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
            VarHierarchyFixture.write(LAKE);
            VarPvFixture.write(LAKE, DAY);
            Path conn = Files.createDirectories(BASE.resolve("connectors"));
            Files.writeString(conn.resolve("risk-lake.yaml"), VarShardClusterTest.riskLake("delta", LAKE, BASE));
        } catch (Exception e) {
            throw new IllegalStateException("the lake cannot be written: " + e, e);
        }
    }

    @Autowired VarService svc;

    @Test
    void theDatePutTogetherFromItsShardsIsTheWholeDate() {
        VarShards shards = svc.shards();
        assertThat(shards.on()).isTrue();
        ShardMap map = shards.map(DAY);
        assertThat(map.size()).as("LE-A/DESK-X and LE-B/DESK-Y").isEqualTo(2);
        assertThat(shards.owners(map.shard(0))).containsExactly("local");
        VarDate sharded = svc.date(DAY);
        VarBook a = sharded.book();
        assertThat(a.sharded()).isTrue();
        assertThat(a.tier()).isEqualTo("sharded");
        assertThat(((ShardedVectors) a.store()).parts()).allMatch(ShardedVectors.Part::held);
        VarBook w = svc.loader().load(DAY).book();
        try {
            assertThat(a.tradeCount()).isEqualTo(w.tradeCount());
            for (int o = 0; o < w.tradeCount(); o++) {
                assertThat(a.tradeId(o)).isEqualTo(w.tradeId(o));
                assertThat(a.positionOf(o)).isEqualTo(w.positionOf(o));
            }
            assertThat(a.nodes().keySet()).containsExactlyElementsOf(w.nodes().keySet());
            for (VarBook.Node n : w.nodes().values()) {
                assertThat(a.node(n.id()).members()).isEqualTo(n.members());
                assertThat(a.aggregate(n.id())).as(n.id()).containsExactly(w.aggregate(n.id()));
            }
            SplittableRandom r = new SplittableRandom(3);
            for (int k = 0; k < 40; k++) {
                OrdinalSet.Builder b = new OrdinalSet.Builder();
                for (int i = 0; i < 1 + r.nextInt(w.tradeCount()); i++) {
                    b.add(r.nextInt(w.tradeCount()));
                }
                OrdinalSet s = b.build();
                double[] x = a.vector(s, svc.sums());
                double[] y = w.vector(s, svc.sums());
                for (int j = 0; j < y.length; j++) {
                    assertThat(Math.abs(x[j] - y[j])).as("scenario " + j).isLessThan(0.005);
                }
            }
            assertThat(a.splitPositions()).isEqualTo(w.splitPositions());
            assertThat(sharded.intake().rejected()).isEqualTo(svc.loader().load(DAY).intake().rejected());
        } finally {
            svc.loader().tier().retire(w, false);
        }
        List<java.util.Map<String, Object>> held = svc.shards().books().describe();
        assertThat(held).hasSize(2).allMatch(m -> "ready".equals(m.get("state")) && String.valueOf(m.get("hotFile")).contains("-shard-"));
        assertThat(shards.describe(DAY).get("map")).isNotNull();
    }
}
