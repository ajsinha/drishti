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

import com.ash.drishti.server.PinnedBusinessDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The flat-memory and flat-disk soak (RESOURCES_AND_SCALE.md section 10.4, O8, 2.9.7), in its short form for CI: many business dates and
 * many versions of each opened, restated and reloaded in turn; the views, open dates, hot files, mapped id maps and deleted-but-mapped
 * files stay within their bounds at every step, and the heap after a collection does not grow with the dates. Also the version watch (O9)
 * on a real lake: a date restated with no data-load signal is reloaded within the interval, the request meanwhile warned.
 *
 * <p>The long form: {@code -Ddrishti.soak.versions=60} (five dates, the fixture's, each restated 60 times); the short form (the default) is
 * 3 versions. The 24-hour soak at 200K positions of section 10.4 is run by hand against a server (PERFORMANCE.md, soak).
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.refresh-seconds=1",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.var-result=var-engine",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.official.on-start=false", "drishti.var.official.enabled=false", "drishti.var.currencies[0]=USD",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0", "drishti.var.hot-dates=2", "drishti.var.tier.disk-dates=6",
        "drishti.var.version-check=1s",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@Import(PinnedBusinessDate.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VarResourcesSoakTest {

    /** The fixture's scenario days end on 2026-09-25: its business dates 2026-09-26 to 30 are the ones it can load. */
    static final int DATES = Math.min(5, Integer.getInteger("drishti.soak.dates", 5));
    static final int VERSIONS = Integer.getInteger("drishti.soak.versions", 3);
    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-soak-" + System.nanoTime())).toAbsolutePath();
        for (int i = DATES - 1; i >= 0; i--) {
            VarLakeFixture.write(lake, VarLakeFixture.DAY.minusDays(i), 1.0, false, false);
        }
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
        r.add("drishti.var.tier.dir", () -> lake.resolve("hot").toString());
        r.add("drishti.sources.connectors.risk-lake.settings.id-map-disk.dir", () -> lake.resolve("idmaps").toString());
    }

    @Autowired VarService svc;

    private double bank(LocalDate d) {
        return svc.scoped(null, "BANK", d).measures().var();
    }

    /** O9: restated with no signal, the open date is reloaded within the interval; meanwhile it answers with a warning. */
    @Test
    @Order(1)
    void aDateRestatedWithoutASignalIsReloadedWithinTheInterval() throws Exception {
        LocalDate d = VarLakeFixture.DAY;
        double before = bank(d);
        String v1 = svc.date(d).version();
        VarLakeFixture.write(lake, d, 2.0, false, false);              // every vector doubled; nobody announces it
        String lakeV = v1;
        for (int i = 0; i < 50 && lakeV.equals(v1); i++) {                  // the connector sees the new table version (refresh-seconds 1)
            Thread.sleep(200);
            lakeV = svc.loader().version(d);
        }
        assertThat(lakeV).as("the lake's version of the date moved").isNotEqualTo(v1);
        boolean warned = false;
        String v2 = v1;
        long until = System.currentTimeMillis() + 30_000;
        while (v2.equals(v1) && System.currentTimeMillis() < until) {
            VarDate now = svc.date(d);
            warned |= now.warnings().stream().anyMatch(w -> w.contains("changed") && w.contains("a new version is loading"));
            v2 = now.version();
            Thread.sleep(200);
        }
        assertThat(v2).as("reloaded with the lake's new version, no signal").isNotEqualTo(v1);
        assertThat(warned).as("a request during the reload was told").isTrue();
        assertThat(bank(d)).isEqualTo(2 * before, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(svc.watch().reloads()).isGreaterThanOrEqualTo(1);
    }

    /** O8 (short form): many dates and versions; every holder stays bounded, the heap after a collection does not grow with the dates. */
    @Test
    @Order(2)
    void manyDatesAndVersionsKeepMemoryAndDiskFlat() throws Exception {
        List<Long> heaps = new ArrayList<>();
        int maxFiles = 0;
        for (int round = 0; round < VERSIONS; round++) {
            for (int i = DATES - 1; i >= 0; i--) {
                LocalDate d = VarLakeFixture.DAY.minusDays(i);
                if (round > 0) {
                    VarLakeFixture.write(lake, d, 1.0 + round * 0.1, false, false);   // a restatement: a new version of the date
                    svc.reload(d);                                                  // as its data-load signal does
                }
                bank(d);
                svc.scoped(null, "DESK:DESK-X", d);
                assertThat(svc.loaded().stream().filter(m -> m.get("version") != null).count()).as("open dates: hot-dates and the one asked").isLessThanOrEqualTo(3);
                assertThat(svc.access().cache().asMap().keySet().stream().map(k -> k.substring(0, 10)).distinct().count())
                        .as("views of open dates only").isLessThanOrEqualTo(3);
                maxFiles = Math.max(maxFiles, ((Number) svc.loader().tier().describe().get("files")).intValue());
                assertThat(svc.answers().asMap().keySet().stream().map(k -> k.substring(0, 10)).distinct().count())
                        .as("answers of open dates only").isLessThanOrEqualTo(3);
            }
            heaps.add(heapAfterGc());
        }
        assertThat(maxFiles).as("hot files within disk-dates").isLessThanOrEqualTo(6);
        assertThat(com.ash.drishti.api.MappedIdFile.mapped(lake.resolve("idmaps"))[0]).as("mapped id maps").isLessThanOrEqualTo(4L * DATES);
        Path maps = Path.of("/proc/self/maps");
        if (Files.isReadable(maps)) {
            String self = lake.toRealPath().toString();
            assertThat(Files.readAllLines(maps).stream().filter(l -> l.contains(self) && l.contains("(deleted)")).count())
                    .as("no deleted file of the lake or hot folder stays mapped").isZero();
        }
        long first = heaps.get(0);
        long last = heaps.get(heaps.size() - 1);
        assertThat(last).as("heap after GC, first " + first + ", by round " + heaps).isLessThan(first + (64L << 20));
    }

    private static long heapAfterGc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }
}
