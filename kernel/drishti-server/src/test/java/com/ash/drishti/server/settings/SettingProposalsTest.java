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
package com.ash.drishti.server.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Admin → Configuration's proposals: four eyes, nothing live until approved, secrets refused, one line in application.local.yaml. */
class SettingProposalsTest {

    @TempDir
    Path dir;

    private final List<String> audited = new ArrayList<>();
    private SettingProposals proposals;
    private Path local;

    @BeforeEach
    void setUp() {
        AuditLog audit = new AuditLog() {
            @Override
            public void record(String actor, String action, String subject, String detail) {
                audited.add(actor + " " + action + " " + subject);
            }

            @Override
            public List<Event> recent(int limit, String subject) {
                return List.of();
            }
        };
        local = dir.resolve("application.local.yaml");
        proposals = new SettingProposals(dir.resolve("settings/proposals.json"), local, audit,
                Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void aProposalChangesNothingUntilASecondAdministratorApprovesIt() throws Exception {
        Files.writeString(local, "# mine\ndrishti.history.max-days: 30\n");
        SettingProposals.Proposal p = proposals.propose("drishti.var.confidence", "engines", "0.975", "0.99", "the board asked", "mara");
        assertThat(p.state()).isEqualTo("proposed");
        assertThat(Files.readString(local)).doesNotContain("drishti.var.confidence");
        assertThatThrownBy(() -> proposals.decide(p.id(), true, "", "mara"))
                .isInstanceOfSatisfying(DrishtiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FOUR_EYES));
        SettingProposals.Proposal done = proposals.decide(p.id(), true, "ok", "li");
        assertThat(done.state()).isEqualTo("approved");
        assertThat(done.applied()).isTrue();
        String text = Files.readString(local);
        assertThat(text).startsWith("# mine\ndrishti.history.max-days: 30\n").contains("drishti.var.confidence: 0.975   # Admin -> Configuration: proposal "
                + p.id() + " by mara, approved by li");
        assertThat(audited).containsExactly("mara setting-proposed setting/drishti.var.confidence", "li setting-approved setting/drishti.var.confidence");
    }

    @Test
    void aSecondApprovalOfTheSameKeyReplacesItsLine() throws Exception {
        String first = proposals.propose("drishti.lenses", "server", "risk,world", "", "two lenses", "mara").id();
        proposals.decide(first, true, "", "li");
        String second = proposals.propose("drishti.lenses", "server", "risk", "risk,world", "one lens", "mara").id();
        proposals.decide(second, true, "", "li");
        assertThat(Files.readAllLines(local).stream().filter(l -> l.startsWith("drishti.lenses:"))).containsExactly(
                "drishti.lenses: \"risk\"   # Admin -> Configuration: proposal " + second + " by mara, approved by li, 2026-11-07T10:00:00Z");
    }

    @Test
    void secretsOtherAreasAndASecondOpenProposalAreRefused() {
        assertThatThrownBy(() -> proposals.propose("drishti.security.secret", "server", "x", "", "why", "mara"))
                .isInstanceOfSatisfying(DrishtiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST));
        assertThatThrownBy(() -> proposals.propose("url", "connectors", "x", "", "why", "mara")).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> proposals.propose("drishti.var.confidence", "engines", "0.9", "", " ", "mara")).isInstanceOf(DrishtiException.class);
        proposals.propose("drishti.var.confidence", "engines", "0.9", "", "why", "mara");
        assertThatThrownBy(() -> proposals.propose("drishti.var.confidence", "engines", "0.95", "", "why", "li"))
                .isInstanceOfSatisfying(DrishtiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PROPOSAL_CONFLICT));
    }

    @Test
    void aRejectionOrAWithdrawalWritesNothingAndAConsoleKeyWaitsForItsConsole() {
        String a = proposals.propose("ui.placeholder", "console", "TRD ...", "", "shorter", "mara").id();
        SettingProposals.Proposal approved = proposals.decide(a, true, "", "li");
        assertThat(approved.applied()).isFalse();
        assertThat(proposals.markApplied(a, "li").applied()).isTrue();
        String b = proposals.propose("drishti.var.scenario-window", "engines", "500", "250", "longer", "mara").id();
        assertThat(proposals.decide(b, false, "not now", "li").state()).isEqualTo("rejected");
        String c = proposals.propose("drishti.var.scenario-window", "engines", "260", "250", "a bit longer", "mara").id();
        assertThatThrownBy(() -> proposals.withdraw(c, "li")).isInstanceOf(DrishtiException.class);
        assertThat(proposals.withdraw(c, "mara").state()).isEqualTo("withdrawn");
        assertThat(Files.exists(local)).isFalse();
        assertThat(proposals.list()).extracting(SettingProposals.Proposal::id).containsExactly(c, b, a);
    }

    @Test
    void valuesAreWrittenAsYaml() {
        assertThat(SettingProposals.yamlValue("250")).isEqualTo("250");
        assertThat(SettingProposals.yamlValue("true")).isEqualTo("true");
        assertThat(SettingProposals.yamlValue("[0.95, 0.99]")).isEqualTo("[0.95, 0.99]");
        assertThat(SettingProposals.yamlValue("a: b # c")).isEqualTo("\"a: b # c\"");
    }
}
