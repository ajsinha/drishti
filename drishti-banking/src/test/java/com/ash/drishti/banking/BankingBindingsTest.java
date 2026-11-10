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

import com.ash.drishti.kernel.api.data.BindingState;
import com.ash.drishti.packs.bindings.BindingResolver;
import com.ash.drishti.registry.AccessRegistry;
import com.ash.drishti.server.bindings.BindingService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The shipped test server (the packs' demo samples on): every banking need is served, so the data gate switches
 * none of VaR, positions, exposure or the trades off, and the computed kinds resolve (KERNEL_AND_MODULES.md section 5).
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
class BankingBindingsTest {

    @Autowired AccessRegistry registry;
    @Autowired BindingService bindings;

    @Test
    void theDataGateLeavesVarExposureAndTheTradesOn() {
        bindings.refresh();
        for (String f : List.of("VRES", "POS", "PORT", "EXP", "XPATH", "TRD", "SENS", "VLIM")) {
            assertThat(registry.functionOffReason(f)).as(f).isEmpty();
        }
        assertThat(bindings.health().get("functionsOff")).isEqualTo(Map.of());
        Map<String, BindingResolver.Resolution> kinds = bindings.resolve(false).kinds();
        for (String k : List.of("trade", "scenario-set", "netting-set", "csa", "agreement", "ir-curve", "fx-spot")) {
            assertThat(kinds.get(k)).as(k).isNotNull();
            assertThat(kinds.get(k).state().usable()).as(k + ": " + kinds.get(k).reason()).isTrue();
        }
        assertThat(kinds.get("scenario-pv").state().usable() || kinds.get("pnl-vector").state().usable()).isTrue();
        assertThat(kinds.get("position").state()).isEqualTo(BindingState.COMPUTED);
        assertThat(kinds.get("system-basket").state()).isEqualTo(BindingState.COMPUTED);
    }
}
