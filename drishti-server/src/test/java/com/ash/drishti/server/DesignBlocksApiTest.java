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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * The Build designer on a pack Sutra that uses shared blocks: {@code GET /builder/designs/{id}/blocks} lists each use with the panels it
 * brings (what the canvas groups), {@code GET /rachana/blocks[/name/version]} reads a block for the read-only view, and
 * {@code POST /builder/designs/{id}/samples {pack: true}} adds the first record of the pack's own samples the Sutra's match holds for,
 * so a Design of a pack Sutra draws without the author bringing a sample.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.designer.kinds[0]=trade",
        "drishti.identity.database-url=jdbc:sqlite:target/design-blocks-${random.uuid}/identity.db",
        "drishti.builder.designs.dir=target/design-blocks-files-${random.uuid}"})
@AutoConfigureMockMvc
class DesignBlocksApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path PACK = pack();

    @DynamicPropertySource
    static void dirs(DynamicPropertyRegistry r) {
        r.add("drishti.rachana.dirs", () -> PACK.resolve("sutras").toString());
    }

    /** A pack folder as the shipped ones are laid out: sutras/ with a common/ block, samples/<kind>.jsonl with its catalogue. */
    private static Path pack() {
        try {
            Path p = Files.createTempDirectory("drishti-design-blocks-");
            Files.createDirectories(p.resolve("sutras/common"));
            Files.createDirectories(p.resolve("sutras/blk"));
            Files.createDirectories(p.resolve("samples"));
            Files.writeString(p.resolve("sutras/common/blk-legs.v1.block.yaml"), """
                    rachana: 1
                    block: blk-legs
                    version: 1
                    description: The legs side by side.
                    params: { rate-fmt: price4 }
                    panels:
                      - { id: blk-legs, kind: kv, title: Legs, columns: [ { label: Rate, bind: $.RATE, fmt: "%{rate-fmt}" } ] }
                      - { id: blk-flows, kind: kv, title: Cash flows, columns: [ { label: Id, bind: $.TRADE_ID } ] }
                    """);
            Files.writeString(p.resolve("sutras/blk/blk-deal.v1.sutra.yaml"), """
                    rachana: 1
                    sutra: blk-deal
                    version: 1
                    match: { kind: trade, where: "$.SOURCE_SYSTEM == 'BLKSYS' && $.PRODUCT_TYPE == 'SWAP'", priority: 900 }
                    title: { id: $.TRADE_ID }
                    panels:
                      - { use: blk-legs@1, with: { rate-fmt: pct4 } }
                      - { id: notes, kind: markdown, text: hi }
                    """);
            Files.writeString(p.resolve("samples/trade.jsonl"), """
                    {"TRADE_ID":"BK-1","SOURCE_SYSTEM":"BLKSYS","PRODUCT_TYPE":"FUTURE","RATE":0.01}
                    {"TRADE_ID":"BK-2","SOURCE_SYSTEM":"BLKSYS","PRODUCT_TYPE":"SWAP","RATE":0.02}
                    {"TRADE_ID":"BK-3","SOURCE_SYSTEM":"BLKSYS","PRODUCT_TYPE":"SWAP","RATE":0.03}
                    """);
            Files.writeString(p.resolve("samples/catalog.json"),
                    "[{\"kind\":\"trade\",\"id\":\"BK-1\"},{\"kind\":\"trade\",\"id\":\"BK-2\"},{\"kind\":\"trade\",\"id\":\"BK-3\"}]");
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user) {
        return "Bearer " + tokens.mint(user, List.of("designer"), 300);
    }

    private ResultActions call(String method, String url, String who, String body) throws Exception {
        var b = "GET".equals(method) ? get(java.net.URI.create(url)) : post(java.net.URI.create(url));
        b.header("Authorization", who);
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(b);
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return JSON.readTree(r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void aDesignOfAPackSutraGetsThePacksFirstMatchingRecordAsItsSampleAndDrawsTheBlocksPanels() throws Exception {
        String who = as("blocks-author");
        String id = ok(call("POST", "/api/v1/builder/designs", who, "{\"kind\":\"trade\",\"base\":\"blk-deal@1\"}")).path("id").asText();
        assertThat(ok(call("GET", "/api/v1/builder/designs/" + id, who, null)).path("samples")).isEmpty();

        JsonNode d = ok(call("POST", "/api/v1/builder/designs/" + id + "/samples", who, "{\"pack\":true}"));
        List<String> names = new ArrayList<>();
        d.path("samples").forEach(s -> names.add(s.path("name").asText()));
        assertThat(names).containsExactly("pack sample BK-2");                 // BK-1 is a FUTURE: the match does not hold
        JsonNode again = ok(call("POST", "/api/v1/builder/designs/" + id + "/samples", who, "{\"pack\":true}"));
        assertThat(again.path("samples")).hasSize(1);                           // asked twice: still one

        JsonNode view = ok(call("GET", "/api/v1/builder/designs/" + id + "/preview", who, null));
        List<String> panels = new ArrayList<>();
        view.path("panels").forEach(p -> panels.add(p.path("id").asText()));
        assertThat(panels).contains("blk-legs", "blk-flows", "notes");

        JsonNode uses = ok(call("GET", "/api/v1/builder/designs/" + id + "/blocks", who, null)).path("uses");
        assertThat(uses).hasSize(1);
        JsonNode u = uses.get(0);
        assertThat(u.path("ref").asText()).isEqualTo("blk-legs@1");
        assertThat(u.path("item").asInt()).isZero();
        assertThat(u.path("description").asText()).isEqualTo("The legs side by side.");
        assertThat(u.path("params").path("rate-fmt").asText()).isEqualTo("price4");
        assertThat(u.path("with").path("rate-fmt").asText()).isEqualTo("pct4");
        assertThat(u.path("source").asText()).endsWith("blk-legs.v1.block.yaml").doesNotStartWith("/");
        assertThat(u.path("panels").get(0).path("id").asText()).isEqualTo("blk-legs");
        assertThat(u.path("panels")).hasSize(2);
        assertThat(u.path("problems")).isEmpty();
    }

    @Test
    void aBlockIsReadForTheReadOnlyViewAndAnUnknownOneIsNotFound() throws Exception {
        String who = as("blocks-reader");
        JsonNode list = ok(call("GET", "/api/v1/rachana/blocks", who, null)).path("blocks");
        List<String> refs = new ArrayList<>();
        list.forEach(b -> refs.add(b.path("ref").asText()));
        assertThat(refs).contains("blk-legs@1");
        JsonNode b = ok(call("GET", "/api/v1/rachana/blocks/blk-legs/1", who, null));
        assertThat(b.path("text").asText()).contains("block: blk-legs").contains("%{rate-fmt}");
        assertThat(b.path("params").path("rate-fmt").asText()).isEqualTo("price4");
        call("GET", "/api/v1/rachana/blocks/blk-legs/9", who, null).andExpect(status().isNotFound());
    }

    @Test
    void aDesignWithNoBaseHasNoPackSample() throws Exception {
        String who = as("blocks-scratch");
        String id = ok(call("POST", "/api/v1/builder/designs", who, "{\"kind\":\"trade\"}")).path("id").asText();
        call("POST", "/api/v1/builder/designs/" + id + "/samples", who, "{\"pack\":true}").andExpect(status().isNotFound());
    }
}
