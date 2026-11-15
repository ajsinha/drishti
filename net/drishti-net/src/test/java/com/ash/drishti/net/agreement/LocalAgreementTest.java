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
package com.ash.drishti.net.agreement;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link LocalAgreement} passes the agreement contract (several nodes over one in-process referee), and its state survives a snapshot. */
class LocalAgreementTest extends AgreementContract {

    @Override
    protected Nodes start(List<String> ids, Duration ttl, Duration renew) {
        LocalAgreement.Referee referee = new LocalAgreement.Referee(System::currentTimeMillis);
        Map<String, LocalAgreement> nodes = new LinkedHashMap<>();
        ids.forEach(id -> nodes.put(id, new LocalAgreement(id, referee, ttl, renew, System::currentTimeMillis)));
        return new Nodes() {
            @Override
            public Agreement node(String id) {
                return nodes.get(id);
            }

            @Override
            public void crash(String id) {
                nodes.get(id).halt();
            }

            @Override
            public void loseReferee(String survivor) {
                referee.down(true);
            }

            @Override
            public void close() {
                nodes.values().forEach(LocalAgreement::close);
            }
        };
    }

    @Override
    protected Duration refereeLoss() {
        return Duration.ofMillis(200);
    }

    @Test
    void aSnapshotRestoresLeasesAndTheRegister() {
        AgreementState s = new AgreementState();
        s.apply(AgreementState.tick(1_000));
        s.apply(AgreementState.candidates("l", List.of("a", "b")));
        s.apply(AgreementState.ask("l", "a", 5_000));
        assertThat(AgreementState.readBoolean(s.apply(AgreementState.compareAndSet("k", 0, new byte[] {1, 2}, "ada")))).isTrue();
        AgreementState t = new AgreementState();
        t.restore(s.snapshot());
        assertThat(AgreementState.readEntry(t.query(AgreementState.get("k")))).hasValueSatisfying(e -> assertThat(e.value()).containsExactly(1, 2));
        AgreementState.Dump d = AgreementState.readDump(t.query(AgreementState.dump(0))).orElseThrow();
        assertThat(d.leases().get("l").node()).isEqualTo("a");
        assertThat(d.revision()).isEqualTo(s.revision());
    }

    @Test
    void timeIsACommandSoALeaseExpiresOnlyWhenTheRefereesClockSaysSo() {
        AgreementState s = new AgreementState();
        s.apply(AgreementState.tick(1_000));
        s.apply(AgreementState.candidates("l", List.of("a")));
        assertThat(AgreementState.readLease(s.apply(AgreementState.ask("l", "a", 500)))).hasValueSatisfying(l -> assertThat(l.expiresAt()).isEqualTo(1_500));
        s.apply(AgreementState.tick(1_499));
        assertThat(AgreementState.readDump(s.query(AgreementState.dump(0))).orElseThrow().leases()).containsKey("l");
        s.apply(AgreementState.tick(1_500));
        assertThat(AgreementState.readDump(s.query(AgreementState.dump(0))).orElseThrow().leases()).doesNotContainKey("l");
        s.apply(AgreementState.tick(900));
        assertThat(s.now()).as("time never goes back").isEqualTo(1_500);
    }

    @Test
    void atFirstOnlyTheFirstCandidateMayTakeTheLeaseUntilItHadOneTimeToLive() {
        AgreementState s = new AgreementState();
        s.apply(AgreementState.tick(1_000));
        s.apply(AgreementState.candidates("l", List.of("a", "b")));
        assertThat(AgreementState.readLease(s.apply(AgreementState.ask("l", "b", 500)))).isEmpty();
        s.apply(AgreementState.tick(1_600));
        assertThat(AgreementState.readLease(s.apply(AgreementState.ask("l", "b", 500)))).hasValueSatisfying(l -> assertThat(l.node()).isEqualTo("b"));
    }
}
