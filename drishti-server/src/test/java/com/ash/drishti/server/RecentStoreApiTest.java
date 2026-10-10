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
package com.ash.drishti.server;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A site's recent store (JSON-lines files) in front of the lake (here another file connector), as QA ran it on
 * 2026-10-01: the recent store is authoritative for the dates it holds (DATA-12), a read "as known at" an instant is
 * refused by stores without time travel (DATA-15), and an id with a {@code /} opens with the id in the query (DATA-21).
 */
// its own connector folder (read before the test environment is final): files other test contexts generate into the shared one, such as trading-store from the trading pack, would merge their settings into these connectors
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false",
        "drishti.sources.connectors-dir=target/test-data/recent-store-connectors", "drishti.packs.enabled=finance", "drishti.sources.plugins.demo.enabled=false",
        "drishti.sources.plugins.file.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.recent-files.plugin=file", "drishti.sources.connectors.recent-files.kinds[0]=trade",
        "drishti.sources.connectors.recent-files.settings.rescan-seconds=3600",
        "drishti.sources.connectors.trading-store.plugin=file", "drishti.sources.connectors.trading-store.kinds[0]=trade",
        "drishti.sources.connectors.trading-store.settings.rescan-seconds=3600",
        "drishti.sources.routes.trade=recent-files"})
@AutoConfigureMockMvc
class RecentStoreApiTest {

    private static final String KNOWN = "2020-01-01T00:00:00Z";

    @Autowired MockMvc mvc;

    private static void write(Path file, String... lines) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
    }

    private static String trade(String id, long mtm) {
        return "{\"id\":\"" + id + "\",\"tradeId\":\"" + id + "\",\"mtm\":" + mtm + "}";
    }

    @DynamicPropertySource
    static void stores(DynamicPropertyRegistry r) throws Exception {
        Path root = Files.createTempDirectory("drishti-recent-store");
        // the recent store's 09-29 book is the lake's minus MX-30000006 (deleted there); 09-01 is only in the lake
        write(root.resolve("recent/2026-09-29/trade.jsonl"), trade("MX-30000001", 11), trade("MX-30000002", 12), trade("sl/ash-6", 13));
        write(root.resolve("lake/2026-09-29/trade.jsonl"), trade("MX-30000001", 21), trade("MX-30000002", 22), trade("MX-30000006", 26));
        write(root.resolve("lake/2026-09-01/trade.jsonl"), trade("MX-30000006", 6));
        r.add("drishti.sources.connectors.recent-files.settings.root", () -> root.resolve("recent").toString());
        r.add("drishti.sources.connectors.trading-store.settings.root", () -> root.resolve("lake").toString());
    }

    /** DATA-12: the entity the recent store dropped is not brought back from the lake; the view and search agree. */
    @Test
    void anEntityTheRecentStoreDroppedIsNotHeldOnItsDates() throws Exception {
        mvc.perform(get("/api/v1/entities/trade/MX-30000001/raw").header("X-Drishti-As-Of", "2026-09-29"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.provenance.source").value("recent-files"))
                .andExpect(jsonPath("$.data.mtm").value(11));
        mvc.perform(get("/api/v1/entities/trade/MX-30000006/raw").header("X-Drishti-As-Of", "2026-09-29"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-1001"))
                .andExpect(jsonPath("$.detail").value(containsString("recent-files")));
        mvc.perform(get("/api/v1/search").param("q", "TRD MX-30000006").header("X-Drishti-As-Of", "2026-09-29"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(0)).andExpect(jsonPath("$.partial").value(false));
        // a date the recent store does not hold still comes from the lake
        mvc.perform(get("/api/v1/entities/trade/MX-30000006/raw").header("X-Drishti-As-Of", "2026-09-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.provenance.source").value("trading-store"))
                .andExpect(jsonPath("$.data.mtm").value(6));
    }

    /** DATA-15: knownAt on a store without time travel is a clear error naming it, never today's data. */
    @Test
    void knownAtOnAStoreWithoutTimeTravelIsRefusedNamingIt() throws Exception {
        mvc.perform(get("/api/v1/entities/trade/MX-30000001/raw").header("X-Drishti-As-Of", "2026-09-29").param("knownAt", KNOWN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-1007"))
                .andExpect(jsonPath("$.detail").value(containsString("recent-files")))
                .andExpect(jsonPath("$.detail").value(containsString("known at")));
        mvc.perform(get("/api/v1/views/trade/MX-30000006").header("X-Drishti-As-Of", "2026-09-01").param("knownAt", KNOWN))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-1007"))
                .andExpect(jsonPath("$.detail").value(containsString("trading-store")));
        mvc.perform(get("/api/v1/search").param("q", "TRD where mtm > 0").header("X-Drishti-As-Of", "2026-09-29").param("knownAt", KNOWN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched").value(0)).andExpect(jsonPath("$.partial").value(true))
                .andExpect(jsonPath("$.failed[0].source").value("recent-files"))
                .andExpect(jsonPath("$.failed[0].reason").value(containsString("known at")));
    }

    /** DATA-21: an id with a "/" opens with "~" in its place and the id in the query; encoded in the path it is refused. */
    @Test
    void anIdWithASlashOpensWithTheIdInTheQuery() throws Exception {
        mvc.perform(get("/api/v1/entities/trade/~/raw").param("id", "sl/ash-6").header("X-Drishti-As-Of", "2026-09-29"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ref.id").value("sl/ash-6")).andExpect(jsonPath("$.data.mtm").value(13));
        mvc.perform(get("/api/v1/views/trade/~").param("id", "sl/ash-6").header("X-Drishti-As-Of", "2026-09-29"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ref.id").value("sl/ash-6"));
        mvc.perform(get("/api/v1/history/trade/~/series").param("id", "sl/ash-6").param("path", "mtm").param("days", "3")
                        .header("X-Drishti-As-Of", "2026-09-29"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/impact/trade/~").param("id", "sl/ash-6").header("X-Drishti-As-Of", "2026-09-29"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/notes/trade/~").param("id", "sl/ash-6")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/entities/trade/MX-30000001/raw").param("id", "sl/ash-6").header("X-Drishti-As-Of", "2026-09-29"))
                .andExpect(jsonPath("$.ref.id").value("MX-30000001"));                // only "~" takes the id from the query
        mvc.perform(get(java.net.URI.create("/api/v1/entities/trade/sl%2Fash-6/raw")).header("X-Drishti-As-Of", "2026-09-29"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-5001"));   // SEC-02 holds
    }
}
