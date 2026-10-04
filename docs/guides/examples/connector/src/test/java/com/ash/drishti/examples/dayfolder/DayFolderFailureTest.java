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
package com.ash.drishti.examples.dayfolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginNotConfigured;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.UnreadableData;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the contract does not cover: discovery, being unconfigured, unreadable data, and a rescan that fails. */
class DayFolderFailureTest {

    @TempDir
    Path dir;

    @Test
    void isDiscoveredThroughMetaInfServices() {
        assertThat(ServiceLoader.load(SourcePlugin.class)).anyMatch(p -> p instanceof DayFolderSourcePlugin);
    }

    @Test
    void withoutARootItIsInstalledButNotConfigured() {
        assertThatThrownBy(() -> new DayFolderSourcePlugin().start(DatedSourceContract.context(Map.of())))
                .isInstanceOf(PluginNotConfigured.class);
    }

    @Test
    void aLineThatIsNotJsonIsUnreadableDataNamingTheFileAndLine() throws Exception {
        Files.createDirectories(dir.resolve("trade"));
        Files.writeString(dir.resolve("trade/2026-09-28.jsonl"), "{\"id\":\"T-1\"}\nnot json\n");
        assertThatThrownBy(() -> new DayFolderSourcePlugin().start(DatedSourceContract.context(Map.of("root", dir.toString()))))
                .isInstanceOf(UnreadableData.class)
                .hasMessage("trade/2026-09-28.jsonl line 2 is not a document");
    }

    @Test
    void aFailedRescanKeepsServingTheLastScanAndSaysSo() throws Exception {
        Files.createDirectories(dir.resolve("trade"));
        Path day = dir.resolve("trade/2026-09-28.jsonl");
        Files.writeString(day, "{\"id\":\"T-1\",\"mtm\":1}\n");
        DayFolderSourcePlugin p = new DayFolderSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("root", dir.toString(), "rescan-seconds", "0")));
        assertThat(p.health()).startsWith("UP");

        Files.writeString(day, "{\"id\":\"T-1\",\"mtm\":2}\n{broken\n");
        p.purgeCaches();                                                  // rescans; this one fails

        assertThat(p.health()).startsWith("DEGRADED");
        assertThat(p.listingProblem("trade")).isPresent();
        assertThat(p.fetch(EntityRef.of("trade", "T-1"), AsOf.of(LocalDate.of(2026, 9, 28))).orElseThrow().data().get("mtm").asDouble())
                .isEqualTo(1);                                            // the previous scan still answers

        Files.writeString(day, "{\"id\":\"T-1\",\"mtm\":3}\n");
        p.purgeCaches();                                                  // the next good scan heals it
        assertThat(p.health()).startsWith("UP");
        assertThat(p.listingProblem("trade")).isEmpty();
    }
}
