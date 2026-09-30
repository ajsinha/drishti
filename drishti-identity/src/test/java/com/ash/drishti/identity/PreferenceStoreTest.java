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
package com.ash.drishti.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreferenceStoreTest {

    @TempDir
    Path dir;
    final ObjectMapper json = new ObjectMapper();

    @Test
    void storesPerUserAndNamespaceWithLimits() throws Exception {
        PreferenceStore s = new PreferenceStore(dir, 200, 2);
        s.put("ash", "workspaces", "Credit desk", json.readTree("{\"layout\":\"2col\"}"));
        s.put("ash", "workspaces", "Rates", json.readTree("{\"layout\":\"2x2\"}"));
        s.put("tina", "workspaces", "Mine", json.readTree("{}"));
        assertThat(s.keys("ash", "workspaces")).containsExactly("Credit desk", "Rates");
        assertThat(s.get("ash", "workspaces", "Rates").orElseThrow().get("layout").asText()).isEqualTo("2x2");
        assertThat(s.keys("tina", "workspaces")).containsExactly("Mine");
        assertThatThrownBy(() -> s.put("ash", "workspaces", "Third", json.readTree("{}"))).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> s.put("ash", "workspaces", "../evil", json.readTree("{}"))).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> s.put("ash", "workspaces", "Rates", json.readTree("{\"x\":\"" + "y".repeat(300) + "\"}")))
                .hasMessageContaining("larger");
        assertThat(s.delete("ash", "workspaces", "Rates")).isTrue();
        assertThat(new PreferenceStore(dir, 200, 2).keys("ash", "workspaces")).containsExactly("Credit desk");
        s.forget("tina");
        assertThat(s.keys("tina", "workspaces")).isEmpty();
    }
}
