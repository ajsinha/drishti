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
package com.ash.drishti.dataload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The inbox with the real loader (DATA_LAKE_GUIDE.md section 3.1): rejected rows said with their counts and reasons; a file sent again is a
 * duplicate only while the lake still holds what it loaded; a tar's {@code rows:} checked before anything is written.
 */
class InboxChecksTest {

    @TempDir
    java.nio.file.Path tmp;

    private DataInbox inbox(Fixture f) {
        return new DataInbox(f.settings, "node-a", f.clock, Fixture.ZONE, f.packs(), new LoaderProcess(f.settings), f.signal(), null);
    }

    @Test
    void rejectedRowsAreSaidWithTheirCountAndReasonInTheOutcomeTheNoticesAndTheBoard() throws IOException {
        Fixture f = new Fixture(tmp);
        f.settings = new DataLoadSettings(true, f.root.toString(), java.time.Duration.ofMinutes(10), 30, null, null, "256m",
                java.time.Duration.ofMinutes(5), 50.0, false, 30, f.lake.toString(), f.rules.toString(), false, null, null);     // up to half rejected
        f.drop("trades-r.2026-11-02.jsonl", Fixture.rows("2026-11-02", "R1", "R2") + "{\"DESK\":\"RATES\",\"BUSINESS_DATE\":\"2026-11-02\"}\n", "");
        DataInbox inbox = inbox(f);
        Cycle.Report r = inbox.cycle("test");
        assertThat(r.files()).as(r.toString()).singleElement().satisfies(o -> {
            assertThat(o.outcome()).isEqualTo("loaded");
            assertThat(o.rejected()).isEqualTo(1);
            assertThat(o.reason()).isEqualTo("loaded with rejects: 1 of 3 rows rejected: no id (field 'TRADE_ID' is missing or empty) (1)");
        });
        assertThat(r.notices()).anyMatch(n -> n.startsWith("trades-r.2026-11-02.jsonl: loaded with rejects: 1 of 3 rows rejected"));
        assertThat(inbox.status().toString()).contains("loaded-with-rejects").contains("1 of 3 rows rejected: no id");
    }

    @Test
    void anIdenticalFileLoadsAgainWhenItsDateWasReplacedSinceAndIsADuplicateWhileItIsThere() throws IOException {
        Fixture f = new Fixture(tmp);
        DataInbox inbox = inbox(f);
        String a = Fixture.rows("2026-11-02", "A1", "A2");
        f.drop("trades-a.2026-11-02.jsonl", a, "mode: replace\n");
        assertThat(inbox.cycle("test").files()).singleElement().extracting(Cycle.FileOutcome::outcome).isEqualTo("loaded");
        f.drop("trades-b.2026-11-02.jsonl", Fixture.rows("2026-11-02", "B1"), "mode: replace\n");          // replaces the date
        assertThat(inbox.cycle("test").files()).singleElement().extracting(Cycle.FileOutcome::outcome).isEqualTo("loaded");

        f.drop("trades-a.2026-11-02.jsonl", a, "mode: replace\n");                                          // the same bytes again
        Cycle.Report again = inbox.cycle("test");
        assertThat(again.files()).as("its date no longer holds its rows: loaded again").singleElement().satisfies(o -> {
            assertThat(o.outcome()).isEqualTo("loaded");
            assertThat(o.rows()).isEqualTo(2);
        });
        f.drop("trades-a.2026-11-02.jsonl", a, "mode: replace\n");
        assertThat(inbox.cycle("test").files()).as("now the lake holds it: a duplicate").singleElement()
                .extracting(Cycle.FileOutcome::outcome).isEqualTo("duplicate");
    }

    @Test
    void aTarsRowsAreCheckedBeforeAnythingIsWritten() throws IOException {
        Fixture f = new Fixture(tmp);
        Map<String, String> members = new LinkedHashMap<>();
        members.put("trade/2026-11-02/t.jsonl", Fixture.rows("2026-11-02", "T1", "T2", "T3"));
        Files.write(f.inbox().resolve("eod.tar"), DataInboxLoaderTest.tar(members));
        Files.writeString(f.inbox().resolve("eod.tar.ready"), "rows: 4\n");
        f.age(f.inbox().resolve("eod.tar"), f.inbox().resolve("eod.tar.ready"));
        DataInbox inbox = inbox(f);
        assertThat(inbox.cycle("test").files()).singleElement().satisfies(o -> {
            assertThat(o.outcome()).isEqualTo("failed");
            assertThat(o.reason()).contains("its .ready file says 4 rows, the archive holds 3 documents (trade 3)");
        });
        assertThat(f.lake.resolve("demo/trade")).doesNotExist();

        Files.write(f.inbox().resolve("eod2.tar"), DataInboxLoaderTest.tar(members));
        Files.writeString(f.inbox().resolve("eod2.tar.ready"), "rows: 3\n");
        f.age(f.inbox().resolve("eod2.tar"), f.inbox().resolve("eod2.tar.ready"));
        assertThat(inbox.cycle("test").files()).singleElement().extracting(Cycle.FileOutcome::outcome).isEqualTo("loaded");
    }
}
