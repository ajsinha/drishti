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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.caches.RefusedForNow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * A date refused for room is remembered (VAR_GUIDE.md section 26.8): here the disk's guard is set past any disk, so every build is refused
 * ({@code DRS-5218}). Fifty requests at once make one load; asked again within {@code refusal-ttl-seconds} the date is refused at once with
 * {@code Retry-After} and the lake is not read; after the time, or once room is freed, it is read again.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=risk-hierarchy", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.var-result=var-engine",
        "drishti.var.official.on-start=false", "drishti.var.official.enabled=false", "drishti.var.currencies[0]=USD",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0", "drishti.var.refusal-ttl-seconds=3",
        "drishti.disk.min-free-gb=100000000",                          // a guard past any disk: every build is refused for room
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@Import(PinnedBusinessDate.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VarRefusalRememberedTest {

    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-refusal-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake, VarLakeFixture.DAY, 1.0, false, false);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
        r.add("drishti.var.tier.dir", () -> lake.resolve("hot").toString());
        r.add("drishti.sources.connectors.risk-lake.settings.id-map-disk.dir", () -> lake.resolve("idmaps").toString());
    }

    @Autowired VarService svc;

    private DrishtiException ask(LocalDate d) {
        try {
            svc.date(d);
            return null;
        } catch (DrishtiException e) {
            return e;
        }
    }

    @Test
    @Order(1)
    void fiftyRequestsMakeOneLoadAndTheRestAreRefusedAtOnce() throws Exception {
        LocalDate d = VarLakeFixture.DAY;
        long before = svc.loader().loadsBegun();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Callable<DrishtiException>> asks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            asks.add(() -> ask(d));
        }
        List<DrishtiException> got = new ArrayList<>();
        for (Future<DrishtiException> f : pool.invokeAll(asks)) {
            got.add(f.get());
        }
        pool.shutdown();
        assertThat(got).allSatisfy(e -> assertThat(e).isNotNull());
        assertThat(got).allSatisfy(e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DISK_GUARD));
        assertThat(svc.loader().loadsBegun() - before).as("one load for fifty requests").isEqualTo(1);
        DrishtiException again = ask(d);
        assertThat(again).isInstanceOf(RefusedForNow.class);
        assertThat(((RefusedForNow) again).retryAfterSeconds()).isBetween(1L, 3L);
        assertThat(again.getMessage()).contains("remembered");
        assertThat(svc.loader().loadsBegun() - before).as("remembered: the lake is not read again").isEqualTo(1);
        assertThat(svc.room().describe()).singleElement().satisfies(m -> assertThat(m.get("code")).isEqualTo("DRS-5218"));
    }

    @Test
    @Order(2)
    void afterTheTimeOrWhenRoomIsFreedTheDateIsReadAgain() throws Exception {
        LocalDate d = VarLakeFixture.DAY;
        ask(d);
        long before = svc.loader().loadsBegun();
        Thread.sleep(3_200);                                              // refusal-ttl-seconds: 3
        assertThat(ask(d)).isNotInstanceOf(RefusedForNow.class);
        assertThat(svc.loader().loadsBegun() - before).as("read again after the time").isEqualTo(1);
        assertThat(ask(d)).isInstanceOf(RefusedForNow.class);
        svc.reload();                                                     // room freed (an administrator's reload, an eviction, a purge)
        assertThat(ask(d)).isNotInstanceOf(RefusedForNow.class);
        assertThat(svc.loader().loadsBegun() - before).as("read again once room is freed").isEqualTo(2);
    }
}
