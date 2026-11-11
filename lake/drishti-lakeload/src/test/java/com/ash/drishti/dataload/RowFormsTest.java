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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The self-describing row forms (DATA_LAKE_GUIDE.md section 5.1): the parallel loader has no reader for loader rows or lake rows, so a file
 * of them is refused with the reason and the tool that loads it; documents, in any compression, are not.
 */
class RowFormsTest {

    @TempDir Path tmp;

    static final String LAKE_ROW = "{\"domain\":\"trading\",\"kind\":\"trade\",\"table\":\"trade_endur\",\"id\":\"T1\",\"date\":\"2026-11-02\","
            + "\"doc\":\"{\\\"TRADE_ID\\\":\\\"T1\\\"}\"}\n";
    static final String LOADER_ROW = "{\"domain\":\"trading\",\"kind\":\"trade\",\"id\":\"T1\",\"date\":\"2026-11-02\","
            + "\"doc\":\"{\\\"TRADE_ID\\\":\\\"T1\\\"}\",\"columns\":{\"TRADE_ID\":\"T1\"}}\n";

    @Test
    void lakeRowsAndLoaderRowsAreRefusedWithTheToolThatLoadsThem() throws IOException {
        Path lake = tmp.resolve("rows.jsonl");
        Files.writeString(lake, "\n" + LAKE_ROW);
        assertThat(RowForms.refusal(lake)).startsWith("rows.jsonl holds lake rows").contains("tools/samplegen/load_rows.py");
        Path loader = tmp.resolve("rows.jsonl.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(loader))) {
            out.write(LOADER_ROW.getBytes(StandardCharsets.UTF_8));
        }
        assertThat(RowForms.refusal(loader)).startsWith("rows.jsonl.gz holds loader rows").contains("tools/load-*.sh");
        Path zst = tmp.resolve("rows.jsonl.zst");
        try (OutputStream out = new com.github.luben.zstd.ZstdOutputStream(Files.newOutputStream(zst))) {
            out.write(LAKE_ROW.getBytes(StandardCharsets.UTF_8));
        }
        assertThat(RowForms.refusal(zst)).contains("holds lake rows");
    }

    @Test
    void documentsInAnyFormAndArchivesAreTheLoadersToRead() throws IOException {
        Path doc = tmp.resolve("trades.jsonl");
        Files.writeString(doc, Fixture.rows("2026-11-02", "1"));
        assertThat(RowForms.refusal(doc)).isNull();
        Path withDoc = tmp.resolve("notes.jsonl");
        Files.writeString(withDoc, "{\"id\":\"N1\",\"doc\":\"a document that has a doc field\",\"BUSINESS_DATE\":\"2026-11-02\"}\n");
        assertThat(RowForms.refusal(withDoc)).as("a document may hold a doc field").isNull();
        Path notJson = tmp.resolve("bad.jsonl");
        Files.writeString(notJson, "not json\n");
        assertThat(RowForms.refusal(notJson)).isNull();
        Path zip = tmp.resolve("rows.zip");
        Files.writeString(zip, LAKE_ROW);
        assertThat(RowForms.refusal(zip)).isNull();
    }

    @Test
    void aCycleSendsAFileOfRowsToFailedWithTheReasonAndLoadsNothing() throws IOException {
        Fixture f = new Fixture(tmp);
        f.drop("trades-rows.jsonl", LAKE_ROW, "");
        f.drop("trades-docs.jsonl", Fixture.rows("2026-11-02", "1"), "");
        Cycle.Report r = f.run();
        assertThat(r.files()).filteredOn(o -> o.file().equals("trades-rows.jsonl")).singleElement().satisfies(o -> {
            assertThat(o.outcome()).isEqualTo("failed");
            assertThat(o.reason()).contains("holds lake rows").contains("no reader for lake rows");
        });
        assertThat(r.files()).filteredOn(o -> o.file().equals("trades-docs.jsonl")).singleElement().extracting(Cycle.FileOutcome::outcome)
                .isEqualTo("loaded");
        assertThat(f.loads).hasSize(1);
        assertThat(f.tree("failed")).contains("failed/2026-11-03/RUN/trades-rows.jsonl.failed.json");
    }
}
