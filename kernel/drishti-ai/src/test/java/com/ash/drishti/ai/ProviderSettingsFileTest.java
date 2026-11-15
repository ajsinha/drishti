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
package com.ash.drishti.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.settingsfile.SettingsFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An AI provider file's key placeholder resolves a key of the settings files (the secrets file given second), never shown. */
class ProviderSettingsFileTest {

    @TempDir
    Path dir;
    private final List<String> set = new ArrayList<>();

    @AfterEach
    void clear() {
        set.forEach(System::clearProperty);
        System.clearProperty(SettingsFile.PROPERTY);
    }

    @Test
    void theApiKeyComesFromTheSecretsFileByItsKey() throws Exception {
        Path base = dir.resolve("prod.ini");
        Files.writeString(base, "[ai.main]\nmodel=gpt-x\n");
        Path secrets = dir.resolve("secrets.ini");
        Files.writeString(secrets, "[ai.main]\napi-key=sk-from-the-secrets-file\n");
        System.setProperty(SettingsFile.PROPERTY, base + "," + secrets);
        set.addAll(SettingsFile.applyFromSystemProperty());
        String yaml = """
                name: main
                type: openai
                base-url: https://llm.bank.internal/v1
                api-key: ${ai.main.api-key}
                models:
                  - {name: chat, id: gpt-x}
                """;
        ProviderConfig c = ProviderConfigLoader.fromNode("main", new ObjectMapper(new YAMLFactory()).readTree(yaml),
                com.ash.drishti.settingsfile.Placeholders::process);
        assertThat(c.apiKey()).isEqualTo("sk-from-the-secrets-file");
        assertThat(SettingsFile.sourceOf("ai.main.api-key")).isEqualTo(secrets.toString());
        assertThat(SettingsFile.shown("ai.main.api-key", c.apiKey())).isEqualTo(SettingsFile.MASK);
    }
}
