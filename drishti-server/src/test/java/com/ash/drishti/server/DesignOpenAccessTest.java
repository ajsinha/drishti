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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Designing and trying are open to every signed-in user; saving, proposing and data access are not. A role that may open only
 * trades and netting sets (no author right, no right to {@code sample} or {@code curve}) can preview a pasted document and use
 * the Screen Builder, but not save, not preview a stored curve, and sees "no access" (and none of the curve's values) in every
 * panel whose {@code source} is a curve: in a stored view, in a stored and a pasted preview, and in the pivot tab's records.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.rachana.studio-save=true",
        "drishti.security.roles.designer.kinds[0]=trade", "drishti.security.roles.designer.kinds[1]=netting-set",
        "drishti.identity.database-url=jdbc:sqlite:target/design-open-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class DesignOpenAccessTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SOURCED = """
            rachana: 1
            sutra: ns-sourced
            version: 1
            match: { kind: netting-set, priority: 100 }
            title: { pill: NS, id: $.nettingSetId }
            strip:
              - { label: Trades, bind: $.trades }
            panels:
              - id: srcline
                kind: line
                title: Curve line
                source: "link('USD-SOFR', 'curve')"
                rows: points
                x: tenor
                y: rate
              - id: srctable
                kind: table
                title: Curve table
                source: "link('USD-SOFR', 'curve')"
                rows: $.points
                pivot: { fields: [tenor, rate], rows: [tenor], values: [{ field: rate, agg: sum }] }
                columns:
                  - { label: Tenor, bind: "@.tenor" }
                  - { label: Rate, bind: "@.rate" }
              - id: srcpivot
                kind: pivot
                title: Curve pivot
                source: "link('USD-SOFR', 'curve')"
                rows: $.points
                by: tenor
                across: rate
                value: rate
            """;
    private static final Path DIR = sutraDir();
    private static final String SAMPLE = "{\"samples\":[{\"name\":\"a.json\",\"document\":{\"tradeId\":\"T1\",\"notional\":1000}}]}";

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry r) {
        r.add("drishti.rachana.dirs", DIR::toString);
    }

    private static Path sutraDir() {
        try {
            Path d = Files.createTempDirectory("drishti-design-open-");
            Files.writeString(d.resolve("ns-sourced.v1.sutra.yaml"), SOURCED);
            return d;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    private static String preview(String yaml, String kind, String doc) throws Exception {
        var b = JSON.createObjectNode();
        b.put("yaml", yaml);
        b.put("kind", kind);
        b.put("id", "X-1");
        b.set("document", JSON.readTree(doc));
        return b.toString();
    }

    private static JsonNode json(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    void aUserWithoutAuthorOrSampleRightsCanPreviewAPastedDocumentAndUseTheBuilder() throws Exception {
        String me = as("dee", "designer");
        mvc.perform(post("/api/v1/studio/preview").header("Authorization", me).contentType(MediaType.APPLICATION_JSON)
                .content(preview(SOURCED, "sample",
                        "{\"tradeId\":\"T1\"}"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/studio/inferred").header("Authorization", me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"sample\",\"id\":\"X-1\",\"document\":{\"tradeId\":\"T1\",\"mtm\":5}}")).andExpect(status().isOk());
        for (String url : new String[] {"/api/v1/builder/shape", "/api/v1/builder/design"}) {
            mvc.perform(post(url).header("Authorization", me).contentType(MediaType.APPLICATION_JSON).content(SAMPLE)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/builder/suggest").header("Authorization", me).contentType(MediaType.APPLICATION_JSON)
                .content(SAMPLE.substring(0, SAMPLE.length() - 1) + ",\"path\":\"$.notional\"}")).andExpect(status().isOk());
    }

    @Test
    void theSameUserStillCannotSaveProposeOrPreviewAStoredEntityOfAKindTheyMayNotOpen() throws Exception {
        String me = as("dee", "designer");
        mvc.perform(post("/api/v1/sutras").header("Authorization", me).contentType("text/yaml").content(SOURCED))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/studio/settings").header("Authorization", me)).andExpect(jsonPath("$.save").value(false))
                .andExpect(jsonPath("$.approve").value(false));
        mvc.perform(get("/api/v1/studio/settings").header("Authorization", as("ann", "author"))).andExpect(jsonPath("$.save").value(true));
        var stored = JSON.createObjectNode();
        stored.put("yaml", SOURCED);
        stored.put("kind", "curve");
        stored.put("id", "USD-SOFR");
        mvc.perform(post("/api/v1/studio/preview").header("Authorization", me).contentType(MediaType.APPLICATION_JSON).content(stored.toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRS-5002"));
    }

    private static void assertNoAccess(JsonNode view) {
        assertThat(view.path("panels")).hasSize(3);
        for (JsonNode p : view.path("panels")) {
            String id = p.path("id").asText();
            assertThat(p.path("denied").asText()).as(id).isEqualTo("no access to curve");
            assertThat(p.path("error").asText("")).as(id).isEmpty();
            assertThat(p.path("data").isNull() || p.path("data").isMissingNode()).as(id).isTrue();
            assertThat(p.path("empty").asBoolean()).as(id).isTrue();
        }
        assertThat(view.toString()).doesNotContain("3.93", "3.54", "USD-SOFR");
    }

    @Test
    void aStoredViewSourcedFromAKindTheUserMayNotOpenShowsNoAccessAndNoValues() throws Exception {
        String path = "/api/v1/views/netting-set/NS-NORTH-01";
        assertNoAccess(json(mvc.perform(get(path).header("Authorization", as("dee", "designer"))).andExpect(status().isOk())));
        JsonNode risk = json(mvc.perform(get(path).header("Authorization", as("rita", "risk"))).andExpect(status().isOk()));
        assertThat(risk.toString()).contains("3.93");
        for (JsonNode p : risk.path("panels")) {
            assertThat(p.path("denied").isNull() || p.path("denied").isMissingNode()).as(p.path("id").asText()).isTrue();
        }
    }

    @Test
    void theStoredPreviewAndAPastedPreviewShowNoAccessToo() throws Exception {
        var stored = JSON.createObjectNode();
        stored.put("yaml", SOURCED);
        stored.put("kind", "netting-set");
        stored.put("id", "NS-NORTH-01");
        assertNoAccess(json(mvc.perform(post("/api/v1/studio/preview").header("Authorization", as("dee", "designer"))
                .contentType(MediaType.APPLICATION_JSON).content(stored.toString())).andExpect(status().isOk())));
        String pasted = preview(SOURCED, "sample", "{\"nettingSetId\":\"X-1\",\"trades\":2}");
        assertNoAccess(json(mvc.perform(post("/api/v1/studio/preview").header("Authorization", as("dee", "designer"))
                .contentType(MediaType.APPLICATION_JSON).content(pasted)).andExpect(status().isOk())));
        JsonNode risk = json(mvc.perform(post("/api/v1/studio/preview").header("Authorization", as("rita", "risk"))
                .contentType(MediaType.APPLICATION_JSON).content(pasted)).andExpect(status().isOk()));
        assertThat(risk.toString()).contains("3.93");
    }

    @Test
    void thePivotTabsRecordsAreRefusedForASourceTheUserMayNotOpen() throws Exception {
        String path = "/api/v1/views/netting-set/NS-NORTH-01/panels/srctable/records";
        mvc.perform(get(path).header("Authorization", as("dee", "designer"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRS-5002"));
        assertThat(mvc.perform(get(path).header("Authorization", as("rita", "risk"))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).contains("3.93");
    }
}
