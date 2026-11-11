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

import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A file connector that is up but serves nothing says so in its health: the folders are all dated after today, or no line has the id field. */
class FileHealthWarningsTest {

    @TempDir
    Path root;

    private FileSourcePlugin started(Map<String, String> extra) {
        Map<String, String> s = new java.util.HashMap<>(Map.of("root", root.toString(), "rescan-seconds", "3600"));
        s.putAll(extra);
        FileSourcePlugin p = new FileSourcePlugin();
        p.start(DatedSourceContract.context(s));
        return p;
    }

    private void lines(String day, List<String> rows) throws Exception {
        Path f = root.resolve(day + "/note.jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, rows, StandardCharsets.UTF_8);
    }

    @Test
    void foldersAllDatedAfterTodayAreAWarningNotAPlainUp() throws Exception {
        lines("2099-01-05", List.of("{\"id\":\"n1\",\"text\":\"a\"}"));
        lines("2099-01-06", List.of("{\"id\":\"n2\",\"text\":\"b\"}"));
        FileSourcePlugin p = started(Map.of());
        assertThat(p.health()).startsWith("UP (").contains("0 rows served for").contains("'note' holds 2099-01-05 to 2099-01-06").contains("lookback-days");
        assertThat(p.stale(LocalDate.of(2099, 1, 6))).isEmpty();
    }

    @Test
    void aLakeOfOlderDaysOnlyStaysAPlainUp() throws Exception {
        lines("2020-01-05", List.of("{\"id\":\"n1\",\"text\":\"a\"}"));
        assertThat(started(Map.of()).health()).isEqualTo("UP");
    }

    @Test
    void aWrongIdFieldIsNamedInHealth() throws Exception {
        lines("2020-01-05", List.of("{\"noteId\":\"n1\",\"text\":\"a\"}", "{\"noteId\":\"n2\",\"text\":\"b\"}"));
        assertThat(started(Map.of()).health()).startsWith("UP (").contains("2 unreadable lines").contains("no id");
        assertThat(started(Map.of("id-field", "noteId")).health()).isEqualTo("UP");
    }
}
