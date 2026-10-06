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
package com.ash.drishti.server.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.server.security.TokenVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Embedding is off unless {@code drishti.embed.enabled}: no token endpoint answers, an embed-shaped token is refused. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=trading", "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long"})
@AutoConfigureMockMvc
class EmbedOffByDefaultTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired EmbedKeys keys;

    @Test
    void noTokensAreMadeAndNoEmbedTokenIsAccepted() throws Exception {
        var r = mvc.perform(post("/api/v1/embed/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", EmbedTokenService.GRANT)).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(403);
        assertThat(r.getContentAsString()).contains("DRS-8003");
        String forged = keys.sign(EmbedTokenService.TYP, java.util.Map.of("sub", "x", "azp", "x", "exp", System.currentTimeMillis() / 1000 + 300));
        var v = mvc.perform(get("/api/v1/views/trade/MX-20000001").header("Authorization", "Bearer " + forged)).andReturn().getResponse();
        assertThat(v.getStatus()).isEqualTo(401);
        assertThat(v.getContentAsString()).contains("DRS-8001");
        var origins = mvc.perform(get("/api/v1/embed/apps/origins").header("Authorization", "Bearer " + tokens.mint("console", List.of("service"), 60)))
                .andReturn().getResponse();
        assertThat(origins.getContentAsString()).contains("\"origins\":[]");
    }
}
