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
package com.ash.drishti.server.bi.runs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** BIR-11: the run object's {@code can} offers only what this server can do now (share, export and promote are built; the rest as it is). */
class RunCanTest {

    static Run done(String type) {
        Instant t = Instant.now();
        return Run.planned("r-java-1-01JA00000000000000000000" + type.length() + "0", "rita", "rita", type, "{}", "ab".repeat(32), "sig", Tier.INTERACTIVE,
                "local", "{}", "java-1", t).moveTo(RunState.RUNNING, null, null, t).moveTo(RunState.DONE, null, null, t);
    }

    static Map<String, Object> can(Run r, RunJson.View v) {
        return RunJson.can(r, v);
    }

    static RunJson.View owner() {
        return RunJson.View.of("rita").withResult(true).withPowers(false, true);
    }

    @Test
    void aFinishedModelQueryOffersShareExportPromoteRerunAndSchedule() {
        Map<String, Object> c = can(done("mq"), owner());
        assertThat(c).containsEntry("open", true).containsEntry("share", true).containsEntry("export", true).containsEntry("promote", true)
                .containsEntry("rerun", true).containsEntry("schedule", true).containsEntry("keep", true).containsEntry("delete", true)
                .containsEntry("confirm", false).containsEntry("discard", false).containsEntry("cancel", false);
    }

    @Test
    void withoutDistributionNothingExports() {
        assertThat(can(done("mq"), owner().withExports(false))).containsEntry("export", false).containsEntry("share", true);
    }

    @Test
    void aStatementRunIsItsFileAndAScheduledSendIsNotAskedAgainFromHere() {
        Map<String, Object> statement = can(done("statement"), owner());
        assertThat(statement).containsEntry("export", false).containsEntry("schedule", false).containsEntry("rerun", true);
        Map<String, Object> send = can(done("subscription"), owner());
        assertThat(send).containsEntry("rerun", false).containsEntry("schedule", false);
    }

    @Test
    void anotherPersonMayDoNothingAndAnAdminMayOnlyCancelOrDelete() {
        Map<String, Object> other = can(done("mq"), RunJson.View.of("ann").withResult(true));
        assertThat(other.values()).containsOnly(false);
        Map<String, Object> admin = can(done("mq"), RunJson.View.of("ops").withResult(true).withPowers(true, false));
        assertThat(admin).containsEntry("delete", true).containsEntry("share", false).containsEntry("export", false).containsEntry("open", false);
    }
}
