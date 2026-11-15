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
package com.ash.drishti.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.tls.Secrets;
import com.ash.drishti.api.tls.TlsSettings;
import com.ash.drishti.settingsfile.SettingsFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A TLS secret written as a placeholder resolves a key of the settings files (the secrets file given second wins), and is masked. */
class TlsSettingsFileTest {

    @TempDir
    Path dir;
    private final List<String> set = new ArrayList<>();

    @AfterEach
    void clear() {
        set.forEach(System::clearProperty);
        System.clearProperty(SettingsFile.PROPERTY);
    }

    @Test
    void aTlsPasswordPlaceholderResolvesTheSecretsFilesKey() throws Exception {
        Path base = dir.resolve("prod.ini");
        Files.writeString(base, "[site.tls]\ntruststore=/etc/drishti/tls/truststore.p12\ntruststore-password=from-base\n");
        Path secrets = dir.resolve("secrets.ini");
        Files.writeString(secrets, "[site.tls]\ntruststore-password=from-secrets\n");
        System.setProperty(SettingsFile.PROPERTY, base + "," + secrets);
        set.addAll(SettingsFile.applyFromSystemProperty());
        Map<String, String> settings = Map.of("tls.truststore", "${site.tls.truststore}", "tls.truststore-password", "${site.tls.truststore-password}");
        TlsSettings t = TlsSettings.from(settings);
        assertThat(t.truststore()).isEqualTo("/etc/drishti/tls/truststore.p12");
        assertThat(t.truststorePassword()).isEqualTo("from-secrets");
        assertThat(Secrets.secret(settings, "tls.truststore-password")).isEqualTo("from-secrets");
        assertThat(SettingsFile.sourceOf("site.tls.truststore-password")).isEqualTo(secrets.toString());
        assertThat(SettingsFile.shown("site.tls.truststore-password", t.truststorePassword())).isEqualTo(SettingsFile.MASK);
    }
}
