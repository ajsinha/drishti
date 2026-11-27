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
package com.ash.drishti.server.feeds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.TestRun;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
 * Admin, Data loads, Feeds, "Run now" over HTTP: the feed's script is started as the administrator asked (a stand-in {@code tools/drishti.py} in a
 * temporary installation writes what it was asked to a file), the run is audited, a run in progress is not started twice, and an unknown feed, a
 * name that is not a feed's and a person who is not an administrator are refused with their codes.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.identity.iterations=1000",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
class DataFeedsRunApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    static Path install;
    static Path data;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        install = TestRun.temp("feeds-install");
        data = Files.createDirectories(install.resolve("data"));
        Files.createDirectories(install.resolve("config/feeds"));
        Files.writeString(install.resolve("config/feeds/demo.yaml"), "title: Demo feed\n");
        Files.createDirectories(install.resolve("tools"));
        Files.writeString(install.resolve("tools/drishti.py"),
                "import os, sys\nout = os.path.join(os.environ['DRISHTI_DATA_DIR'], 'ran-' + sys.argv[3] + '.txt')\n"
                        + "with open(out + '.part', 'w') as f:\n    f.write(' '.join(sys.argv[1:]))\nos.replace(out + '.part', out)\n");
        r.add("drishti.feeds.config", () -> install.resolve("config/feeds").toString());
        r.add("drishti.feeds.root", install::toString);
        r.add("drishti.data.dir", data::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;

    private String as(String user, String role) {
        return "Bearer " + tokens.mint(user, List.of(role), 300);
    }

    @Test
    void runNowStartsTheFeedsScriptOnceAuditsItAndRefusesWhatIsNotAFeed() throws Exception {
        String ada = as("ada", "admin");
        mvc.perform(post("/api/v1/admin/feeds/demo/run").header("Authorization", as("tia", "trader"))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DRS-5002"));
        assertThat(data.resolve("ran-demo.txt")).as("a refusal started nothing").doesNotExist();
        JsonNode started = JSON.readTree(mvc.perform(post("/api/v1/admin/feeds/demo/run").header("Authorization", ada)).andExpect(status().isOk())
                .andExpect(jsonPath("$.started").value(true)).andExpect(jsonPath("$.feed").value("demo")).andExpect(jsonPath("$.by").value("ada"))
                .andReturn().getResponse().getContentAsString());
        assertThat(started.path("pid").asLong()).isPositive();
        Path ran = data.resolve("ran-demo.txt");
        long until = System.currentTimeMillis() + 20_000;
        while (!Files.exists(ran) && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertThat(ran).as("the script ran").exists();
        assertThat(Files.readString(ran)).startsWith("feed run demo --trigger admin --config");
        assertThat(Files.readString(data.resolve("datafeeds/demo/admin-run.log"))).contains("Run now by ada");
        assertThat(mvc.perform(get("/api/v1/admin/audit").header("Authorization", ada)).andReturn().getResponse().getContentAsString()).contains("feed.run", "feed/demo");
        // a run in progress (its lock) is not started a second time, and says so
        Files.writeString(data.resolve("datafeeds/demo/run.lock"), "running");
        Files.delete(ran);
        mvc.perform(post("/api/v1/admin/feeds/demo/run").header("Authorization", ada)).andExpect(status().isOk()).andExpect(jsonPath("$.started").value(false))
                .andExpect(jsonPath("$.skipped").value(org.hamcrest.Matchers.containsString("in progress")));
        Thread.sleep(500);
        assertThat(ran).as("nothing started while the lock is held").doesNotExist();
        // unknown feeds, and names that are not a feed's name
        mvc.perform(post("/api/v1/admin/feeds/nope/run").header("Authorization", ada)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-1001"));
        mvc.perform(post("/api/v1/admin/feeds/Not-A-Feed/run").header("Authorization", ada)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(startsWith("DRS-")));
    }
}
