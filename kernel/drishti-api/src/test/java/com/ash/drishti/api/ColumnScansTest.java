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
package com.ash.drishti.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The scan over a source's columns: days walked, filters applied, batches cut, a non-columnar path refused. */
class ColumnScansTest {

    /** Two days of three trades each; desk and notional are columns, tradeDate is not. */
    static final class Stub implements SourcePlugin {
        @Override
        public PluginManifest manifest() {
            return new PluginManifest("stub", "1", Set.of("trade"), new SourceCapabilities(false, false, false, true));
        }

        @Override
        public void start(SourceContext context) {}

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }

        @Override
        public Set<String> columnar(String kind) {
            return Set.of("desk", "notional");
        }

        @Override
        public Optional<ColumnSet> columns(String kind, Collection<String> paths, AsOf asOf) {
            LocalDate d = asOf.businessDate();
            if (d == null || d.isAfter(LocalDate.of(2026, 10, 2)) || d.isBefore(LocalDate.of(2026, 10, 1))) {
                return Optional.empty();
            }
            int base = d.getDayOfMonth() * 10;
            return Optional.of(new ColumnSet(new String[] {"T" + base, "T" + (base + 1), "T" + (base + 2)},
                    Map.of("notional", new double[] {100, 200, Double.NaN}), Map.of("desk", new String[] {"FX", "FI", "FX"}), d));
        }
    }

    private static List<ColumnBatch> drain(ArrowScan scan) throws Exception {
        List<ColumnBatch> out = new ArrayList<>();
        for (ColumnBatch b = scan.next(); b != null; b = scan.next()) {
            out.add(b);
        }
        return out;
    }

    @Test
    void walksTheDaysOfTheRangeAndSkipsDaysTheSourceDoesNotHold() throws Exception {
        ScanRequest r = new ScanRequest("trade", List.of("desk", "notional"), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 3));
        List<ColumnBatch> batches = drain(ColumnScans.over(new Stub(), r).orElseThrow());
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0).businessDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(batches.get(1).ids()).containsExactly("T20", "T21", "T22");
        assertThat(batches.get(0).value(1, 2)).isNull();                  // NaN is an absent number
        assertThat(batches.get(0).value(0, 1)).isEqualTo("FI");
    }

    @Test
    void appliesFiltersAndCutsBatches() throws Exception {
        ScanRequest r = new ScanRequest("trade", List.of("desk", "notional"),
                List.of(new ScanRequest.Filter("desk", ScanRequest.Op.EQ, List.of("FX"))), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), 1);
        List<ColumnBatch> batches = drain(ColumnScans.over(new Stub(), r).orElseThrow());
        assertThat(batches).hasSize(2);                                   // two FX trades, one row per batch
        assertThat(batches).allSatisfy(b -> assertThat(b.value(0, 0)).isEqualTo("FX"));
        ScanRequest range = new ScanRequest("trade", List.of("desk", "notional"),
                List.of(new ScanRequest.Filter("notional", ScanRequest.Op.GE, List.of(150))), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), 10);
        assertThat(drain(ColumnScans.over(new Stub(), range).orElseThrow()).get(0).ids()).containsExactly("T11");
    }

    @Test
    void refusesAPathThatIsNotAColumn() {
        ScanRequest r = new ScanRequest("trade", List.of("desk", "tradeDate"), LocalDate.of(2026, 10, 1), null);
        assertThat(ColumnScans.over(new Stub(), r)).isEmpty();
    }
}
