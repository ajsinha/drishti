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
package com.ash.drishti.plugin.iceberg;

import static com.ash.drishti.testkit.DatedSourceContract.context;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.UnreadableData;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * QA3B-03 and QA3B-04 on the path QA took: a domain written by {@link IcebergLoader} with {@code --engine native} (as
 * {@code tools/load-iceberg.sh --engine native} runs it), its folder moved, read by the native engine with table discovery and with
 * {@code kinds}. {@code IcebergMovedTableTest} writes its table through the library engine; this one through the loader, two tables moved
 * together, so the summary names both with one {@code relocate}, and the read's failure says what is wrong without a path. The server's
 * side (the badge of Admin → Connectors, the 502 and its one log line) is {@code IcebergMovedConnectorFileTest} in drishti-server.
 */
class IcebergMovedLoaderTableTest {

    private static Path base;
    private static Path moved;

    @BeforeAll
    static void loadAndMove() throws Exception {
        base = Files.createTempDirectory("drishti-iceberg-loader-moved");
        Path written = base.resolve("ice1");
        Path lines = base.resolve("rows.jsonl");
        StringBuilder sb = new StringBuilder();
        for (String kind : new String[] {"credit-limit", "cva"}) {
            for (String d : new String[] {"2026-09-29", "2026-09-30"}) {
                for (int i = 1; i <= 3; i++) {
                    sb.append("{\"domain\":\"credit\",\"kind\":\"").append(kind).append("\",\"id\":\"LIM-").append(i).append("\",\"date\":\"").append(d)
                            .append("\",\"doc\":\"{\\\"limit\\\":").append(i).append("}\",\"columns\":{\"limit\":").append(i).append("}}\n");
                }
            }
        }
        Files.writeString(lines, sb);
        IcebergLoader.load(new String[] {lines.toString(), written.toString(), "--engine", "native", "--spill-dir", base.resolve("spill").toString()});
        moved = base.resolve("ice2");
        Files.move(written, moved);
    }

    private static IcebergSourcePlugin start(String kinds, String relocate) throws Exception {
        Map<String, String> s = new HashMap<>(Map.of("root", moved.toString(), "domain", "credit", "source-name", "qa-ice-moved",
                "engine", "native", "refresh-seconds", "1"));
        if (kinds != null) {
            s.put("kinds", kinds);
        }
        if (relocate != null) {
            s.put("relocate", relocate);
        }
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        p.start(context(s));
        return p;
    }

    private static final AsOf SEPT_30 = AsOf.of(LocalDate.parse("2026-09-30"));

    @Test
    void aMovedLoaderTableIsDegradedWithThePairToCopyAndTheReadSaysWhy() throws Exception {
        for (String kinds : new String[] {null, "credit-limit"}) {
            IcebergSourcePlugin p = start(kinds, null);
            try {
                String pair = "'" + base.resolve("ice1") + " -> " + moved + "'";
                assertThat(p.health()).as(String.valueOf(kinds)).startsWith("DEGRADED: cannot read ").contains("relocate: " + pair)
                        .doesNotContain("file:");                              // the plain form the setting reads, not the metadata's URI
                if (kinds == null) {
                    assertThat(p.health()).as("two tables moved together: one sentence").contains("credit-limit, cva: their metadata names files");
                }
                Throwable t = catchThrowable(() -> p.fetch(EntityRef.of("credit-limit", "LIM-1"), SEPT_30));
                assertThat(t).isInstanceOf(UnreadableData.class).hasMessageContaining("credit-limit").hasMessageContaining("moved")
                        .hasMessageContaining("Admin → Health");
                assertThat(t.getMessage()).doesNotContain(base.toString());     // the reader is not told the folders
            } finally {
                p.close();
            }
        }
    }

    @Test
    void relocateAsPlainFoldersOrFileUrisReadsIt() throws Exception {
        for (String relocate : new String[] {base.resolve("ice1") + " -> " + moved, "file:" + base.resolve("ice1") + " -> file:" + moved,
            "'" + base.resolve("ice1") + "/ -> " + moved + "/'"}) {
            IcebergSourcePlugin p = start("credit-limit", relocate);
            try {
                assertThat(p.fetch(EntityRef.of("credit-limit", "LIM-2"), SEPT_30)).as(relocate).isPresent();
                assertThat(p.health()).as(relocate).startsWith("UP");
            } finally {
                p.close();
            }
        }
    }

    @Test
    void relocateThatMatchesNothingOrCannotBeReadIsSaid() throws Exception {
        IcebergSourcePlugin p = start("credit-limit", "/nowhere/ice1 -> " + moved);
        try {
            assertThat(p.health()).contains("(relocate /nowhere/ice1 -> " + moved + " is set, but no old folder in it holds ");
        } finally {
            p.close();
        }
        IcebergSourcePlugin q = start("credit-limit", base.resolve("ice1") + " -> " + base.resolve("ice3"));
        try {
            assertThat(q.health()).contains("sends its reads to " + base.resolve("ice3")).contains("check the new folder");
        } finally {
            q.close();
        }
        assertThatThrownBy(() -> start("credit-limit", base.resolve("ice1") + " " + moved)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relocate: '").hasMessageContaining("<old folder> -> <new folder>");
    }
}
