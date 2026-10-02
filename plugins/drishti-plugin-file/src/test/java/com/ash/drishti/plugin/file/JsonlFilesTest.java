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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The file connector over JSON-lines files ({@code <root>/<domain>/<date>/<kind>.jsonl}) written by {@link JsonlLoader}
 * from the contract's rows, with {@code mtm} and {@code nettingSet} promoted: the same tests as every dated source,
 * plus columns, reverse lookups, a plain document per line and a file rewritten under a running connector.
 */
class JsonlFilesTest extends DatedSourceContract {

    private static Path root;
    private static FileSourcePlugin plugin;

    private static String line(Row r) {
        StringBuilder s = new StringBuilder("{\"domain\":\"desk\",\"kind\":\"").append(r.kind()).append("\",\"id\":\"").append(r.id())
                .append("\",\"date\":\"").append(r.date()).append("\",\"doc\":\"")
                .append(new String(JsonStringEncoder.getInstance().quoteAsString(r.json()))).append('"');
        if (r.kind().equals("trade")) {
            var doc = new com.ash.drishti.common.JsonCodec().read(r.json());
            s.append(",\"columns\":{\"mtm\":").append(doc.get("mtm").asDouble()).append(",\"nettingSet\":\"").append(doc.get("nettingSet").asText())
                    .append("\"}");
        }
        return s.append('}').toString();
    }

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        if (plugin != null) {
            return plugin;
        }
        root = Files.createTempDirectory("jsonl");
        Path in = Files.createTempFile("rows", ".jsonl");
        Files.write(in, ROWS.stream().map(JsonlFilesTest::line).toList(), StandardCharsets.UTF_8);
        JsonlLoader.main(new String[] {in.toString(), root.toString()});
        FileSourcePlugin p = new FileSourcePlugin();
        p.start(context(Map.of("root", root.toString(), "domain", "desk", "mode.counterparty", "effective", "source-name", "desk-files",
                "layout.trade.columns", "mtm,nettingSet")));
        plugin = p;
        return p;
    }

    @Test
    void aFutureDatedRowIsNotWritten() throws Exception {
        Path into = Files.createTempDirectory("jsonl-future");
        Path in = Files.createTempFile("rows", ".jsonl");
        Files.write(in, List.of("{\"domain\":\"desk\",\"kind\":\"trade\",\"id\":\"T-1\",\"date\":\"2026-09-30\",\"doc\":\"{}\"}",
                "{\"domain\":\"desk\",\"kind\":\"trade\",\"id\":\"T-TYPO\",\"date\":\"2099-03-01\",\"doc\":\"{}\"}"), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> JsonlLoader.main(new String[] {in.toString(), into.toString()})).hasMessageContaining("1 rows not loaded");
        assertThat(into.resolve("desk/2026-09-30/trade.jsonl")).exists();
        assertThat(into.resolve("desk/2099-03-01")).doesNotExist();
    }

    @Test
    void theLoaderWritesAFilePerKindPerDay() throws Exception {
        plugin();
        assertThat(root.resolve("desk/2026-09-28/trade.jsonl")).exists();
        assertThat(Files.readAllLines(root.resolve("desk/2026-09-28/trade.jsonl"))).hasSize(3);
        try (var files = Files.walk(root)) {
            assertThat(files.filter(f -> f.toString().endsWith(".tmp")).toList()).isEmpty();
        }
    }

    @Test
    void aDaysPromotedFieldsAnswerSearchesAndReverseLookupsWithoutDocuments() throws Exception {
        SourcePlugin p = plugin();
        assertThat(p.columnar("trade")).containsExactlyInAnyOrder("mtm", "nettingSet");
        assertThat(p.columnar("counterparty")).isEmpty();
        ColumnSet c = p.columns("trade", List.of("mtm", "nettingSet"), AsOf.LATEST).orElseThrow();
        assertThat(c.size()).isEqualTo(2);
        for (int i = 0; i < c.size(); i++) {
            var doc = p.fetch(EntityRef.of("trade", c.ids()[i])).orElseThrow().data();
            assertThat((Double) c.value("mtm", i)).isEqualTo(doc.get("mtm").asDouble());
        }
        assertThat(p.columns("trade", List.of("mtm"), AsOf.of(java.time.LocalDate.of(2019, 12, 5)))).isEmpty();
        assertThat(p.reverse(EntityRef.of("netting-set", "NS-B"), "trade", AsOf.of(D1))).containsExactly(EntityRef.of("trade", "T-3"));
        assertThat(p.search("trade", "t-", 10)).hasSize(2);
    }

    @Test
    void aPlainDocumentPerLineNeedsNoEnvelope() throws Exception {
        Path dir = Files.createTempDirectory("plain");
        Files.writeString(dir.resolve("book.jsonl"), "{\"id\":\"BOOK-1\",\"desk\":\"DESK-RATES\",\"limit\":5}\n{\"id\":\"BOOK-2\",\"desk\":\"DESK-FX\"}\n");
        FileSourcePlugin p = new FileSourcePlugin();
        p.start(context(Map.of("root", dir.toString(), "layout.book.columns", "desk")));
        assertThat(p.fetch(EntityRef.of("book", "BOOK-2")).orElseThrow().data().get("desk").asText()).isEqualTo("DESK-FX");
        assertThat(p.columns("book", List.of("desk"), AsOf.LATEST).orElseThrow().texts().get("desk")).containsExactly("DESK-RATES", "DESK-FX");
        assertThat(p.reverse(EntityRef.of("desk", "DESK-FX"), "book", AsOf.LATEST)).containsExactly(EntityRef.of("book", "BOOK-2"));
    }

    @Test
    void aRewrittenFileIsIndexedAgain() throws Exception {
        Path dir = Files.createTempDirectory("rewrite");
        Path f = dir.resolve("book.jsonl");
        Files.writeString(f, "{\"id\":\"BOOK-1\",\"desk\":\"DESK-RATES\"}\n");
        FileSourcePlugin p = new FileSourcePlugin();
        p.start(context(Map.of("root", dir.toString())));
        assertThat(p.fetch(EntityRef.of("book", "BOOK-1"))).isPresent();
        Files.writeString(f, "{\"id\":\"BOOK-9\",\"desk\":\"DESK-FX\"}\n{\"id\":\"BOOK-1\",\"desk\":\"DESK-EQ\"}\n");
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000));
        assertThat(p.fetch(EntityRef.of("book", "BOOK-1")).orElseThrow().data().get("desk").asText()).isEqualTo("DESK-EQ");
        assertThat(p.fetch(EntityRef.of("book", "BOOK-9"))).isPresent();
    }

    @Test
    void aLargeFileIndexedInSegmentsFindsEveryLine() throws Exception {
        Path f = Files.createTempFile("segments", ".jsonl");
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < 5_000; i++) {                         // envelopes and plain documents, with and without \r
            lines.add(i % 2 == 0 ? "{\"kind\":\"trade\",\"id\":\"X-" + i + "\",\"doc\":{\"n\":" + i + ",\"pad\":\"" + "x".repeat(i % 97) + "\"}}"
                    : "{\"id\":\"X-" + i + "\",\"n\":" + i + "}\r");
        }
        Files.write(f, lines, StandardCharsets.UTF_8);
        long before = JsonlDay.segmentBytes;
        JsonlDay.segmentBytes = 4_096;
        try {
            JsonlDay d = JsonlDay.index(f, null, List.of("n"), "id");
            assertThat(d.size()).isEqualTo(5_000);
            for (int i = 0; i < 5_000; i += 7) {
                String doc = new String(d.document("X-" + i).orElseThrow(), StandardCharsets.UTF_8);
                assertThat(new com.ash.drishti.common.JsonCodec().read(doc).get("n").asDouble()).isEqualTo(i);
            }
            assertThat(d.columns().numbers().get("n")).hasSize(5_000);
        } finally {
            JsonlDay.segmentBytes = before;
        }
    }
}
