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
package com.ash.drishti.api.resolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The one join of a table's location: a lake's root, domain and folder, as the plugins and the loaders have always met them. */
class TableCatalogueTest {

    @Test
    void anObjectStoreLocationIsJoinedAsWrittenWithNoTrailingSlash() {
        assertEquals("s3://bank-lake/curated/trading/trade_murex", TableCatalogue.locate("s3://bank-lake/curated/", "trading", "trade_murex"));
        assertEquals("mem://feeds/news/headline", TableCatalogue.locate("mem://feeds", "news", "headline"));
    }

    @Test
    void aLocalLocationIsAbsoluteAndNormalised() {
        assertEquals(Path.of("/data/delta/risklake/risk/pnl").toAbsolutePath().toString(), TableCatalogue.locate("/data/delta/risklake/", "risk", "pnl"));
        assertEquals(Path.of("/data/x").toAbsolutePath().toString(), TableCatalogue.locate("/data/a/..", "x", ""));
        assertEquals(Path.of("/data/feeds/news").toAbsolutePath().toString(), TableCatalogue.locateLocal("/data/feeds", "news").toString());
    }

    @Test
    void joinKeepsWhatItIsGivenAndABlankDomainPutsTheFolderUnderTheRoot() {
        assertEquals("file:/data/risk-results/var-official", TableCatalogue.join("file:/data/", "risk-results", "var-official"));
        assertEquals("/r/t", TableCatalogue.join("/r", "", "t"));
    }

    @Test
    void aConnectorsExpandedSettingsGiveTheTableAndSayHowItWasFound() {
        TableCatalogue.Resolved r = TableCatalogue.of("trading.trade_murex", "trade",
                Map.of("root", "s3://fo-data", "domain", "mx", "table.trade", "trades_v7"), "delta", "trade@1", true);
        assertEquals("s3://fo-data/mx/trades_v7", r.uri());
        assertEquals("configured", r.how());
        assertEquals("trade@1", r.contract());
        assertNull(TableCatalogue.of("x.y", "y", Map.of(), "iceberg", null, false));
        assertEquals("risk/marts", TableCatalogue.namespaceFolder("risk.marts").replace('.', '/'));
    }
}
