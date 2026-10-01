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
package com.ash.drishti.plugin.delta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How the connector picks its Delta engine: the setting, then DRISHTI_DELTA_ENGINE, then native; auto by platform. */
class EngineKindTest {

    @Test
    void theSettingWinsThenTheEnvironmentThenNative() {
        assertThat(EngineKind.choose(null, null, "./data/delta", false)).isEqualTo(EngineKind.NATIVE);
        assertThat(EngineKind.choose("", "", "./data/delta", false)).isEqualTo(EngineKind.NATIVE);
        assertThat(EngineKind.choose(null, "hadoop", "./data/delta", false)).isEqualTo(EngineKind.HADOOP);
        assertThat(EngineKind.choose("native", "hadoop", "./data/delta", false)).isEqualTo(EngineKind.NATIVE);
        assertThat(EngineKind.choose("HADOOP", null, "./data/delta", true)).isEqualTo(EngineKind.HADOOP);
    }

    @Test
    void autoIsNativeOnWindowsAndHadoopElsewhere() {
        assertThat(EngineKind.choose("auto", null, "C:\\drishti\\data\\delta", true)).isEqualTo(EngineKind.NATIVE);
        assertThat(EngineKind.choose("auto", null, "./data/delta", false)).isEqualTo(EngineKind.HADOOP);
        assertThat(EngineKind.choose(null, "auto", "s3a://lakes/banking", true)).isEqualTo(EngineKind.NATIVE);
        assertThat(EngineKind.choose("auto", null, "abfs://lake@acct.dfs.core.windows.net/banking", true)).isEqualTo(EngineKind.HADOOP);
    }

    @Test
    void nativeRefusesWhatItCannotReadAndUnknownNamesAreRefused() {
        assertThatThrownBy(() -> EngineKind.choose("native", null, "abfs://lake@acct.dfs.core.windows.net/banking", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("engine: hadoop");
        assertThatThrownBy(() -> EngineKind.choose("spark", null, "./data/delta", false)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("native, hadoop or auto");
    }

    @Test
    void aNativeLakeListsItsTablesAndSaysWhereItIs() throws Exception {
        Path root = Files.createTempDirectory("drishti-native-lake");
        Files.createDirectories(root.resolve("banking/trade/_delta_log"));
        Files.createDirectories(root.resolve("banking/account/_delta_log"));
        Files.createDirectories(root.resolve("banking/not-a-table"));
        try (LakeStore lake = LakeStore.of(root.toString(), "banking", Map.of("engine", "native"))) {
            assertThat(lake.engineName()).isEqualTo("native");
            assertThat(lake.tables()).containsExactly("account", "trade");
            assertThat(lake.reachable()).isTrue();
            assertThat(lake.describe()).isEqualTo(root.resolve("banking").toString());
            assertThat(lake.table("trade")).isEqualTo("file:" + root.resolve("banking/trade"));
        }
        try (LakeStore missing = LakeStore.of(root.resolve("nowhere").toString(), "banking", Map.of("engine", "native"))) {
            assertThat(missing.reachable()).isFalse();
            assertThat(missing.tables()).isEmpty();
        }
        assertThatThrownBy(() -> LakeStore.of(root.toString(), "..\\up", Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
