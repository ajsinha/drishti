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
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

/**
 * QA3B-03 / QA4-01 as QA ran it: a lake written by the Iceberg loader with the native engine (several domains and tables, ten business
 * dates each), its folder moved, served through connector files: one present at start, one added while the server runs. The badge and
 * Health say DEGRADED with the relocate to copy, the connector's Test says the same and why its kinds list nothing, a read answers the
 * "names files that are not there" 502, and the log has one line per read, never a stack trace.
 *
 * <p>The earlier test passed on JDK 21 while QA's server (JDK 25) stayed UP: there {@code CompletableFuture.join()} wraps the plugin's quiet
 * failure in a new {@code CancellationException("join")}, so the failure never reached the table problems. Run this on both JDKs.
 */
// its own connector folder, written before the context starts (connector files are read while the environment is prepared)
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=core,counterparty-risk",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors-dir=" + IcebergMovedConnectorFileTest.CONNECTORS,
        "drishti.sources.plugins.file.enabled=false"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class IcebergMovedConnectorFileTest {

    static final String CONNECTORS = "target/test-data/iceberg-moved-connectors";
    private static final List<String> CREDIT = List.of("credit-limit", "cva", "exposure-profile", "netting-set", "sa-ccr");
    private static final List<String> MARKET = List.of("bond", "fx-rate");
    private static Path base;

    @Autowired MockMvc mvc;

    static {
        try {
            base = Files.createTempDirectory("drishti-iceberg-moved-file");
            Path lines = base.resolve("rows.jsonl");
            StringBuilder rows = new StringBuilder();
            int dates = 0;
            for (LocalDate d = LocalDate.of(2026, 9, 17); dates < 10; d = d.plusDays(1)) {
                if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    continue;
                }
                dates++;
                for (String kind : CREDIT) {
                    rows(rows, "credit", kind, d);
                }
                for (String kind : MARKET) {
                    rows(rows, "market", kind, d);
                }
            }
            Files.writeString(lines, rows);
            // the loader load-iceberg.sh runs, with the options QA gave it
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
            // as on QA's server: the pack's own credit store is switched off, so reads of the kind reach Iceberg
            Files.writeString(connectors.resolve("credit-store.yaml"), "plugin: delta\nenabled: false\nkinds: [" + String.join(", ", CREDIT)
                    + "]\nsettings:\n  root: " + base.resolve("delta") + "\n  domain: credit\n");
            Files.writeString(connectors.resolve("qa-ice-moved.yaml"), "plugin: iceberg\nkinds: [credit-limit]\nsettings: {root: " + base.resolve("ice2")
                    + ", domain: credit, engine: native}\n");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void rows(StringBuilder out, String domain, String kind, LocalDate d) {
        String[] names = {"ALDERSHOT", "AURORA", "BRAMLEY", "CALDER", "DUNMORE", "ELGIN", "FARNHAM", "GARSTON", "HARLOW", "ILKLEY", "JARROW", "KESWICK"};
        String prefix = kind.substring(0, 3).toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < names.length; i++) {
            String id = (kind.equals("credit-limit") ? "LIM" : prefix) + "-" + names[i];
            out.append("{\"domain\":\"").append(domain).append("\",\"kind\":\"").append(kind).append("\",\"id\":\"").append(id)
                    .append("\",\"date\":\"").append(d).append("\",\"doc\":\"{\\\"ID\\\":\\\"").append(id).append("\\\",\\\"LIMIT\\\":")
                    .append(1000 * (i + 1) + d.getDayOfMonth()).append("}\",\"columns\":{}}\n");
        }
    }

    private String connectors() throws Exception {
        return mvc.perform(get("/api/v1/admin/connectors")).andReturn().getResponse().getContentAsString();
    }

    private void awaitRunning(String name) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;                     // the folder's watcher starts the file's connector
        while (!connectors().contains("\"name\":\"" + name + "\",\"origin\":\"file\",\"plugin\":\"iceberg\"")
                || !connectors().matches("(?s).*\"name\":\"" + name + "\".{0,300}\"state\":\"RUNNING\".*")) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(name + " did not start: " + connectors());
            }
            Thread.sleep(200);
        }
    }

    @Test
    void aMovedLakeFromTheLoaderIsDegradedEverywhereAndEachReadSaysWhyInOneLogLine(CapturedOutput out) throws Exception {
        // present at start
        awaitRunning("qa-ice-moved");
        String pair = "relocate: '" + base.resolve("ice1") + " -> " + base.resolve("ice2") + "'";
        String sentence = "DEGRADED: cannot read " + String.join(", ", CREDIT) + ": their metadata names files under the folder they were written in";
        mvc.perform(get("/api/v1/admin/connectors")).andExpect(status().isOk())
                .andExpect(jsonPath("$.connectors[?(@.name == 'qa-ice-moved')].level").value("warn"))
                .andExpect(jsonPath("$.connectors[?(@.name == 'qa-ice-moved')].label").value("degraded"))
                .andExpect(jsonPath("$.connectors[?(@.name == 'qa-ice-moved')].health").value(hasItem(allOf(startsWith(sentence), containsString(pair)))));
        mvc.perform(get("/api/v1/admin/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[?(@.name == 'qa-ice-moved')].status").value("DEGRADED"));
        // the connector's Test: not ok, the same health, and the kind says why it lists nothing
        mvc.perform(post("/api/v1/admin/connectors/qa-ice-moved/test")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.health").value(allOf(startsWith(sentence), containsString(pair))))
                .andExpect(jsonPath("$.kinds[0].note").value(startsWith("cannot read: the table credit-limit names files that are not there")));
        for (int i = 0; i < 2; i++) {
            int before = out.getAll().length();
            mvc.perform(get("/api/v1/entities/credit-limit/LIM-ALDERSHOT/raw").param("asOf", "2026-09-30"))
                    .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("DRS-1003"))
                    .andExpect(jsonPath("$.detail").value(containsString("credit-limit names files that are not there (it was moved")))
                    .andExpect(jsonPath("$.detail").value(not(containsString(base.toString()))));
            String logged = out.getAll().substring(before);
            assertThat(logged).contains("request failed: DRS-1003 qa-ice-moved failed reading credit-limit/LIM-ALDERSHOT: the table credit-limit names files");
            assertThat(logged).as("no stack trace").doesNotContain("\tat ").doesNotContain("CancellationException").doesNotContain("NoSuchFileException");
        }

        // added while the server runs, with a relocate in the Windows form that matches nothing here (QA3B-04)
        Files.writeString(Path.of(CONNECTORS).resolve("qa-ice-hot.yaml"), "plugin: iceberg\nkinds: [" + String.join(", ", MARKET) + "]\nsettings:\n  root: "
                + base.resolve("ice2") + "\n  domain: market\n  engine: native\n  relocate: 'C:\\Users\\qa\\ice1 -> D:\\lake\\ice2'\n");
        awaitRunning("qa-ice-hot");
        mvc.perform(get("/api/v1/admin/connectors")).andExpect(status().isOk())
                .andExpect(jsonPath("$.connectors[?(@.name == 'qa-ice-hot')].label").value("degraded"))
                .andExpect(jsonPath("$.connectors[?(@.name == 'qa-ice-hot')].health").value(hasItem(allOf(
                        startsWith("DEGRADED: cannot read bond, fx-rate: their metadata names files"), containsString(pair),
                        containsString("(relocate C:/Users/qa/ice1 -> D:/lake/ice2 is set, but no old folder in it holds " + base.resolve("ice1") + ")")))));
        mvc.perform(post("/api/v1/admin/connectors/qa-ice-hot/test")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.health").value(containsString("is set, but no old folder in it holds")));
        int before = out.getAll().length();
        mvc.perform(get("/api/v1/entities/bond/BON-ALDERSHOT/raw").param("asOf", "2026-09-30"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value(containsString("the table bond names files that are not there")));
        assertThat(out.getAll().substring(before)).as("no stack trace").doesNotContain("\tat ");
    }
}
