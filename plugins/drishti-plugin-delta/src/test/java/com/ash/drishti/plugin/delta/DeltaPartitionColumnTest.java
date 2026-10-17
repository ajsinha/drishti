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
package com.ash.drishti.plugin.delta;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A promoted column that is also a partition column ({@code lake build --partition-by PRODUCT_TYPE}: folders
 * {@code business_date=.../PRODUCT_TYPE=Cash/}) is not in the Parquet files; its value comes from the table's log, so search
 * columns, drop-downs and pivots see it (src/test/resources/lake-partitioned: six trades, three product types).
 */
class DeltaPartitionColumnTest {

    @Test
    void aPartitionColumnReadsItsValueFromTheLog() throws Exception {
        DeltaSourcePlugin p = new DeltaSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("root", Path.of("src/test/resources/lake-partitioned").toAbsolutePath().toString(), "domain", "desk",
                "source-name", "lake", "layout.trade.columns", "PRODUCT_TYPE,CURRENCY")));
        ColumnSet c = p.columns("trade", List.of("PRODUCT_TYPE", "CURRENCY"), AsOf.of(LocalDate.parse("2026-09-30"))).orElseThrow();
        assertThat(c.size()).isEqualTo(6);
        Map<String, String> type = new HashMap<>();
        Map<String, String> currency = new HashMap<>();
        for (int i = 0; i < c.size(); i++) {
            type.put(c.ids()[i], (String) c.value("PRODUCT_TYPE", i));
            currency.put(c.ids()[i], (String) c.value("CURRENCY", i));
        }
        assertThat(type).containsEntry("T1", "ComSwap").containsEntry("T2", "ComFut").containsEntry("T3", "Cash").containsEntry("T6", "Cash");
        assertThat(currency).containsEntry("T1", "EUR").containsEntry("T2", "USD");
    }
}
