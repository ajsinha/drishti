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
package com.ash.drishti.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The store loaders' feed by the load rules (docs/architecture/DATA_ARCHITECTURE.md section 10): the business date from outside (--as-of, .ready,
 * the name, a tar's folder), never a row's; a row's own date counted when it differs; a plain file is one table.
 */
class RowFeedTest {

    private static final LocalDate D29 = LocalDate.of(2026, 9, 29);
    private static final LocalDate D30 = LocalDate.of(2026, 9, 30);

    @TempDir Path dir;

    /** Every line's business date, filing each by its table (the text before its comma) and the date {@code own} it carries. */
    private static List<LocalDate> drain(RowFeed f, LocalDate own) throws IOException {
        List<LocalDate> out = new ArrayList<>();
        for (String line = f.next(); line != null; line = f.next()) {
            if (!line.isBlank()) {
                out.add(f.place(line.split(",")[0], own));
            }
        }
        return out;
    }

    @Test
    void aDatedNameFilesEveryRowUnderItsDateAndCountsTheRowsThatSayAnother() throws IOException {
        Path f = Files.writeString(dir.resolve("trade.2026-09-30.jsonl"), "trade,1\ntrade,2\n");
        try (RowFeed feed = RowFeed.open(f.toString(), null)) {
            assertThat(drain(feed, D29)).containsExactly(D30, D30);
            assertThat(feed.notes()).hasSize(2);
            assertThat(feed.notes().get(0)).startsWith("trade.2026-09-30.jsonl: business date 2026-09-30");
            assertThat(feed.notes().get(1)).contains("2 rows").contains("2026-09-30");
        }
    }

    @Test
    void asOfWinsOverTheNameAndRowsOfTheSameDateAreNotCounted() throws IOException {
        Path f = Files.writeString(dir.resolve("trade.2026-09-30.jsonl"), "trade,1\n");
        try (RowFeed feed = RowFeed.open(f.toString(), D29)) {
            assertThat(drain(feed, D29)).containsExactly(D29);
            assertThat(feed.notes()).hasSize(1);
        }
    }

    @Test
    void aPlainFileOfTwoTablesIsRefusedSendATar() throws IOException {
        Path f = Files.writeString(dir.resolve("rows.2026-09-30.jsonl"), "trade,1\nbook,2\n");
        try (RowFeed feed = RowFeed.open(f.toString(), null)) {
            assertThatThrownBy(() -> drain(feed, null)).hasMessageContaining("a file is one table and one business date")
                    .hasMessageContaining("send a tar");
        }
    }

    @Test
    void aTarFilesEachRowUnderItsFolderAndRefusesAsOfAndAStrayKind() throws IOException {
        Path t = tar(dir.resolve("rows.tar"), "trade/2026-09-29/rows.jsonl", "trade,1\n", "book/2026-09-30/rows.jsonl", "book,2\nbook,3\n");
        try (RowFeed feed = RowFeed.open(t.toString(), null)) {
            assertThat(drain(feed, null)).containsExactlyInAnyOrder(D29, D30, D30);
            assertThat(feed.notes()).containsExactly("rows.tar: business dates 2026-09-29 to 2026-09-30 (2 dates) from the archive's folders");
        }
        assertThatThrownBy(() -> RowFeed.open(t.toString(), D30)).hasMessageContaining("--as-of is refused");
        Path bad = tar(dir.resolve("bad.tar"), "trade/2026-09-29/rows.jsonl", "book,1\n");
        try (RowFeed feed = RowFeed.open(bad.toString(), null)) {
            assertThatThrownBy(() -> drain(feed, null)).hasMessageContaining("a row of book in folder trade/");
        }
    }

    @Test
    void aStreamIsOneTableUnderTheDateGiven() throws IOException {
        try (RowFeed feed = RowFeed.stream(new StringReader("trade,1\ntrade,2\n"), D29)) {
            assertThat(drain(feed, D29)).containsExactly(D29, D29);
            assertThat(feed.notes()).hasSize(1);
            assertThat(feed.notes().get(0)).startsWith("the stream: business date 2026-09-29");
        }
        assertThat(RowFeed.asOf(new String[] {"in.tar", "--as-of", "2026-09-30"})).isEqualTo(D30);
        assertThat(RowFeed.asOf(new String[] {"in.tar"})).isNull();
    }

    private static Path tar(Path f, String... nameAndText) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < nameAndText.length; i += 2) {
            byte[] data = nameAndText[i + 1].getBytes(StandardCharsets.UTF_8);
            byte[] h = new byte[512];
            put(h, 0, nameAndText[i]);
            put(h, 100, "0000644");
            put(h, 108, "0000000");
            put(h, 116, "0000000");
            put(h, 124, String.format("%011o", data.length));
            put(h, 136, "00000000000");
            h[156] = '0';
            put(h, 257, "ustar");
            put(h, 263, "00");
            java.util.Arrays.fill(h, 148, 156, (byte) ' ');
            long sum = 0;
            for (byte b : h) {
                sum += b & 0xff;
            }
            put(h, 148, String.format("%06o", sum));
            out.write(h);
            out.write(data);
            out.write(new byte[(512 - data.length % 512) % 512]);
        }
        out.write(new byte[1024]);
        return Files.write(f, out.toByteArray());
    }

    private static void put(byte[] h, int at, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, at, b.length);
    }
}
