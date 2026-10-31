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
package com.ash.drishti.server.bi.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The BI store's retention (2.9.7, RESOURCES_AND_SCALE.md section 7.2): at most {@code store-max-dates} dates a table, then the least
 * recently built dates beyond {@code store-max-gb}, never a table's newest; a removed date's generation moves on.
 */
class LayersRetentionTest {

    @TempDir
    Path store;

    private void partition(String layer, String key, String table, LocalDate d, int bytes, long builtAt) throws Exception {
        Path pd = Files.createDirectories(store.resolve(layer).resolve(key).resolve(table).resolve("date=" + d));
        Path data = pd.resolve("part.parquet");
        Files.write(data, new byte[bytes]);
        Files.writeString(pd.resolve("meta.json"), "{}");
        Files.setLastModifiedTime(data, FileTime.fromMillis(builtAt));
        Files.setLastModifiedTime(pd.resolve("meta.json"), FileTime.fromMillis(builtAt));
    }

    private List<String> dates(String layer, String key, String table) throws Exception {
        try (Stream<Path> s = Files.list(store.resolve(layer).resolve(key).resolve(table))) {
            return s.map(p -> p.getFileName().toString().substring(5)).sorted().toList();
        }
    }

    @Test
    void keepsTheNewestDatesAndStaysUnderTheCap() throws Exception {
        LocalDate d0 = LocalDate.of(2026, 9, 1);
        for (int i = 0; i < 10; i++) {
            partition("columns", "ds_a", "trades", d0.plusDays(i), 1000, 1_000_000L * (i + 1));
            partition("rollups", "ds_a", "by_desk", d0.plusDays(i), 100, 1_000_000L * (i + 1));
        }
        partition("columns", "ds_b", "positions", d0, 5000, 1);               // one old, large date: the table's newest, kept
        Files.createDirectories(store.resolve("live/ds_a/trades/date=live"));
        Layers layers = new Layers(null, null, QueryProperties.defaults(store.toString()), Clock.systemUTC());
        long before = layers.generation("ds_a", d0);

        Layers.Trimmed t = layers.trim(0, 6);
        assertThat(t.partitions()).isEqualTo(8);                             // four dates of each of the two tables of ds_a
        assertThat(dates("columns", "ds_a", "trades")).hasSize(6).startsWith(d0.plusDays(4).toString());
        assertThat(layers.generation("ds_a", d0)).isGreaterThan(before);

        t = layers.trim(5000 + 3 * 1000 + 6 * 100 + 6 * 2 * 3, 6);            // room for three trade dates (meta.json is 2 bytes)
        assertThat(dates("columns", "ds_a", "trades")).containsExactly(d0.plusDays(7).toString(), d0.plusDays(8).toString(),
                d0.plusDays(9).toString());
        assertThat(dates("columns", "ds_b", "positions")).containsExactly(d0.toString());
        assertThat(store.resolve("live/ds_a/trades/date=live")).exists();
        assertThat(t.keptBytes()).isLessThanOrEqualTo(5000 + 3 * 1000 + 6 * 100 + 36);
    }

    /** The per-date generations are bounded, and a date's generation made again never repeats a value it had (2.9.7). */
    @Test
    void generationsAreBoundedAndNeverRepeat() {
        Layers layers = new Layers(null, null, QueryProperties.defaults(store.toString()), Clock.systemUTC());
        LocalDate d0 = LocalDate.of(2000, 1, 1);
        long first = layers.generation("ds", d0);
        for (int i = 0; i < 250_000; i++) {
            layers.generation("ds" + (i % 50), d0.plusDays(i / 50));
        }
        assertThat(layers.generationsHeld()).isLessThanOrEqualTo(200_000 + 1_000);
        assertThat(new Layers(null, null, QueryProperties.defaults(store.toString()), Clock.systemUTC()).generation("ds", d0))
                .as("made again: a value never handed out").isNotEqualTo(first);
    }
}
