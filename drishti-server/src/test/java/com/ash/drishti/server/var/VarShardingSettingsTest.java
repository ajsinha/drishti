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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * VaR sharding's settings (VAR_SHARDING.md section 9): off by default (no block, no setting: every date loaded whole, as before 2.9.6); the
 * server's keys switch it on with the pack's defaults (legal entity and desk, the trades' two kinds, 2048 MB, two replicas); a server
 * setting that is not valid refuses the start, saying why.
 */
class VarShardingSettingsTest {

    static VarProperties var() {
        return new VarProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }

    static VarShardingProperties own(Boolean enabled, List<String> key, List<String> kinds, Double mb, Integer replicas) {
        return new VarShardingProperties(enabled, key, kinds, mb, replicas, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void offByDefault() {
        VarSharding s = VarSharding.of(VarShardingProperties.none(), var(), () -> null).get();
        assertThat(s.enabled()).isFalse();
        assertThat(s.origin()).isEqualTo("default");
        assertThat(VarSharding.of(own(false, null, null, null, null), var(), () -> null).get().enabled()).isFalse();
        VarShardingProperties d = VarShardingProperties.none();
        assertThat(d.stableSeconds()).isEqualTo(30);
        assertThat(d.graceSeconds()).isEqualTo(30);
        assertThat(d.coordinatorOnly()).isFalse();
        assertThat(d.drain()).isFalse();
    }

    @Test
    void theServersKeysSwitchItOnWithTheDefaults() {
        VarSharding s = VarSharding.of(own(true, null, null, null, null), var(), () -> null).get();
        assertThat(s.enabled()).isTrue();
        assertThat(s.key()).containsExactly("LEGAL_ENTITY", "DESK");
        assertThat(s.kinds()).containsExactly("pnl-vector", "scenario-pv");
        assertThat(s.targetBytes()).isEqualTo(2048L * 1024 * 1024);
        assertThat(s.replicas()).isEqualTo(2);
        VarSharding le = VarSharding.of(own(true, List.of("legal_entity"), List.of("pnl-vector"), 512.0, 3), var(), () -> null).get();
        assertThat(le.key()).containsExactly("LEGAL_ENTITY");
        assertThat(le.targetBytes()).isEqualTo(512L * 1024 * 1024);
        assertThat(le.replicas()).isEqualTo(3);
        assertThat((Map<String, Object>) le.describe()).containsEntry("enabled", true).containsEntry("replicas", 3);
    }

    @Test
    void aServerSettingThatIsNotValidRefusesTheStart() {
        assertThatThrownBy(() -> VarSharding.of(own(true, List.of("DESK"), null, null, null), var(), () -> null))
                .hasMessageContaining("drishti.var.sharding").hasMessageContaining("LEGAL_ENTITY");
        assertThatThrownBy(() -> VarSharding.of(own(true, null, List.of("risk-hierarchy"), null, null), var(), () -> null))
                .hasMessageContaining("only the trades' scenario P&L");
        assertThatThrownBy(() -> VarSharding.of(own(true, null, null, 0.0, null), var(), () -> null)).hasMessageContaining("target-shard-mb");
        assertThatThrownBy(() -> VarSharding.of(own(true, null, null, null, 0), var(), () -> null)).hasMessageContaining("replicas");
    }
}
