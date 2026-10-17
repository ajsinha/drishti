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
import org.junit.jupiter.api.io.TempDir;

/** {@code table.<kind>}: one kind per source system, each connector reading its own folder (trade_endur, trade_calypso) under one domain. */
class NamedTablesTest {

    @TempDir
    Path root;

    @Test
    void aKindIsReadFromTheFolderItsConnectorNames() throws Exception {
        for (String t : new String[] {"trade_endur", "trade_calypso", "desk"}) {
            Files.createDirectories(root.resolve("trading").resolve(t).resolve("_delta_log"));
        }
        try (LakeStore endur = LakeStore.of(root.toString(), "trading", Map.of("table.trade", "trade_endur"));
                LakeStore calypso = LakeStore.of(root.toString(), "trading", Map.of("table.trade", "trade_calypso"));
                LakeStore murex = LakeStore.of(root.toString(), "trading", Map.of("table.trade", "trade_murex"));
                LakeStore plain = LakeStore.of(root.toString(), "trading", Map.of())) {
            assertThat(endur.table("trade")).endsWith("trading/trade_endur");
            assertThat(calypso.table("trade")).endsWith("trading/trade_calypso");
            assertThat(endur.tables()).containsExactly("trade");          // only its own table: the others are other connectors'
            assertThat(murex.tables()).isEmpty();                          // not built yet
            assertThat(plain.tables()).containsExactly("desk", "trade_calypso", "trade_endur");
        }
        assertThatThrownBy(() -> LakeStore.of(root.toString(), "trading", Map.of("table.trade", "../x"))).hasMessageContaining("folder name");
    }
}
