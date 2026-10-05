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
package com.ash.drishti.server.explain;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** With no configuration at all, Ask is off: DRS-4007, the box is not offered, and labels-only is the default for values. */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=market-risk",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true",
        "drishti.identity.database-url=jdbc:sqlite:target/askoff-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class AskOffByDefaultTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired AskProperties props;

    @Test
    void askIsOffUntilAdministratorsTurnItOn() throws Exception {
        assertThat(props.enabled()).isFalse();
        assertThat(props.packs()).isEmpty();
        assertThat(props.valuesShown()).isFalse();                       // labels-only by default
        String auth = "Bearer " + tokens.mint("ann", List.of("full"), 300);
        mvc.perform(post("/api/v1/views/var/VAR-COMM/ask").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hi\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-4007"));
        mvc.perform(get("/api/v1/views/var/VAR-COMM/explain").header("Authorization", auth)).andExpect(jsonPath("$.ask.enabled").value(false));
    }
}
