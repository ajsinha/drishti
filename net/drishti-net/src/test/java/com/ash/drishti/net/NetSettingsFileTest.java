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
package com.ash.drishti.net;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.net.config.NetSchema;
import com.ash.drishti.settingsfile.Placeholders;
import com.ash.drishti.settingsfile.SettingsFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A network file's placeholders resolve keys of the settings files: a namespaced section's key, nested defaults, an unset one empty. */
class NetSettingsFileTest {

    @TempDir
    Path dir;
    private final List<String> set = new ArrayList<>();

    @AfterEach
    void clear() {
        set.forEach(System::clearProperty);
        System.clearProperty(SettingsFile.PROPERTY);
    }

    @Test
    void aNetFilesPlaceholderResolvesASettingsFileKey() throws Exception {
        Path ini = dir.resolve("site.ini");
        Files.writeString(ini, "[net.cluster]\nsecret-file=/etc/drishti/net/secret\nseeds=10.0.0.11:18480,10.0.0.12:18480\n");
        System.setProperty(SettingsFile.PROPERTY, ini.toString());
        set.addAll(SettingsFile.applyFromSystemProperty());
        NetSchema schema = new NetSchema(Placeholders::process);
        assertThat(schema.resolve("${net.cluster.secret-file}")).isEqualTo("/etc/drishti/net/secret");
        assertThat(schema.resolve("${net.cluster.seeds:${net.seeds:none}}")).isEqualTo("10.0.0.11:18480,10.0.0.12:18480");
        assertThat(schema.resolve("${net.other.seeds}")).isEmpty();
    }
}
