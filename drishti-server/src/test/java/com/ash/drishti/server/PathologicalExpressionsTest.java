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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.rachana.SutraRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GRAM-01..03: pathological Rachana-EL never stops the server, kills hot reload or fails a view. The QA reproductions:
 * a Sutra whose bind nests 3000 parentheses ({@code qa-deep}) and one whose bind is a 5000-term {@code +} chain
 * ({@code qa-chain}) sit in the site Sutra directory at start-up.
 */
class PathologicalExpressionsTest {

    static String deep(int depth) {
        return "(".repeat(depth) + "$.mtm" + ")".repeat(depth);
    }

    static String chain(int terms) {
        return String.join(" + ", Collections.nCopies(terms, "$.mtm"));
    }

    static String sutra(String name, String bind) {
        return "rachana: 1\nsutra: " + name + "\nversion: 1\nmatch: { kind: trade, where: \"$.tradeId == 'IRS-48213'\", priority: 1000 }\n"
                + "panels:\n  - { id: a, kind: kv, columns: [{label: x, bind: '" + bind + "'}] }\n  - { id: b, kind: kv, columns: [{label: id, bind: $.tradeId}] }\n";
    }

    static Path sutraDir(String prefix, Map<String, String> files) {
        try {
            Path dir = Files.createTempDirectory(prefix);
            for (var f : files.entrySet()) {
                Files.writeString(dir.resolve(f.getKey()), f.getValue());
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The default limits: both files are located problems at start-up; the view, alerts and search refuse cleanly. */
    @Nested
    @SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=finance",
            "drishti.identity.database-url=jdbc:sqlite:target/prefs-patho-${random.uuid}/identity.db"})
    @AutoConfigureMockMvc
    class DefaultLimits {

        static final Path DIR = sutraDir("drishti-patho-", Map.of("qa-deep.v1.sutra.yaml", sutra("qa-deep", deep(3000)),
                "qa-chain.v1.sutra.yaml", sutra("qa-chain", chain(5000))));

        @DynamicPropertySource
        static void dirs(DynamicPropertyRegistry r) {
            r.add("drishti.rachana.dirs", DIR::toString);
        }

        @Autowired MockMvc mvc;
        @Autowired SutraRegistry sutras;
        final ObjectMapper json = new ObjectMapper();

        @Test
        void theServerStartsAndListsBothFilesAsProblems() throws Exception {
            mvc.perform(get("/api/v1/sutras/problems")).andExpect(status().isOk())
                    // SEC-11: the keys are relative to the Sutra root
                    .andExpect(jsonPath("$['" + "qa-deep.v1.sutra.yaml" + "'][0].code").value("DRS-2101"))
                    .andExpect(jsonPath("$['" + "qa-deep.v1.sutra.yaml" + "'][0].message").value(containsString("nested deeper than 200")))
                    .andExpect(jsonPath("$['" + "qa-chain.v1.sutra.yaml" + "'][0].code").value("DRS-2101"));
            assertThat(sutras.latest("qa-deep")).isEmpty();
            assertThat(sutras.hotReload()).isIn("WATCHING", "POLLING");
        }

        @Test
        void theViewTheChainWouldHaveMatchedStillRenders() throws Exception {
            mvc.perform(get("/api/v1/views/trade/IRS-48213")).andExpect(status().isOk());
        }

        @Test
        void hotReloadKeepsWorkingAfterAPathologicalFileIsDroppedIn() throws Exception {
            Files.writeString(DIR.resolve("qa-deep2.v1.sutra.yaml"), sutra("qa-deep2", deep(5000)));
            Files.writeString(DIR.resolve("qa-probe.v1.sutra.yaml"), sutra("qa-probe", "$.tradeId"));
            long deadline = System.currentTimeMillis() + 15_000;
            while (sutras.latest("qa-probe").isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(sutras.latest("qa-probe")).isPresent();
            assertThat(sutras.problems()).containsKey(DIR.resolve("qa-deep2.v1.sutra.yaml").toString());
            assertThat(sutras.hotReload()).isIn("WATCHING", "POLLING");
        }

        @Test
        void studioPreviewAlertsSearchAndHistoryRefuseDeepExpressionsWithACode() throws Exception {
            mvc.perform(post("/api/v1/studio/preview").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("yaml", sutra("qa-chain", chain(5000)), "kind", "trade", "id", "IRS-48213"))))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.problems[*].code").value(hasItem("DRS-2101")));
            mvc.perform(put("/api/v1/me/alerts/rules/Deep").header("X-Drishti-User", "qa").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("kind", "trade", "id", "IRS-48213", "when", deep(3000) + " < 0", "severity", "warn"))))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-2101"));
            mvc.perform(put("/api/v1/me/alerts/rules/Long").header("X-Drishti-User", "qa").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("kind", "trade", "id", "IRS-48213", "when", chain(5000) + " < 0", "severity", "warn"))))
                    .andExpect(status().isUnprocessableEntity());
            mvc.perform(get("/api/v1/search").param("q", "TRD where " + "(".repeat(3000) + "mtm > 0" + ")".repeat(3000)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4004"));
            mvc.perform(get("/api/v1/search").param("q", "TRD where " + String.join(" or ", Collections.nCopies(3000, "mtm > 0"))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4004"));
            mvc.perform(get("/api/v1/history/trade/IRS-48213/series").param("path", deep(3000)))
                    .andExpect(status().isBadRequest());
        }
    }

    /**
     * With the limits lifted, a chain too deep to evaluate loads; the view still renders, the panel shows a DRS-2102
     * problem and the other panels their data. A file that overflows the parser is still only that file's problem.
     */
    @Nested
    @SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=finance",
            "drishti.rachana.max-expression-depth=10000000", "drishti.rachana.max-expression-length=100000000",
            "drishti.identity.database-url=jdbc:sqlite:target/prefs-patho2-${random.uuid}/identity.db"})
    @AutoConfigureMockMvc
    class LiftedLimits {

        static final Path DIR = sutraDir("drishti-patho2-", Map.of("qa-deep.v1.sutra.yaml", sutra("qa-deep", deep(200_000)),
                "qa-chain.v1.sutra.yaml", sutra("qa-chain", chain(200_000))));

        @DynamicPropertySource
        static void dirs(DynamicPropertyRegistry r) {
            r.add("drishti.rachana.dirs", DIR::toString);
        }

        @Autowired MockMvc mvc;
        @Autowired SutraRegistry sutras;

        @Test
        void anOverflowIsAPanelProblemOrAFileProblemNeverA500() throws Exception {
            assertThat(sutras.problems().get(DIR.resolve("qa-deep.v1.sutra.yaml").toString())).singleElement()
                    .satisfies(p -> assertThat(p.code()).isEqualTo("DRS-2032"));
            assertThat(sutras.latest("qa-chain")).isPresent();
            mvc.perform(get("/api/v1/views/trade/IRS-48213")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.panels[?(@.id=='a')].error").value(hasItem(containsString("DRS-2102"))))
                    .andExpect(jsonPath("$.panels[?(@.id=='b')].empty").value(hasItem(false)));
        }
    }
}
