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
package com.ash.drishti.plugin.file;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DateCoverage;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DATA-12 and DATA-15 (QA 2026-10-01): the file connector says which dates it holds, so the router treats it as
 * authoritative for them (an entity a day's file does not list is not held) and passes only other dates on; and it keeps
 * no earlier versions, so a read "as known at" an instant is not put to it.
 */
class FileCoverageTest {

    @TempDir
    Path root;

    private void write(String rel, String line) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, List.of(line), StandardCharsets.UTF_8);
    }

    @Test
    void aSnapshotDayIsHeldOtherDatesAreNotAndWhatCannotTellSaysSo() throws Exception {
        write("2026-09-29/trade.jsonl", "{\"id\":\"MX-1\",\"mtm\":1}");
        write("2026-09-30/trade.jsonl", "{\"id\":\"MX-1\",\"mtm\":2}");
        write("2026-09-30/counterparty.jsonl", "{\"id\":\"CP-1\"}");
        write("book.jsonl", "{\"id\":\"B-1\"}");
        write("2026-09-30/curve/USD.json", "{\"id\":\"USD\"}");
        FileSourcePlugin p = new FileSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("root", root.toString(), "mode.counterparty", "effective", "rescan-seconds", "3600",
                "lookback-days", "3")));

        assertThat(p.coverage("trade", AsOf.of(LocalDate.of(2026, 9, 29)))).isEqualTo(DateCoverage.HELD);
        assertThat(p.coverage("trade", AsOf.of(LocalDate.of(2026, 10, 2)))).isEqualTo(DateCoverage.HELD);   // 09-30, within the lookback
        assertThat(p.coverage("trade", AsOf.LATEST)).isEqualTo(DateCoverage.HELD);
        assertThat(p.coverage("trade", AsOf.of(LocalDate.of(2026, 9, 28)))).isEqualTo(DateCoverage.NOT_HELD);
        assertThat(p.coverage("trade", AsOf.of(LocalDate.of(2026, 10, 9)))).isEqualTo(DateCoverage.NOT_HELD);   // beyond the lookback
        assertThat(p.coverage("counterparty", AsOf.of(LocalDate.of(2026, 9, 30)))).isEqualTo(DateCoverage.UNKNOWN);   // effective
        assertThat(p.coverage("book", AsOf.of(LocalDate.of(2026, 9, 30)))).isEqualTo(DateCoverage.UNKNOWN);          // undated file
        assertThat(p.coverage("curve", AsOf.of(LocalDate.of(2026, 9, 30)))).isEqualTo(DateCoverage.UNKNOWN);         // feed folder
        assertThat(p.coverage("curve", AsOf.of(LocalDate.of(2026, 9, 1)))).isEqualTo(DateCoverage.NOT_HELD);         // none that date
        // a kind it has nothing of is not held, so a "known at" read passes it by for a store with time travel
        assertThat(p.coverage("netting-set", AsOf.of(LocalDate.of(2026, 9, 30)))).isEqualTo(DateCoverage.NOT_HELD);
        assertThat(p.timeTravel()).isFalse();
    }
}
