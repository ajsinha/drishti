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

import com.ash.drishti.identity.db.IdentityRepositories;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;

/** The switches ledger on a database (docs/admin/USER_MANAGEMENT.md): what every supported database must do the same. */
final class SwitchLedgerChecks {

    private SwitchLedgerChecks() {}

    static void ledger(IdentityRepositories.Switches rows, IdentityRepositories.SwitchLog log, TransactionTemplate tx) {
        Clock t0 = Clock.fixed(Instant.parse("2026-10-25T10:00:00Z"), ZoneOffset.UTC);
        SwitchLedger a = new SwitchLedger(rows, log, tx, "node-a", t0);
        String name = "CHK" + System.nanoTime() % 100000;
        List<SwitchLedger.Entry> made = a.set(List.of(new SwitchLedger.Change(SwitchLedger.COMMAND, name, false)), "audit asked", "ann");
        assertThat(a.on(SwitchLedger.COMMAND, name)).isFalse();
        assertThat(a.hash()).isNotBlank();
        long stamp = made.get(0).stamp();
        assertThat(SwitchLedger.timeOf(stamp)).isEqualTo(Instant.parse("2026-10-25T10:00:00Z"));

        // the same clock again: the counter moves the stamp on, never back
        SwitchLedger.Entry second = a.set(List.of(new SwitchLedger.Change(SwitchLedger.COMMAND, name + "X", false)), "", "ann").get(0);
        assertThat(second.stamp()).isGreaterThan(stamp);

        // a restart reads the same state from the database
        SwitchLedger again = new SwitchLedger(rows, log, tx, "node-a", t0);
        assertThat(again.on(SwitchLedger.COMMAND, name)).isFalse();
        assertThat(again.hash()).isEqualTo(a.hash());

        // an older change from another node loses; a newer one wins, is written and logged once
        SwitchLedger.Entry older = new SwitchLedger.Entry(SwitchLedger.COMMAND, name, "*", true, "", "bob", stamp - 1, "node-b", null);
        assertThat(a.merge(List.of(older))).isEmpty();
        assertThat(a.on(SwitchLedger.COMMAND, name)).isFalse();
        SwitchLedger.Entry newer = new SwitchLedger.Entry(SwitchLedger.COMMAND, name, "*", true, "back on", "bob", stamp + (5L << 16), "node-b",
                Instant.parse("2026-10-25T10:00:05Z"));
        assertThat(a.merge(List.of(newer))).containsExactly(newer);
        assertThat(a.merge(List.of(newer))).as("the same change twice is applied once").isEmpty();
        assertThat(a.on(SwitchLedger.COMMAND, name)).isTrue();
        assertThat(new SwitchLedger(rows, log, tx, "node-a", t0).on(SwitchLedger.COMMAND, name)).as("written before it took effect").isTrue();
        assertThat(a.history(SwitchLedger.COMMAND, name, 10)).extracting(SwitchLedger.Entry::reason).containsExactly("back on", "audit asked");

        // a change made here after that is newer than what was learned (the clock moved past the stamp seen)
        SwitchLedger.Entry after = a.set(List.of(new SwitchLedger.Change(SwitchLedger.COMMAND, name, false)), "", "ann").get(0);
        assertThat(after.stamp()).isGreaterThan(newer.stamp());

        // at the same stamp the greater node id wins, whichever arrives first
        SwitchLedger.Entry z = new SwitchLedger.Entry(SwitchLedger.COMMAND, name, "*", true, "", "zed", after.stamp(), "node-z", null);
        SwitchLedger.Entry b = new SwitchLedger.Entry(SwitchLedger.COMMAND, name, "*", false, "", "bea", after.stamp(), "node-0", null);
        a.merge(List.of(z, b));
        assertThat(a.find(SwitchLedger.COMMAND, name)).get().extracting(SwitchLedger.Entry::node).isEqualTo("node-z");

        // a scope that is not a switch's is never taken
        assertThat(a.merge(List.of(new SwitchLedger.Entry("other", name, "*", false, "", "x", Long.MAX_VALUE, "n", null)))).isEmpty();
    }
}
