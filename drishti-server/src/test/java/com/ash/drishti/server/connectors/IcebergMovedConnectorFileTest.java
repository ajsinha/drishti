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
package com.ash.drishti.server.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

/**
 * QA3B-03 as QA ran it: a domain written by the Iceberg loader with the native engine, its folder moved, served through a connector file.
 * The connector reported DEGRADED, but Admin → Connectors showed it as "running" (level ok), so the page said UP and hid the fix; and each
 * read answered 502 with a full stack trace in the log. Now the badge is "degraded" with the health (the relocate to copy) beside it, the
 * 502 says the table was moved, and the log has one line per failed read.
 */
// its own connector folder, written before the context starts (connector files are read while the environment is prepared)
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance", "drishti.sources.plugins.demo.enabled=false",
        "drishti.sources.connectors-dir=" + IcebergMovedConnectorFileTest.CONNECTORS,
        "drishti.sources.plugins.file.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class IcebergMovedConnectorFileTest {

    private static Path base;

    @Autowired MockMvc mvc;

    static final String CONNECTORS = "target/test-data/iceberg-moved-connectors";

    static {
        try {
            base = Files.createTempDirectory("drishti-iceberg-moved-file");
            Path lines = base.resolve("rows.jsonl");
            Files.writeString(lines, "{\"domain\":\"credit\",\"kind\":\"credit-limit\",\"id\":\"LIM-ALDERSHOT\",\"date\":\"2026-09-30\",\"doc\":\"{\\\"limit\\\":5}\",\"columns\":{}}\n"
                    + "{\"domain\":\"credit\",\"kind\":\"credit-limit\",\"id\":\"LIM-BRAMLEY\",\"date\":\"2026-09-30\",\"doc\":\"{\\\"limit\\\":7}\",\"columns\":{}}\n");
            com.ash.drishti.plugin.iceberg.IcebergLoader.main(new String[] {lines.toString(), base.resolve("ice1").toString(), "--engine", "native",
                "--spill-dir", base.resolve("spill").toString()});
            Files.move(base.resolve("ice1"), base.resolve("ice2"));
            Path connectors = Path.of(CONNECTORS);
            if (Files.isDirectory(connectors)) {
                try (var old = Files.list(connectors)) {
                    for (Path f : old.toList()) {
                        Files.deleteIfExists(f);
                    }
                }
            }
            Files.createDirectories(connectors);
            Files.writeString(connectors.resolve("qa-ice-moved.yaml"), "plugin: iceberg\nkinds: [credit-limit]\nsettings:\n  root: " + base.resolve("ice2")
                    + "\n  domain: credit\n  engine: native\n");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Test
    void aMovedTableIsDegradedOnTheConnectorsPageAndTheReadSaysWhyInOneLogLine(CapturedOutput out) throws Exception {
        String pair = "relocate: '" + base.resolve("ice1") + " -> " + base.resolve("ice2") + "'";
        long deadline = System.currentTimeMillis() + 30_000;                     // the folder's watcher starts the file's connector
        while (!mvc.perform(get("/api/v1/admin/connectors")).andReturn().getResponse().getContentAsString().contains("\"name\":\"qa-ice-moved\",\"origin\":\"file\",\"plugin\":\"iceberg\",\"kinds\":[\"credit-limit\"],\"description\":\"\",\"enabled\":true,\"state\":\"RUNNING\"")
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        mvc.perform(get("/api/v1/admin/connectors")).andExpect(status().isOk())
                .andExpect(jsonPath("$.connectors[?(@.name == 'qa-ice-moved')].level").value("warn"))
                .andExpect(jsonPath("$.connectors[?(@.name == 'qa-ice-moved')].label").value("degraded"))
                .andExpect(jsonPath("$.connectors[?(@.name == 'qa-ice-moved')].health").value(org.hamcrest.Matchers.hasItem(
                        org.hamcrest.Matchers.allOf(startsWith("DEGRADED: cannot read credit-limit"), containsString(pair)))));
        mvc.perform(get("/api/v1/admin/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[?(@.name == 'qa-ice-moved')].status").value("DEGRADED"));
        int before = out.getAll().length();
        mvc.perform(get("/api/v1/entities/credit-limit/LIM-ALDERSHOT/raw").header("X-Drishti-As-Of", "2026-09-30"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("DRS-1003"))
                .andExpect(jsonPath("$.detail").value(containsString("credit-limit names files that are not there (it was moved")))
                .andExpect(jsonPath("$.detail").value(not(containsString(base.toString()))));
        String logged = out.getAll().substring(before);
        assertThat(logged).contains("request failed: DRS-1003 qa-ice-moved failed reading credit-limit/LIM-ALDERSHOT");
        assertThat(logged).as("no stack trace").doesNotContain("\tat ").doesNotContain("FileNotFoundException");
    }
}
