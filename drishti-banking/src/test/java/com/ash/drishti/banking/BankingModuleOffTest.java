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
package com.ash.drishti.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.registry.AccessRegistry;
import com.ash.drishti.server.engines.EngineRegistry;
import com.ash.drishti.server.security.TokenVerifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The guarantee, backend part (docs/architecture/ACCESS_MODEL.md section 6): the banking module off is gone everywhere on the server. No bean
 * of its code, no engine (each "not installed", saying why), no scheduled job, no API (new and old paths answer {@code 404 DRS-1025}), no
 * pack of its domain, no connector of its own, no function. The packs named are the banking ones: they are asked for and still not loaded.
 */
@SpringBootTest(properties = {"drishti.modules.banking.enabled=false", "drishti.rachana.hot-reload=false",
        "drishti.packs.enabled=core,market-data,trading,market-risk,counterparty-risk,world-economy", "drishti.sources.plugins.demo.enabled=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class BankingModuleOffTest {

    static final List<String> BANKING_PACKS = List.of("trading", "market-data", "market-risk", "counterparty-risk");

    @Autowired ApplicationContext ctx;
    @Autowired AccessRegistry registry;
    @Autowired EngineRegistry engines;
    @Autowired PackRegistry packs;
    @Autowired SourceRegistry sources;
    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    @Test
    void theRegistrySaysOffAndWhy() {
        assertThat(registry.moduleOn("banking")).isFalse();
        assertThat(registry.module("banking").orElseThrow().reason()).contains("switched off").contains("drishti.modules.banking.enabled");
        assertThat(registry.moduleOn("economy")).isTrue();
        assertThat(registry.lensOffered("risk")).isFalse();
        assertThat(registry.lensOffered("world")).isTrue();
    }

    @Test
    void noBeanOfItsCode() {
        List<String> found = new ArrayList<>();
        for (String name : ctx.getBeanDefinitionNames()) {
            Class<?> t = ctx.getType(name);
            if (t != null && t.getName().startsWith("com.ash.drishti.banking.") && t != BankingTestServer.class) {   // the tests' own start class
                found.add(name + " (" + t.getName() + ")");
            }
        }
        assertThat(found).isEmpty();
    }

    @Test
    void noEngineAndEachSaysWhy() {
        for (String id : List.of("var", "exposure", "sensitivities", "frtb-sa", "simm", "mr-cube")) {
            assertThat(engines.state(id)).as(id).isEqualTo(EngineRegistry.State.NOT_INSTALLED);
            assertThat(engines.reason(id)).as(id).contains("the module 'banking' is off");
            assertThat(engines.engine(id)).as(id).isNull();
        }
        assertThat(engines.running("pivot")).isTrue();
    }

    @Test
    void noScheduledJob() {
        ScheduledAnnotationBeanPostProcessor jobs = ctx.getBeanProvider(ScheduledAnnotationBeanPostProcessor.class).getIfAvailable();
        if (jobs != null) {
            assertThat(jobs.getScheduledTasks()).noneMatch(t -> t.getTask().getRunnable().toString().contains("com.ash.drishti.banking."));
        }
    }

    @Test
    void noApiNewOrOld() throws Exception {
        String auth = "Bearer " + tokens.mint("rita", List.of("admin"), 300);
        for (String path : List.of("/api/v1/risk/var/status", "/api/v1/var/status", "/api/v1/risk/exposure/status", "/api/v1/exposure/status",
                "/api/v1/risk/frtb/sa", "/api/v1/frtb/sa", "/api/v1/risk/simm/versions", "/api/v1/simm/versions", "/api/v1/risk/sensitivities",
                "/api/v1/risk-factors", "/api/v1/pivot/mr/versions", "/api/v1/risk/mr/versions")) {
            MvcResult r = mvc.perform(get(path).header("Authorization", auth)).andReturn();
            assertThat(r.getResponse().getStatus()).as(path).isEqualTo(404);
            assertThat(r.getResponse().getContentAsString()).as(path).contains("DRS-1025").contains("banking");
        }
        MvcResult core = mvc.perform(get("/api/v1/admin/modules").header("Authorization", auth)).andReturn();
        assertThat(core.getResponse().getStatus()).isEqualTo(200);
        assertThat(core.getResponse().getContentAsString()).contains("\"id\":\"banking\"").contains("\"loaded\":false");
    }

    @Test
    void noPackOfItsDomainAndNoConnectorOfItsOwn() {
        List<String> loaded = packs.packs().stream().map(Pack::name).toList();
        assertThat(loaded).contains("core", "world-economy").doesNotContainAnyElementsOf(BANKING_PACKS);
        for (String c : List.of("var-engine", "var-positions", "var-pv-stream", "exposure-engine")) {
            assertThat(sources.connectorStatuses()).as(c).doesNotContainKey(c);
        }
        assertThat(sources.plugins()).as("its connector plugins").noneMatch(p -> p.getClass().getName().startsWith("com.ash.drishti.banking."));
    }

    @Test
    void noFunction() {
        for (String w : List.of("VAR", "EXP", "TRD", "FRSA", "SIMM", "MCUBE", "VRES")) {
            assertThat(registry.functionOn(w)).as(w).isFalse();
        }
        assertThat(registry.functionOn("BI")).isTrue();
        assertThat(registry.functionOn("ECON")).isTrue();
    }
}
