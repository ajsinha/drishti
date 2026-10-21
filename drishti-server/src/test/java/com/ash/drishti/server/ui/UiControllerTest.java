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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.content.FileContentStore;
import com.ash.drishti.server.api.ApiExceptionHandler;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The P2 routes of {@code /api/v1/ui} over a filesystem store: listing, manuals, board save with ETags, conflicts and the lifecycle codes. */
class UiControllerTest {

    MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Files.createDirectories(Path.of("target", "ui-store-tests", "mvc-" + UUID.randomUUID()));
        FileContentStore store = new FileContentStore(root);
        UiSettings settings = UiSettings.defaults(Path.of("../config/ui/chips"));
        ChipStore chips = new ChipStore(store, ChipStoreContract.ACCESS, settings.chipsDir());
        mvc = MockMvcBuilders.standaloneSetup(new UiController(chips, new BoardStore(store, chips, ChipStoreContract.ACCESS, settings)))
                .setControllerAdvice(advice()).build();
    }

    /** The API's own exception handler (its constructor is package-private). */
    static ApiExceptionHandler advice() throws Exception {
        var c = ApiExceptionHandler.class.getDeclaredConstructor(com.ash.drishti.rachana.SutraRegistry.class);
        c.setAccessible(true);
        return c.newInstance((Object) null);
    }

    static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String user) {
        return b.requestAttr(Principal.ATTRIBUTE, new Principal(user, List.of()));
    }

    @Test
    void routes() throws Exception {
        mvc.perform(as(get("/api/v1/ui/chips").param("category", "controls"), "dave")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chip").value("date-range")).andExpect(jsonPath("$[1].chip").value("search-box"));
        mvc.perform(as(get("/api/v1/ui/chips/filterable-table"), "dave")).andExpect(jsonPath("$.versions.length()").value(2));
        mvc.perform(as(get("/api/v1/ui/chips/filterable-table/2/manual"), "dave")).andExpect(status().isOk())
                .andExpect(jsonPath("$.manual.purpose").exists()).andExpect(jsonPath("$.state").value("certified"));
        mvc.perform(as(get("/api/v1/ui/chips/nope/1"), "dave")).andExpect(status().isNotFound());
        String board = ChipStoreContract.board("filterable-table@2").replace("rows.value: PNL", "rows.label: PNL");
        String etag = mvc.perform(as(put("/api/v1/ui/boards/book-pnl").contentType(MediaType.TEXT_PLAIN).content(board), "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.document.owner").value("alice")).andReturn().getResponse().getHeader("ETag");
        mvc.perform(as(put("/api/v1/ui/boards/book-pnl").contentType(MediaType.TEXT_PLAIN).content(board), "alice")).andExpect(status().isConflict());
        mvc.perform(as(put("/api/v1/ui/boards/book-pnl").header("If-Match", etag).contentType(MediaType.TEXT_PLAIN).content(board), "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.document.version").value(2));
        mvc.perform(as(get("/api/v1/ui/boards/book-pnl"), "alice")).andExpect(header().exists("ETag"));
        mvc.perform(as(get("/api/v1/ui/boards/book-pnl").param("owner", "alice"), "dave")).andExpect(status().isNotFound());
        mvc.perform(as(put("/api/v1/ui/boards/book-pnl/share").contentType(MediaType.APPLICATION_JSON).content("{\"people\":[\"dave\"]}"), "alice"))
                .andExpect(jsonPath("$.sharedWith[0]").value("dave"));
        mvc.perform(as(get("/api/v1/ui/boards/book-pnl").param("owner", "alice"), "dave")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/ui/chips/entity-card/where-used"), "alice")).andExpect(jsonPath("$.versions[0].boards[0]").value("user:alice:book-pnl"));
        mvc.perform(as(get("/api/v1/ui/boards/book-pnl/revisions"), "alice")).andExpect(jsonPath("$.revisions[0]").value(1));
        mvc.perform(as(post("/api/v1/ui/boards/check").contentType(MediaType.TEXT_PLAIN).content(board.replace("filterable-table@2", "nope@1")), "alice"))
                .andExpect(jsonPath("$.ok").value(false));
        mvc.perform(as(put("/api/v1/ui/chips/pnl-strip/draft").contentType(MediaType.TEXT_PLAIN).content(ChipStoreContract.chipYaml(1, "", "")), "alice"))
                .andExpect(status().isOk());
        mvc.perform(as(post("/api/v1/ui/chips/pnl-strip/1/publish"), "alice")).andExpect(status().isNotFound());
        mvc.perform(as(post("/api/v1/ui/chips/pnl-strip/1/submit"), "alice")).andExpect(jsonPath("$.state").value("in-review"));
        mvc.perform(as(post("/api/v1/ui/chips/pnl-strip/1/publish"), "alice")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRS-2078"));
        mvc.perform(as(post("/api/v1/ui/chips/pnl-strip/1/approve"), "alice")).andExpect(status().isForbidden());
        mvc.perform(as(delete("/api/v1/ui/boards/book-pnl"), "alice")).andExpect(status().isBadRequest());
    }
}
