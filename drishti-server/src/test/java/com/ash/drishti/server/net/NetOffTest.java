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
package com.ash.drishti.server.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * With no config/net/ folder a server is a single server exactly as before (DRISHTI_NET.md section 3): no gossip thread, no
 * peers.json, no forwarded request, and the cluster's endpoints answer that the cluster is off.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.net.dir=target/net-off-${random.uuid}/config-net"})
@AutoConfigureMockMvc
class NetOffTest {

    @Autowired MockMvc mvc;
    @Autowired NetService net;

    @Test
    void withoutTheFolderNothingOfTheClusterRuns() throws Exception {
        assertThat(net.running()).isFalse();
        assertThat(net.node()).isEmpty();
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> t.getName().equals("drishti-net-gossip"));
        assertThat(Files.exists(Path.of(net.folder().dir().toString()))).isFalse();
        assertThat(Files.exists(net.peersFile())).isFalse();
        mvc.perform(post("/api/v1/net/gossip").contentType("application/json").content("{\"members\":[]}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRS-5050"));
        mvc.perform(get("/api/v1/net/ping")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/net/members")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.members").isEmpty());
        mvc.perform(post("/api/v1/net/drain")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-5050"));
        // an ordinary request is served as always
        String view = mvc.perform(get("/api/v1/views/trade/IRS-48213")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(view).as("no node is named in How this view was built, even to an administrator").contains("How this view was built")
                .doesNotContain("\"label\":\"" + NetServedBy.LABEL + "\"");
    }
}
