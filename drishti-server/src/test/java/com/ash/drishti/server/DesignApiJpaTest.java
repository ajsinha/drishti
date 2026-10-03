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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.design.DesignStore;
import com.ash.drishti.identity.design.JpaDesignStore;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** {@code drishti.builder.designs.store=jpa}: the same API on the identity database. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.identity.database-url=jdbc:sqlite:target/design-jpa-${random.uuid}/identity.db", "drishti.builder.designs.store=jpa",
        "drishti.builder.designs.dir=target/design-jpa-must-stay-unused"})
@AutoConfigureMockMvc
class DesignApiJpaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired DesignStore store;

    @Test
    void designsAreKeptInTheDatabaseAndOnlyForTheirOwner() throws Exception {
        assertThat(store).isInstanceOf(JpaDesignStore.class);
        String ann = "Bearer " + tokens.mint("ann", List.of("author"), 300);
        String bob = "Bearer " + tokens.mint("bob", List.of("author"), 300);
        JsonNode d = JSON.readTree(mvc.perform(post("/api/v1/builder/designs").header("Authorization", ann).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Db design\"}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String url = "/api/v1/builder/designs/" + d.path("id").asText();
        mvc.perform(post(url + "/samples").header("Authorization", ann).contentType(MediaType.APPLICATION_JSON)
                .content("{\"samples\":[{\"name\":\"a.json\",\"document\":{\"x\":1}}]}")).andExpect(status().isOk());
        assertThat(mvc.perform(get(url + "/samples/document?name=a.json").header("Authorization", ann)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).isEqualTo("{\"x\":1}");
        mvc.perform(get(url).header("Authorization", bob)).andExpect(status().isNotFound());
        assertThat(java.nio.file.Files.exists(java.nio.file.Path.of("target/design-jpa-must-stay-unused"))).isFalse();
        mvc.perform(delete(url).header("Authorization", ann)).andExpect(status().isNoContent());
        assertThat(store.sample("ann", d.path("id").asText(), "a.json")).isEmpty();
    }
}
