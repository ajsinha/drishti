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
package com.ash.drishti.server.pivot.functions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The pivot Python functions start the interpreter {@code drishti.python} names, not {@code python} by name. */
class PivotPythonInterpreterTest {

    private static String r(String own, Map<String, String> props, Map<String, String> env) {
        return PivotFunctionsConfiguration.interpreter(own, props::get, env::get);
    }

    @Test
    void blankSettingFallsToTheSharedDrishtiPython() {
        Map<String, String> props = Map.of("drishti.python", "C:/Program Files/Python313/python.exe");
        assertThat(r("", props, Map.of())).isEqualTo("C:/Program Files/Python313/python.exe");
        assertThat(r(null, props, Map.of())).isEqualTo("C:/Program Files/Python313/python.exe");
        assertThat(r("/own/python", props, Map.of())).isEqualTo("/own/python");
        assertThat(r("", props, Map.of("DRISHTI_PYTHON", "/env/python"))).isEqualTo("/env/python");
        assertThat(r("", Map.of(), Map.of())).isEmpty();
    }
}
