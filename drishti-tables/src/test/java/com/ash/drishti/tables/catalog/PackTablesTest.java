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
package com.ash.drishti.tables.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.tables.Problem;
import com.ash.drishti.tables.def.TableDef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A pack's kinds as tables (BI_FOUNDATION_2911E.md 3.6): regenerated, never migrated; a table per kind and per list the pack names. */
class PackTablesTest {

    @Test
    void aKindAndItsListsAreTablesThatPassTheChecks() {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("TRADE_ID", "string");
        columns.put("DESK", "string");
        columns.put("NOTIONAL", "decimal");
        Map<String, String> legs = new LinkedHashMap<>();
        legs.put("LEG_ID", "integer");
        legs.put("CURRENCY", "string");
        List<TableDef> t = PackTables.generate(new PackTables.KindInfo("trading", "trade", "2", columns, Map.of("DESK", "desk"), Map.of(), false,
                Map.of("LEGS", legs)));
        assertThat(t).extracting(TableDef::name).containsExactly("trading.trade", "trading.trade_legs");
        assertThat(t.get(0).was()).isEqualTo("pack.trading.trade");
        assertThat(t.get(0).columns().get("DESK").references()).isEqualTo("desk");
        assertThat(t.get(1).grain()).isEqualTo("$.LEGS[*]");
        assertThat(t.get(1).columns().get("CURRENCY").path()).isEqualTo("@.CURRENCY");
        assertThat(Catalogue.of(t, List.of()).check()).filteredOn(Problem::isError).isEmpty();
        List<TableDef> live = PackTables.generate(new PackTables.KindInfo("market", "quote", "1.2.0", Map.of("PRICE", "decimal"), null, null, true, null));
        assertThat(live.get(0).materialize().live()).isTrue();
        assertThat(Catalogue.of(live, List.of()).check()).filteredOn(Problem::isError).isEmpty();
    }
}
