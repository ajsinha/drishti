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
package com.ash.drishti.banking.var;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The MR cube on a sharded date (docs/architecture/LENSES.md section 4.11): the date of {@link VarHierarchyFixture} cut into one shard per legal entity
 * and desk ({@link VarShardSingleNodeTest}'s settings), a pivot view's cells summed in one batch through the shards ({@code VarBook.vectors},
 * one call per remote shard); every cell and total equals the engine's VaR of the cell's scope on the same sharded date to the cent, the
 * components add up to the total, and the same cells on the date read whole (not sharded) give the same figures. The remote side (one call
 * per owner for all the sets, each set's vector the single set's to the bit) is {@code ShardReadAheadTest} in drishti-compute.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors-dir=target/var-mr-cube-shard/connectors",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.var.hierarchies.MGMT.title=Management", "drishti.var.hierarchies.MGMT.levels=DESK > TT_BOOK",
        "drishti.var.hierarchies.MGMT.level-names=desk > book", "drishti.var.hierarchies.MGMT.ids=leaf",
        "drishti.var.hierarchies.MGMT.limits=var-limit", "drishti.var.hierarchies.LEGAL.title=Legal entity",
        "drishti.var.hierarchies.LEGAL.levels=LEGAL_ENTITY", "drishti.var.hierarchies.LEGAL.ids=leaf",
        "drishti.var.hierarchies.LEGAL.limits=var-limit",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.var-limit=risk-lake", "drishti.sources.routes.scenario-pv=risk-lake",
        "drishti.sources.routes.fx-scenario=risk-lake", "drishti.sources.routes.fx-spot=risk-lake",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.hierarchies.LL.title=Limit letters", "drishti.var.hierarchies.LL.levels=LIMIT_LETTER > DESK",
        "drishti.var.hierarchies.LL.word=LL", "drishti.var.hierarchies.LL.limits=var-limit",
        "drishti.var.official.on-start=false", "drishti.var.official.root=target/var-mr-cube-shard/official",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0", "drishti.var.currencies[0]=USD",
        "drishti.var.sharding.enabled=true", "drishti.var.sharding.target-shard-mb=0.0003", "drishti.var.sharding.stable-seconds=0",
        "drishti.var.sharding.release-after-seconds=0", "drishti.engines.mr-cube.enabled=true",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@Import(PinnedBusinessDate.class)
class VarMrCubeShardTest {

    static final Path BASE = Path.of("target", "var-mr-cube-shard").toAbsolutePath();
    static final Path LAKE = BASE.resolve("lake");
    static final LocalDate DAY = VarHierarchyFixture.DAY;
    static final Principal ME = new Principal("rita", List.of("risk"));

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
    @Autowired PivotCore core;

    @SuppressWarnings("unchecked")
    @Test
    void aViewOnAShardedDateIsTheEnginesVarOfEveryCellsScopeAndTheWholeDatesToo() {
        VarBook book = svc.date(DAY).book();
        assertThat(book.sharded()).as("the date put together from its shards").isTrue();
        PivotView view = PivotView.parse(Map.of("source", "var", "date", DAY.toString(), "rows", List.of("MGMT.BOOK"), "columns",
                List.of("LEGAL_ENTITY"), "values", List.of(Map.of("measure", "var()", "as", "VaR"), Map.of("measure", "comp_var()", "as", "Comp"))));
        Map<String, Object> a = core.query(new Viewer("rita", ME), view);
        Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) a.get("cells");
        assertThat(cells.size()).isGreaterThanOrEqualTo(5);
        int checked = 0;
        for (Map<String, Object> e : cells.values()) {
            Object scope = e.get("scope");
            Object v = ((Map<String, Object>) e.get("values")).get("VaR");
            if (scope == null || v == null) {
                continue;
            }
            assertThat(((Number) v).doubleValue()).as(String.valueOf(scope))
                    .isCloseTo(svc.scoped(ME, String.valueOf(scope), DAY).measures().var(), Offset.offset(0.005));
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(5);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) a.get("rows");
        List<Map<String, Object>> cols = (List<Map<String, Object>>) a.get("columns");
        String grandCol = String.valueOf(cols.get(cols.size() - 1).get("key"));
        double comp = 0;
        Double total = null;
        for (Map<String, Object> r : rows) {
            Map<String, Object> c = cells.get(r.get("key") + "|" + grandCol);
            Map<String, Object> vals = (Map<String, Object>) c.get("values");
            if ("grand".equals(r.get("kind"))) {
                total = ((Number) vals.get("VaR")).doubleValue();
            } else {
                comp += ((Number) vals.get("Comp")).doubleValue();
            }
        }
        assertThat(comp).as("the components add up to the total").isCloseTo(total, Offset.offset(0.02));
        // the same cells on the whole date (read without its shards): the same figures
        VarBook whole = svc.loader().load(DAY).book();
        try {
            for (Map<String, Object> e : cells.values()) {
                Object scope = e.get("scope");
                Object v = ((Map<String, Object>) e.get("values")).get("VaR");
                if (scope == null || v == null) {
                    continue;
                }
                double[] vec = whole.vector(svc.scoped(ME, String.valueOf(scope), DAY).set(), svc.sums());
                assertThat(((Number) v).doubleValue()).as(scope + " on the whole date").isCloseTo(svc.calc().var(vec), Offset.offset(0.005));
            }
        } finally {
            svc.loader().tier().retire(whole, false);
        }
    }
}
