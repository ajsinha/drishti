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
package com.ash.drishti.server.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The words every screen and the CLI show for a connector: a connector that runs but is down is never "running". */
class ConnectorBadgeTest {

    @Test
    void aRunningConnectorIsRunningOnlyWhenItIsHealthy() {
        assertThat(ConnectorManager.shown("RUNNING", "UP", false)).containsExactly("ok", "running");
        assertThat(ConnectorManager.shown("RUNNING", "DOWN: no directory /x", false)).containsExactly("down", "down");
        assertThat(ConnectorManager.shown("RUNNING", "UP", true)).containsExactly("down", "down, retrying");
        assertThat(ConnectorManager.shown("RUNNING", "UP (2 unreadable lines in a.jsonl (line 1: no id))", false)).containsExactly("warn", "running, with a warning");
        assertThat(ConnectorManager.shown("RUNNING", "UP (0 rows served for 2026-10-12: 'note' holds 2099-01-05, none on or before it)", false))
                .containsExactly("warn", "running, with a warning");
    }

    @Test
    void theOtherStatesKeepTheirOwnWords() {
        assertThat(ConnectorManager.shown("DISABLED", null, false)).containsExactly("off", "disabled");
        assertThat(ConnectorManager.shown("FAILED", null, true)).containsExactly("down", "failed, retrying");
        assertThat(ConnectorManager.shown("FAILED", null, false)).containsExactly("down", "failed");
        assertThat(ConnectorManager.shown("NOT_LOADED", null, false)).containsExactly("warn", "not started");
        assertThat(ConnectorManager.shown("IDLE", null, false)).containsExactly("warn", "idle");
    }
}
