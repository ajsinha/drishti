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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.server.security.SecurityConfiguration;
import com.ash.drishti.server.security.SecurityNotConfiguredException;
import com.ash.drishti.server.security.SecurityProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Secure defaults (CONFIGURATION.md, Security): security on and nothing seeded unless the dev profile or the environment says otherwise;
 * security on without a token secret refuses to start, in words.
 */
class SecureDefaultsTest {

    private static SecurityProperties props(boolean enabled, String secret) {
        return new SecurityProperties(enabled, secret, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = SecureDefaultsTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void securityOnWithoutASecretRefusesToStartAndSaysWhatToSet() {
        assertThatThrownBy(() -> new SecurityConfiguration().tokenVerifier(props(true, null)))
                .isInstanceOf(SecurityNotConfiguredException.class)
                .hasMessageContaining("DRISHTI_TOKEN_SECRET").hasMessageContaining("--spring.profiles.active=dev");
        assertThatThrownBy(() -> new SecurityConfiguration().tokenVerifier(props(true, "  "))).isInstanceOf(SecurityNotConfiguredException.class);
        assertThat(new SecurityConfiguration().tokenVerifier(props(true, "0123456789abcdef0123456789abcdef"))).isNotNull();
        assertThat(new SecurityConfiguration().tokenVerifier(props(false, null))).isNotNull();          // the dev profile: sign-in off
        var analysis = new SecurityNotConfiguredException.Analyzer().analyze(new IllegalStateException(new SecurityNotConfiguredException()));
        assertThat(analysis.getAction()).contains("DRISHTI_TOKEN_SECRET").contains("dev profile");
    }

    @Test
    void theShippedDefaultsAreSecureAndTheDevProfileIsNot() throws IOException {
        String main = resource("application.yaml");
        assertThat(main).contains("enabled: ${DRISHTI_SECURITY_ENABLED:true}").contains("seed-admin: ${DRISHTI_SEED_ADMIN:false}");
        String dev = resource("application-dev.yaml");
        assertThat(dev).contains("enabled: ${DRISHTI_SECURITY_ENABLED:false}").contains("seed-admin: ${DRISHTI_SEED_ADMIN:true}");
        assertThat(resource("META-INF/spring.factories")).contains("SecurityNotConfiguredException$Analyzer");
    }
}
