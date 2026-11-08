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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.PinnedBusinessDate;
import com.ash.drishti.server.admission.AdmissionGate;
import com.ash.drishti.server.admission.RequestsInFlight;
import com.ash.drishti.server.admission.Signals;
import com.ash.drishti.server.security.TokenVerifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Busy protection in front of a VaR date (docs/guides/OPERATIONS_GUIDE.md, "Busy protection"): with the memory level set under any heap, opening a
 * date not yet open is refused {@code 503 DRS-9502} with {@code Retry-After} and the calm words, and the lake is not read; the crowd's
 * second ask gets the seconds left; Admin, Health's card lists the refusal; an operator who lets everything through (a reason, audited)
 * opens the date, and a date already open keeps answering when automatic again.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.risk-lake.plugin=delta",
        "drishti.sources.connectors.risk-lake.kinds[0]=pnl-vector", "drishti.sources.connectors.risk-lake.kinds[1]=scenario-set",
        "drishti.sources.connectors.risk-lake.kinds[2]=var-limit", "drishti.sources.connectors.risk-lake.kinds[3]=portfolio",
        "drishti.sources.connectors.risk-lake.settings.domain=risk",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.columns=TRADE_ID,LEGAL_ENTITY,DIVISION,DESK,TT_BOOK,PORTFOLIO,"
                + "PRODUCT_GROUP,PRODUCT_TYPE,SECURITY_ID,CURRENCY,COUNTERPARTY,DIRECTION,QUANTITY,PV,TRADE_STATUS,MATURITY_DATE,STATUS,REPORTING_CCY,"
                + "SCENARIO_SET_ID,BATCH_ID,VALUE_TYPE,GRAIN,POSITION_ID,SOURCE_SYSTEM,TRADE_IDS,TRADE_CURRENCY",
        "drishti.sources.connectors.risk-lake.settings.layout.pnl-vector.vectors=PNL",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.var-limit=risk-lake",
        "drishti.var.hierarchies.MGMT.title=Management", "drishti.var.hierarchies.MGMT.levels=DESK > TT_BOOK",
        "drishti.var.hierarchies.MGMT.level-names=desk > book", "drishti.var.hierarchies.MGMT.ids=leaf",
        "drishti.var.hierarchies.MGMT.limits=var-limit", "drishti.var.hierarchies.LEGAL.title=Legal entity",
        "drishti.var.hierarchies.LEGAL.levels=LEGAL_ENTITY", "drishti.var.hierarchies.LEGAL.ids=leaf",
        "drishti.var.hierarchies.LEGAL.limits=var-limit",
        "drishti.sources.routes.portfolio=risk-lake",
        "drishti.var.official.on-start=false", "drishti.var.official.publish=false", "drishti.var.currencies=USD",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.risk.kinds[0]=*", "drishti.security.roles.risk.raw=true",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.admission.memory-warn=0.00001", "drishti.admission.memory-refuse-heavy=0.00002",   // under any heap: new heavy work waits
        "drishti.admission.memory-free-min-mb=1048576",                                                // and any free heap counts as short
        "drishti.admission.retry-after.load=20",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import({PinnedBusinessDate.class, VarBusyProtectionTest.ConfirmedReading.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VarBusyProtectionTest {

    /**
     * A confirmed reading of the heap after the old generation's clean-up (half of it in use), as a server that has run a while has: a
     * fresh test JVM has none and busy protection refuses nothing for memory without one. The levels above make half the heap too much.
     */
    @TestConfiguration
    static class ConfirmedReading {
        @Bean
        @Primary
        Signals confirmedHalfHeap(RequestsInFlight inFlight) {
            Signals live = Signals.live(inFlight.counter(), Path.of("target"));
            return new Signals() {
                public long heapAfterGcBytes() {
                    return live.heapMaxBytes() / 2;
                }

                public long heapMaxBytes() {
                    return live.heapMaxBytes();
                }

                public int requestsInFlight() {
                    return live.requestsInFlight();
                }

                public long freeDiskBytes() {
                    return live.freeDiskBytes();
                }
            };
        }
    }

    static Path lake;

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        lake = Files.createDirectories(Path.of("target", "var-busy-" + System.nanoTime())).toAbsolutePath();
        VarLakeFixture.write(lake);
        r.add("drishti.sources.connectors.risk-lake.settings.root", lake::toString);
        r.add("drishti.var.official.root", lake::toString);
        r.add("drishti.var.tier.dir", () -> lake.resolve("hot").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired VarService svc;
    @Autowired AdmissionGate gate;

    private String rita() {
        return "Bearer " + tokens.mint("rita", List.of("risk"), 300);
    }

    private String admin() {
        return "Bearer " + tokens.mint("ops", List.of("admin"), 300);
    }

    @Test
    @Order(1)
    void aNewDateIsRefusedPolitelyWithRetryAfterAndTheLakeIsNotRead() throws Exception {
        long before = svc.loader().loadsBegun();
        String url = "/api/v1/risk/var/tree?hierarchy=MGMT&asOf=" + VarLakeFixture.DAY;
        mvc.perform(get(url).header("Authorization", rita())).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "20"))
                .andExpect(jsonPath("$.code").value("DRS-9502"))
                .andExpect(jsonPath("$.busy").value(true))
                .andExpect(jsonPath("$.work").value("load"))
                .andExpect(jsonPath("$.title").value(VarLakeFixture.DAY + " cannot be opened just yet"))
                .andExpect(jsonPath("$.because").value(org.hamcrest.Matchers.startsWith("the server is short of memory")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("The dates already open keep working; try again in 20 seconds.")));
        mvc.perform(get(url).header("Authorization", rita())).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("1\\d|20")));
        assertThat(svc.loader().loadsBegun() - before).as("refused before the lake is read").isZero();
        mvc.perform(get("/api/v1/admin/busy").header("Authorization", admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("new heavy work waits"))
                .andExpect(jsonPath("$.refusals.rows[0].who").value("rita"))
                .andExpect(jsonPath("$.refusals.rows[0].code").value("DRS-9502"))
                .andExpect(jsonPath("$.gauges[0].id").value("memory"));
        mvc.perform(get("/api/v1/admin/busy").header("Authorization", rita())).andExpect(status().isForbidden());
    }

    @Test
    @Order(2)
    void anOperatorLetsEverythingThroughWithAReasonAndAnOpenDateKeepsAnswering() throws Exception {
        mvc.perform(put("/api/v1/admin/busy/override").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"open\",\"minutes\":5}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/busy/override").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"open\",\"reason\":\"month-end sign-off\",\"minutes\":5}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.override.mode").value("open")).andExpect(jsonPath("$.override.by").value("ops"));
        String url = "/api/v1/risk/var/tree?hierarchy=MGMT&asOf=" + VarLakeFixture.DAY;
        mvc.perform(get(url).header("Authorization", rita())).andExpect(status().isOk());
        gate.override(AdmissionGate.Mode.AUTOMATIC, null, 0, "ops");
        mvc.perform(get(url).header("Authorization", rita())).andExpect(status().isOk());      // an open date is read, not loaded: it keeps answering
        @SuppressWarnings("unchecked")
        Map<String, Object> o = (Map<String, Object>) gate.describe().get("override");
        assertThat(o.get("mode")).isEqualTo("automatic");
    }
}
