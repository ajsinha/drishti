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

import com.ash.drishti.api.resolution.ConfigResolution;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.registry.AccessRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A module switched at run time (KERNEL_AND_MODULES.md section 18.7, inventory 8.6): one rebuild of the resolution, and the packs, the
 * connector templates and the inbox rules follow. Off: the module's packs unload, its templates and rules leave the resolution; on again: they
 * come back. The switches are written into the test's own folder.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=trading,world-economy", "drishti.modules.dir=${drishti.test.dir}/module-switches-${random.uuid}"})
class ModuleSwitchFollowsTest {

    @Autowired PackRegistry packs;
    @Autowired AccessRegistry access;
    @Autowired ResolutionFollower follower;

    private List<String> names() {
        return packs.packs().stream().map(Pack::name).toList();
    }

    private static void until(String what, BooleanSupplier ok) throws InterruptedException {
        Instant end = Instant.now().plus(Duration.ofSeconds(30));
        while (!ok.getAsBoolean()) {
            if (Instant.now().isAfter(end)) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    private static boolean rulesOf(String module) {
        return ConfigResolution.current().rules().stream().anyMatch(r -> module.equals(r.module()));
    }

    @Test
    void aModuleSwitchedOffIsGoneEverywhereAndSwitchedOnComesBack() throws Exception {
        assertThat(names()).contains("trading", "world-economy");
        assertThat(ConfigResolution.current().templates()).containsKey("trade-endur");
        assertThat(rulesOf("source-endur")).isTrue();

        access.setEnabled("economy", false, "test");
        until("world-economy to unload", () -> !names().contains("world-economy"));
        assertThat(ConfigResolution.current().packs().off()).containsKey("world-economy");
        assertThat(names()).contains("trading");                                              // nothing else moves

        access.setEnabled("source-endur", false, "test");
        until("source-endur's template and rules to leave", () -> !ConfigResolution.current().templates().containsKey("trade-endur")
                && !rulesOf("source-endur"));
        assertThat(ConfigResolution.current().templates()).containsKey("trade-murex");          // the other systems stay

        access.setEnabled("economy", true, "test");
        access.setEnabled("source-endur", true, "test");
        until("world-economy back", () -> names().contains("world-economy"));
        until("source-endur back", () -> ConfigResolution.current().templates().containsKey("trade-endur") && rulesOf("source-endur"));
        assertThat(follower.followed()).isPositive();
    }
}
