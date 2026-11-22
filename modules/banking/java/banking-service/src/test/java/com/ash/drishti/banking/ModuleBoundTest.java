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

import com.ash.drishti.banking.exposure.ExposureProperties;
import com.ash.drishti.banking.frtb.FrtbProperties;
import com.ash.drishti.banking.simm.SimmProperties;
import com.ash.drishti.banking.var.MrCubeProperties;
import com.ash.drishti.banking.var.VarConfiguration;
import com.ash.drishti.banking.var.VarProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Every setting the banking module owns takes effect from its one name {@code drishti.module.banking.<key>}, and the engine's other settings still bind from its prefix. */
class ModuleBoundTest {

    private static MockEnvironment env(String... kv) {
        MockEnvironment e = new MockEnvironment();
        for (int i = 0; i < kv.length; i += 2) {
            e.setProperty(kv[i], kv[i + 1]);
        }
        return e;
    }

    @Test
    void theVarSettingsTakeEffectFromTheModulesKeys() {
        VarProperties defaults = new VarConfiguration().varProperties(env());
        VarProperties set = new VarConfiguration().varProperties(env(
                "drishti.module.banking.var.window", "300", "drishti.module.banking.var.confidence", "0.95",
                "drishti.module.banking.var.es-confidence", "0.99", "drishti.module.banking.var.ten-day-method", "sum",
                "drishti.module.banking.var.rank-rule", "interpolated", "drishti.module.banking.var.threads", "3",
                "drishti.module.banking.var.hot-dates", "4", "drishti.module.banking.var.root-name", "ALL-BOOKS",
                "drishti.module.banking.var.hierarchy.empty-label", "(None)", "drishti.var.history-days", "11"));
        assertThat(defaults.scenarioWindow()).isNotEqualTo("300");
        assertThat(set.scenarioWindow()).isEqualTo("300");
        assertThat(set.confidence()).isEqualTo(0.95);
        assertThat(set.esConfidence()).isEqualTo(0.99);
        assertThat(set.tenDayMethod()).isEqualTo("sum");
        assertThat(set.rankRule()).isEqualTo("interpolated");
        assertThat(set.threads()).isEqualTo(3);
        assertThat(set.hotDates()).isEqualTo(4);
        assertThat(set.rootName()).isEqualTo("ALL-BOOKS");
        assertThat(set.hierarchy().emptyLabel()).isEqualTo("(None)");
        assertThat(set.historyDays()).as("the engine's own prefix still binds the rest").isEqualTo(11);
        assertThat(defaults.threads()).isEqualTo(Runtime.getRuntime().availableProcessors());
    }

    @Test
    void theOtherEnginesSettingsTakeEffectFromTheModulesKeys() {
        ExposureProperties x = new com.ash.drishti.banking.exposure.ExposureConfiguration().exposureProperties(env(
                "drishti.module.banking.exposure.limit-confidence", "0.99", "drishti.module.banking.exposure.rank-rule", "interpolated"));
        assertThat(x.limitConfidence()).isEqualTo(0.99);
        assertThat(x.rankRule()).isEqualTo("interpolated");
        FrtbProperties f = new com.ash.drishti.banking.frtb.FrtbConfiguration().frtbProperties(env("drishti.module.banking.frtb.parameters-dir", "/srv/frtb",
                "drishti.module.banking.sensitivities.cache-minutes", "25"));
        assertThat(f.cacheMinutes()).isEqualTo(25);
        assertThat(f.parametersDir()).isEqualTo("/srv/frtb");
        SimmProperties s = new com.ash.drishti.banking.simm.SimmConfiguration().simmProperties(env(
                "drishti.module.banking.simm.parameters-dir", "/srv/simm", "drishti.module.banking.simm.default-version", "2.6"));
        assertThat(s.parametersDir()).isEqualTo("/srv/simm");
        assertThat(s.defaultVersion()).isEqualTo("2.6");
        MrCubeProperties m = new com.ash.drishti.banking.var.MrCubeConfiguration().mrCubeProperties(env("drishti.module.banking.mr-cube.sbm-max-cells", "7"));
        assertThat(m.sbmMaxCells()).isEqualTo(7);
    }
}
