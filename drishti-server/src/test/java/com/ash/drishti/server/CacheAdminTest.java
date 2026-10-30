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

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Admins see every cache and can purge any of them, or all, at any time; each purge is audited. */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.sources.connectors.finance-lake.settings.root=../plugins/drishti-plugin-delta/src/test/resources/lake",
        "drishti.sources.connectors.finance-lake.settings.domain=desk"})
@AutoConfigureMockMvc
class CacheAdminTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    @Test
    void adminsListAndPurgeCaches() throws Exception {
        String admin = "Bearer " + tokens.mint("ada", List.of("admin"), 300);
        mvc.perform(get("/api/v1/views/trade/T-1").header("Authorization", admin).header("X-Drishti-As-Of", "2026-09-29"));
        mvc.perform(get("/api/v1/admin/caches").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.caches[*].name").value(hasItem("engine")))
                .andExpect(jsonPath("$.caches[*].name").value(hasItem("finance-lake")))
                // 2.9.7 (O1): VaR, exposure, BI and pack access are listed too, with where they live, size and limit; totals above
                .andExpect(jsonPath("$.caches[*].name").value(hasItem("var.answers")))
                .andExpect(jsonPath("$.caches[*].name").value(hasItem("var.hot-files")))
                .andExpect(jsonPath("$.caches[*].name").value(hasItem("var.views")))
                .andExpect(jsonPath("$.caches[*].name").value(hasItem("exposure.trade-paths")))
                .andExpect(jsonPath("$.caches[*].name").value(hasItem("bi.layers")))
                .andExpect(jsonPath("$.caches[*].name").value(hasItem("security.pack-access")))
                .andExpect(jsonPath("$.caches[?(@.name == 'var.hot-files')].where").value(hasItem("disk")))
                .andExpect(jsonPath("$.memory.heapMaxBytes").isNumber())
                .andExpect(jsonPath("$.memory.budgetBytes").isNumber())
                .andExpect(jsonPath("$.disk.freeBytes").isNumber())
                .andExpect(jsonPath("$.disk.folders[*].name").value(hasItem("var/hot")));
        // O2: one date of a cache, on this server; the dates a cache holds
        mvc.perform(post("/api/v1/admin/caches/var.hot-files/purge?date=2026-09-29").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.purged").value(hasItem("var.hot-files"))).andExpect(jsonPath("$.date").value("2026-09-29"))
                .andExpect(jsonPath("$.scope").value("server"));
        mvc.perform(post("/api/v1/admin/caches/finance-lake/purge?date=2026-09-29").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.purged").value(hasItem("finance-lake")));
        mvc.perform(get("/api/v1/admin/caches").header("Authorization", admin))
                .andExpect(jsonPath("$.caches[?(@.name == 'var.hot-files')].lastPurge.by").value(hasItem("ada")))
                .andExpect(jsonPath("$.caches[?(@.name == 'var.hot-files')].lastPurge.date").value(hasItem("2026-09-29")));
        mvc.perform(get("/api/v1/admin/caches/var.hot-files/dates").header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/caches/nope/dates").header("Authorization", admin)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/caches/var.answers/purge?date=not-a-date").header("Authorization", admin))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/caches/var.answers/purge?scope=cluster").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("cluster")).andExpect(jsonPath("$.members").isEmpty());
        mvc.perform(post("/api/v1/admin/caches/finance-lake/purge").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.purged").value(hasItem("finance-lake")));
        mvc.perform(post("/api/v1/admin/caches/all/purge").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.purged").value(hasItem("engine")));
        mvc.perform(post("/api/v1/admin/caches/nope/purge").header("Authorization", admin))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-5004"));
        mvc.perform(get("/api/v1/admin/audit").header("Authorization", admin))
                .andExpect(jsonPath("$[*].action").value(hasItem("cache-purged")));
    }

    @Test
    void othersMayNotPurge() throws Exception {
        String viewer = "Bearer " + tokens.mint("vic", List.of("viewer"), 300);
        mvc.perform(post("/api/v1/admin/caches/all/purge").header("Authorization", viewer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/caches").header("Authorization", viewer)).andExpect(status().isForbidden());
    }
}
