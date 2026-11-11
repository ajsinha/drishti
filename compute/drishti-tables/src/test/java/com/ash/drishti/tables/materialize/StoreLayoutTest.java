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
package com.ash.drishti.tables.materialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.yaml.TableYaml;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Where on-load tables keep their files, what a retention drops, and a live table's bounds (docs/architecture/ARCHITECTURE.md §28 section 3.4, Q14). */
class StoreLayoutTest {

    @TempDir Path store;

    static TableDef table(String materialize, String version) {
        return TableYaml.read("rachana: 3\ntable: trading.trade_legs\nversion: " + version + "\nfrom: { kind: trade }\nkeys: [ID2]\ncolumns: { ID2: $.ID2 }\nmaterialize: "
                + materialize + "\n").table();
    }

    @Test
    void aPartitionPerTableMajorAndDate() {
        StoreLayout l = new StoreLayout(store);
        TableDef t = table("on-load", "2.1.0");
        assertThat(l.partition(t, LocalDate.of(2026, 10, 9))).isEqualTo(store.resolve("trading/trade_legs/v2/2026-10-09"));
        assertThat(l.newFile(t, LocalDate.of(2026, 10, 9)).getFileName().toString()).startsWith("part-").endsWith(".parquet");
        assertThat(l.newFile(t, LocalDate.of(2026, 10, 9))).isNotEqualTo(l.newFile(t, LocalDate.of(2026, 10, 9)));
    }

    @Test
    void retentionByCountOrByDays() {
        List<LocalDate> held = List.of(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 9, 1));
        LocalDate today = LocalDate.of(2026, 10, 9);
        assertThat(StoreLayout.expired(held, null, today)).isEmpty();
        assertThat(StoreLayout.expired(held, "2", today)).containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 7));
        assertThat(StoreLayout.expired(held, "10", today)).isEmpty();
        assertThat(StoreLayout.expired(held, "30d", today)).containsExactly(LocalDate.of(2026, 9, 1));
        assertThatThrownBy(() -> StoreLayout.expired(held, "soon", today)).hasMessageContaining("keep is");
    }

    @Test
    void aLiveTablesBounds() {
        Materialization.LiveBounds b = Materialization.LiveBounds.of(table("{ live: { rows: 5000, age: 2h } }", "1.0.0"), 100_000);
        assertThat(b.rows()).isEqualTo(5000);
        assertThat(b.age()).isEqualTo(Duration.ofHours(2));
        assertThat(Materialization.LiveBounds.of(table("{ live: {} }", "1.0.0"), 100_000).rows()).isEqualTo(100_000);
        assertThat(Materialization.LiveBounds.age("PT30M")).isEqualTo(Duration.ofMinutes(30));
        assertThat(Materialization.mode(table("on-load", "1.0.0"))).isEqualTo("on-load");
    }
}
