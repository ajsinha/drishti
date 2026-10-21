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
package com.ash.drishti.server.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.identity.content.FileContentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The seed chips of {@code config/ui/chips} (UI_BOARD.md 9.4): one per panel kind, BI visual type and trading pack block, and the
 * composites; each passes the publish checks (the grammar's reading, a complete manual, every out pin driven, a pure body) and every
 * manual example reads as a board fragment whose chip uses all resolve in the store.
 */
class SeedChipsTest {

    @Test
    void everySeedChipPassesItsOwnChecksAndExamples() throws Exception {
        Path root = Files.createDirectories(Path.of("target", "ui-store-tests", "seeds-" + UUID.randomUUID()));
        ChipStore store = new ChipStore(new FileContentStore(root), ChipStoreContract.ACCESS, Path.of("../config/ui/chips"));
        Map<String, TreeMap<Integer, ChipStore.Version>> seeds = store.seeds();
        int versions = seeds.values().stream().mapToInt(Map::size).sum();
        assertThat(versions).isEqualTo(68);
        assertThat(seeds.keySet().stream().filter(n -> n.startsWith("panel-")).count()).isEqualTo(22);
        assertThat(seeds.keySet().stream().filter(n -> n.startsWith("bi-")).count()).isEqualTo(34);
        assertThat(seeds.keySet()).contains("deal-ticket", "legs", "flow-schedule", "events", "payments", "deal-footer", "entity-card",
                "filterable-table", "cashflow-ladder", "search-box", "date-range");
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, TreeMap<Integer, ChipStore.Version>> e : seeds.entrySet()) {
            for (ChipStore.Version v : e.getValue().values()) {
                String at = e.getKey() + "@" + v.state().version();
                UiDocs.publishProblems(v.doc()).forEach(p -> problems.add(at + ": " + p));
                for (Object ex : UiDocs.list(UiDocs.map(v.doc().get("manual")).get("examples"))) {
                    Map<String, Object> board = UiDocs.read(String.valueOf(UiDocs.map(ex).get("board")));
                    UiDocs.uses(board).values().stream().filter(u -> store.find(com.ash.drishti.rachana.ui.ChipRef.parse(u).orElseThrow()).isEmpty())
                            .forEach(u -> problems.add(at + ": example uses " + u + ", not in the store"));
                }
            }
        }
        assertThat(problems).as(problems.stream().collect(Collectors.joining("\n"))).isEmpty();
        assertThat(UiDocs.incompatibilities(seeds.get("filterable-table").get(1).doc(), seeds.get("filterable-table").get(2).doc())).isEmpty();
    }
}
