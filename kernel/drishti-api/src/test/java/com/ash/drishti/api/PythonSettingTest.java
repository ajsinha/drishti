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
package com.ash.drishti.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class PythonSettingTest {

    private static String r(String own, Map<String, String> props, Map<String, String> env) {
        return PythonSetting.resolve(own, props::get, env::get);
    }

    @Test
    void theEnvironmentVariableBeatsTheOwnSettingWhichBeatsTheSharedOne() {
        Map<String, String> props = Map.of("drishti.python", "/shared/python");
        assertThat(r("/own/python", props, Map.of("DRISHTI_PYTHON", "/env/python"))).isEqualTo("/env/python");
        assertThat(r("/own/python", props, Map.of())).isEqualTo("/own/python");
        assertThat(r("  ", props, Map.of())).isEqualTo("/shared/python");
        assertThat(r(null, props, Map.of("DRISHTI_PYTHON", " "))).isEqualTo("/shared/python");
    }

    @Test
    void nothingConfiguredIsEmptyForTheCallersOwnLastResort() {
        assertThat(r("", Map.of(), Map.of())).isEmpty();
        assertThat(PythonSetting.resolve(null, null, null)).isEmpty();
    }
}
