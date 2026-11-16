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
package com.ash.drishti.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The tool base class: the same scenario as {@code test_a_tool_validates_runs_tracks_and_serves_itself_in_mcp_format} in the console's {@code tests/test_llm_contract.py}. */
class BaseMcpToolTest {

    static final class Echo extends BaseMcpTool {
        Echo(Map<String, Object> config) {
            super(config);
        }

        @Override public Object execute(Map<String, Object> arguments) {
            return Map.of("echo", arguments.get("text"));
        }

        @Override public Map<String, Object> getInputSchema() {
            return Map.of("type", "object", "properties", Map.of("text", Map.of("type", "string")), "required", List.of("text"), "additionalProperties", false);
        }

        @Override public Map<String, Object> getOutputSchema() {
            return Map.of("type", "object");
        }
    }

    @Test
    void aToolValidatesRunsTracksAndServesItselfInMcpFormat() {
        Echo t = new Echo(Map.of("name", "echo", "description", "Echoes", "version", "2.1"));
        assertThat(t.executeWithTracking(Map.of("text", "hi"))).isEqualTo(Map.of("echo", "hi"));
        assertThatThrownBy(() -> t.executeWithTracking(Map.of())).isInstanceOf(BaseMcpTool.ToolArgumentException.class);
        assertThatThrownBy(() -> t.executeWithTracking(Map.of("text", "x", "other", 1))).isInstanceOf(BaseMcpTool.ToolArgumentException.class);
        assertThat(t.getMetrics()).containsEntry("calls", 3L).containsEntry("errors", 2L);
        assertThat(t.toMcpFormat()).containsEntry("name", "echo");
        assertThat(t.version()).isEqualTo("2.1");
        assertThat(t.enabled()).isTrue();
        t.disable();
        assertThat(t.enabled()).isFalse();
    }
}
