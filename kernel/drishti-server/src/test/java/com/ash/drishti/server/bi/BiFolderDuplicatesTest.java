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
package com.ash.drishti.server.bi;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One id, one file: two files of one id in two domain folders are both refused and listed, never one of them read. */
class BiFolderDuplicatesTest {

    @TempDir Path dir;

    private static void write(Path f, String text) throws Exception {
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
    }

    @Test
    void aModelInTwoDomainFoldersIsRefusedAndBothFilesAreNamed() throws Exception {
        Path bi = dir.resolve("bi");
        Path tables = dir.resolve("tables");
        write(tables.resolve("banking/trades-basic.model.yaml"), "model: trades-basic\ntitle: With the rules\n");
        write(tables.resolve("bi/trades-basic.model.yaml"), "model: trades-basic\ntitle: Without them\n");
        write(tables.resolve("banking/other.model.yaml"), "model: other\ntitle: Once\n");
        write(bi.resolve("reports/banking/desk.report.yaml"), "title: Desk\npages: []\n");
        write(bi.resolve("reports/desk.report.yaml"), "title: Desk again\npages: []\n");
        try (BiFolder folder = new BiFolder(bi.toString(), tables.toString(), Duration.ofMillis(100), "off")) {
            folder.start();
            assertThat(folder.item("file:model:trades-basic")).isEmpty();                  // neither, never the last one read
            assertThat(folder.item("file:report:desk")).isEmpty();
            assertThat(folder.item("file:model:other")).isPresent();
            assertThat(folder.problems()).anySatisfy(p -> {
                assertThat(p.file()).isEqualTo("tables/banking/trades-basic.model.yaml");
                assertThat(p.message()).startsWith(BiFolder.DEFINED_TWICE).contains("tables/banking/trades-basic.model.yaml")
                        .contains("tables/bi/trades-basic.model.yaml").contains("none is read");
                assertThat(p.keptLastGood()).isFalse();
            });
            assertThat(folder.problems()).anySatisfy(p -> assertThat(p.file()).isEqualTo("tables/bi/trades-basic.model.yaml"));
            assertThat(folder.problems()).anySatisfy(p -> assertThat(p.message()).contains("reports/banking/desk.report.yaml").contains("reports/desk.report.yaml"));

            Files.delete(tables.resolve("bi/trades-basic.model.yaml"));                       // one file left: it is read, the problem goes
            folder.reload();
            assertThat(folder.item("file:model:trades-basic").orElseThrow().title()).isEqualTo("With the rules");
            assertThat(folder.problems()).noneMatch(p -> p.file().contains("trades-basic"));
        }
    }
}
