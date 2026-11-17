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
package com.ash.drishti.server.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.packs.module.ModuleSettingsEnvironmentPostProcessor;
import com.ash.drishti.server.security.Entitlements;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Admin -> Modules -> banking -> Settings: what the manifest describes, each value and where it comes from. */
class ModuleSettingsControllerTest {

    private static final Principal ADMIN = new Principal("alice", List.of("admin"));

    @Test
    @SuppressWarnings("unchecked")
    void listsTheSettingsWithTheirSource() {
        MockEnvironment env = new MockEnvironment().withProperty("drishti.module-manifests-dir", "../../modules")
                .withProperty("drishti.module.banking.var.window", "500").withProperty("drishti.var.es-confidence", "0.9")
                .withProperty(ModuleSettingsEnvironmentPostProcessor.INFO + ".deprecated", "drishti.var.hot-dates is read for one release: x");
        ModuleSettingsController c = new ModuleSettingsController(env, mock(Entitlements.class));
        Map<String, Object> r = c.settings("banking", ADMIN);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("settings");
        assertThat(rows).anySatisfy(m -> assertThat(m).containsEntry("key", "drishti.module.banking.var.window").containsEntry("source", "module").containsEntry("value", "500"));
        assertThat(rows).anySatisfy(m -> assertThat(m).containsEntry("key", "drishti.module.banking.var.es-confidence").containsEntry("source", "old-key"));
        assertThat((List<String>) r.get("deprecated")).hasSize(1);
        assertThatThrownBy(() -> c.settings("no-such-module", ADMIN)).hasMessageContaining("declares no settings");
    }
}
