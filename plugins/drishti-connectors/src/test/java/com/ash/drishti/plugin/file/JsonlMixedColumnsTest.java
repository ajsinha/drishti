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
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * DATA-07 (QA 2026-10-01, hostile day 09-03): a promoted field with numbers on most lines, {@code "N/A"} on one and
 * 1e20 and 1.2e22 on two others. Its columns hold each value as the document does, so a search from columns agrees
 * with one from documents; numbers beyond a long keep their value.
 */
class JsonlMixedColumnsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 3);

    @TempDir
    Path root;

    private FileSourcePlugin started() {
        FileSourcePlugin p = new FileSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("root", root.toString(), "layout.trade.columns", "mtm,desk,notional", "id-field", "tradeId",
                "rescan-seconds", "3600")));
        return p;
    }

    @Test
    void aFieldWithNumbersAndTextKeepsEachValueAsTheDocumentHoldsIt() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.add("{\"tradeId\":\"T-" + (100 + i) + "\",\"mtm\":" + (i * 99_991 - 500_000) + ",\"desk\":\"D-" + i % 3 + "\",\"notional\":" + i * 1e6 + "}");
        }
        lines.add("{\"tradeId\":\"T-NA\",\"mtm\":\"N/A\",\"desk\":\"D-1\",\"notional\":5}");
        lines.add("{\"tradeId\":\"T-E20\",\"mtm\":1e20,\"desk\":\"D-2\",\"notional\":5}");
        lines.add("{\"tradeId\":\"T-E22\",\"mtm\":1.2e22,\"desk\":\"D-0\",\"notional\":5}");
        lines.add("{\"tradeId\":\"T-NONE\",\"desk\":\"D-0\",\"notional\":5}");
        Path f = root.resolve(DAY + "/trade.jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.UTF_8);

        FileSourcePlugin p = started();
        ColumnSet c = p.columns("trade", List.of("mtm", "desk", "notional"), AsOf.of(DAY)).orElseThrow();
        assertThat(c.numbers()).containsOnlyKeys("notional");                 // numbers only: a number column, as before
        assertThat(c.texts()).containsOnlyKeys("desk");                       // text only: a text column, as before
        for (int i = 0; i < c.size(); i++) {
            DataNode doc = p.fetch(EntityRef.of("trade", c.ids()[i]), AsOf.of(DAY)).orElseThrow().data();
            DataNode mtm = doc.get("mtm");
            Object column = c.value("mtm", i);
            if (mtm.isMissing() || mtm.isNull()) {
                assertThat(column).as(c.ids()[i]).isNull();
            } else if (mtm.type() == com.ash.drishti.api.NodeType.NUMBER) {
                assertThat(column).as(c.ids()[i]).isInstanceOf(Double.class);
                assertThat((Double) column).as(c.ids()[i]).isEqualTo(mtm.asDouble());
            } else {
                assertThat(column).as(c.ids()[i]).isEqualTo(mtm.asText());
            }
        }
        int e20 = java.util.Arrays.asList(c.ids()).indexOf("T-E20");
        int e22 = java.util.Arrays.asList(c.ids()).indexOf("T-E22");
        assertThat(c.value("mtm", e20)).isEqualTo(1e20);                     // not 9223372036854775807
        assertThat(c.value("mtm", e22)).isEqualTo(1.2e22);
        assertThat(c.numeric("mtm")[java.util.Arrays.asList(c.ids()).indexOf("T-NA")]).isNaN();
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(DAY)).orElseThrow().mixed()).containsOnlyKeys("mtm");
    }
}
