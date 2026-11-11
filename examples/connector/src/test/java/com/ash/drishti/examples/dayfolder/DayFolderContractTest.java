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
package com.ash.drishti.examples.dayfolder;

import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.testkit.DatedSourceContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;

/** The whole dated contract (dates, snapshot and effective kinds, reverse lookups, search) over a folder of day files. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DayFolderContractTest extends DatedSourceContract {

    private SourcePlugin plugin;

    @Override
    protected synchronized SourcePlugin plugin() throws Exception {
        if (plugin == null) {
            Path folder = Files.createTempDirectory("dayfolder-contract");
            // group the contract's rows into <kind>/<date>.jsonl, one line per entity
            Map<Path, List<String>> files = new LinkedHashMap<>();
            for (Row r : ROWS) {
                files.computeIfAbsent(folder.resolve(r.kind()).resolve(r.date() + ".jsonl"), k -> new ArrayList<>()).add(r.json());
            }
            for (var e : files.entrySet()) {
                Files.createDirectories(e.getKey().getParent());
                Files.write(e.getKey(), e.getValue());
            }
            DayFolderSourcePlugin p = new DayFolderSourcePlugin();
            p.start(context(Map.of(
                    "root", folder.toString(),
                    "id-field.trade", "tradeId",
                    "mode.counterparty", "effective",
                    "link.trade.netting-set", "nettingSet",
                    "rescan-seconds", "0")));
            plugin = p;
        }
        return plugin;
    }

    @AfterAll
    void close() {
        if (plugin != null) {
            plugin.close();
        }
    }
}
