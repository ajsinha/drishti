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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.engine.source.SourceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The `postgres` profile turns each banking data domain's store into a JDBC table connector. With no database at
 * the configured URL the connectors still start (they reconnect once it is up), report themselves down, and the
 * server runs.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=market-risk,counterparty-risk",
        "DRISHTI_PG_URL=jdbc:postgresql://127.0.0.1:1/nowhere"})
@ActiveProfiles("postgres")
class StoreProfilesTest {

    @Autowired SourceRegistry registry;

    @Test
    void eachDomainStoreBecomesATableConnector() {
        for (String store : new String[] {"reference-store", "market-store", "trading-store", "risk-store", "credit-store", "collateral-store"}) {
            assertThat(registry.failures()).doesNotContainKey(store);
            assertThat(registry.plugin(store)).as(store).isPresent();
            // table kinds are listed in the background once the database answers; reads meanwhile fail and health says so
            assertThat(registry.plugin(store).orElseThrow().health()).as(store).startsWith("DOWN");
        }
        assertThat(registry.plugin("demo")).isPresent();
    }
}
