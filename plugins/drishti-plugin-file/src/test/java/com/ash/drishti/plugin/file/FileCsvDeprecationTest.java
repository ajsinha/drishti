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

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CSV input is deprecated (2.9.4, docs/guides/DATA_FORMATS.md): a CSV feed file is still served, and health names it until it is gone. */
class FileCsvDeprecationTest {

    @TempDir
    Path root;

    private FileSourcePlugin started() {
        FileSourcePlugin p = new FileSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("root", root.toString(), "rescan-seconds", "3600")));
        return p;
    }

    @Test
    void aCsvFileIsServedAndNamedInHealthUntilItIsGone() throws Exception {
        Files.createDirectories(root.resolve("fixing"));
        Files.writeString(root.resolve("fixing/SOFR-HISTORY.csv"), "date,rate\n2026-09-30,3.86\n");
        FileSourcePlugin p = started();
        assertThat(p.fetch(EntityRef.of("fixing", "SOFR-HISTORY"))).isPresent();
        assertThat(p.health()).startsWith("UP (").contains("CSV input is deprecated: fixing/SOFR-HISTORY.csv").contains("drishti.py convert");
        Files.delete(root.resolve("fixing/SOFR-HISTORY.csv"));
        assertThat(p.health()).isEqualTo("UP");
    }

    @Test
    void jsonLinesAndPerEntityJsonAreAPlainUp() throws Exception {
        Files.writeString(root.resolve("fixing.jsonl"), "{\"id\":\"SOFR-HISTORY\",\"rows\":[{\"date\":\"2026-09-30\",\"rate\":3.86}]}\n");
        Files.createDirectories(root.resolve("settlement"));
        Files.writeString(root.resolve("settlement/CL-DEC26.json"), "{\"settle\": 71.15}");
        FileSourcePlugin p = started();
        assertThat(p.fetch(EntityRef.of("fixing", "SOFR-HISTORY"))).isPresent();
        assertThat(p.health()).isEqualTo("UP");
    }
}
