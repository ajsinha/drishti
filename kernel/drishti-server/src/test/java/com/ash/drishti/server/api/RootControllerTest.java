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
package com.ash.drishti.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.Branding;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

/** The server's root address says what it is, with the build's version, and names the next addresses. */
class RootControllerTest {
    @SuppressWarnings("unchecked")
    private static ObjectProvider<BuildProperties> provider(BuildProperties b) {
        ObjectProvider<BuildProperties> p = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(p.getIfAvailable()).thenReturn(b);
        return p;
    }

    @Test
    void theRootNamesTheProductTheVersionAndWhereTheUiIs() {
        Branding was = Branding.current();
        Branding.install(new Branding("Acme Risk", null, null, null, null));
        try {
            java.util.Properties p = new java.util.Properties();
            p.setProperty("version", "9.9.9");
            Map<String, Object> m = new RootController(provider(new BuildProperties(p))).root();
            assertThat(m).containsEntry("product", "Acme Risk").containsEntry("version", "9.9.9").containsEntry("kind", "server");
            assertThat((String) m.get("message")).contains("Acme Risk server").contains("console");
            assertThat(m).containsEntry("api", "/api/v1/about").containsEntry("health", "/actuator/health");
        } finally {
            Branding.install(was);
        }
    }

    @Test
    void withoutABuildTheVersionIsDev() {
        assertThat(new RootController(provider(null)).root()).containsEntry("version", "dev");
    }
}
