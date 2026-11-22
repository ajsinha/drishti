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
package com.ash.drishti.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The pack lifecycle on the server as installed (docs/architecture/LIFECYCLE.md section 3), with the VaR proof: the market-risk pack's VaR
 * mnemonic ({@code var-result}, {@code VRES BANK}) answers the bank's 1-day 99% VaR 167,372,692 and ES 210,131,972 (250 scenarios, USD,
 * 2026-09-30); suspended, the same request is {@code 503 DRS-1037} naming the pack; resumed, it answers again, bit for bit as before (the
 * whole scenario P&L vector compared exactly, not only the rounded headline): nothing was reloaded or recomputed differently. A resume of a
 * running pack is {@code 409 DRS-1036}; every transition is in the audit.
 */
class PackagedPackLifecycleTest {

    static final Path FOLDER = PackagedVarGoldenTest.FOLDER;
    static final String VAR = "/api/v1/entities/var-result/BANK/raw";

    static PackagedServer server;

    @BeforeAll
    static void start() throws Exception {
        Path lake = FOLDER.resolve("lake");
        PackagedServer.buildLake(lake, PackagedVarGoldenTest.ONLY);
        Path work = FOLDER.resolve("server-lifecycle");
        deleteTree(work);
        // the 250-day window of the headline (the golden test asks for the whole set): the default window, so the variable is removed
        server = PackagedServer.start(work, lake, "2026-09-30", Map.of("DRISHTI_VAR_SCENARIO_WINDOW", ""));
    }

    @AfterAll
    static void stop() throws IOException {
        if (server != null) {
            server.close();
        }
        deleteTree(FOLDER.resolve("server-lifecycle"));
    }

    @Test
    void suspendThenResumeAnswersTheSameVarBitForBit() throws Exception {
        PackagedServer.Answer before = server.send("GET", VAR);
        assertThat(before.status()).as(before.text()).isEqualTo(200);
        JsonNode record = before.json();
        assertThat(Math.round(record.findValue("VAR").asDouble())).as("headline VaR").isEqualTo(167_372_692L);
        assertThat(Math.round(record.findValue("ES").asDouble())).as("headline ES").isEqualTo(210_131_972L);
        JsonNode pnl = record.findValue("PNL");
        assertThat(pnl).as("the scope's scenario P&L vector").isNotNull();
        assertThat(pnl.size()).as("one P&L per scenario").isPositive();

        PackagedServer.Answer suspend = server.send("POST", "/api/v1/admin/packs/market-risk/lifecycle/suspend");
        assertThat(suspend.status()).as(suspend.text()).isEqualTo(200);
        assertThat(suspend.json().path("state").asText()).isEqualTo("SUSPENDED");

        PackagedServer.Answer during = server.send("GET", VAR);
        assertThat(during.status()).as(during.text()).isEqualTo(503);
        assertThat(during.json().path("code").asText()).isEqualTo("DRS-1037");
        assertThat(during.json().path("detail").asText()).contains("market-risk is suspended");
        assertThat(server.send("GET", "/api/v1/admin/packs/market-risk/health").json().path("status").asText()).isEqualTo("SUSPENDED");

        PackagedServer.Answer again = server.send("POST", "/api/v1/admin/packs/market-risk/lifecycle/suspend");
        assertThat(again.status()).as("suspend of a suspended pack").isEqualTo(409);
        assertThat(again.json().path("code").asText()).isEqualTo("DRS-1036");

        PackagedServer.Answer resume = server.send("POST", "/api/v1/admin/packs/market-risk/lifecycle/resume");
        assertThat(resume.status()).as(resume.text()).isEqualTo(200);
        assertThat(resume.json().path("state").asText()).isEqualTo("RUNNING");

        PackagedServer.Answer after = server.send("GET", VAR);
        assertThat(after.status()).as(after.text()).isEqualTo(200);
        assertThat(Math.round(after.json().findValue("VAR").asDouble())).isEqualTo(167_372_692L);
        assertThat(Math.round(after.json().findValue("ES").asDouble())).isEqualTo(210_131_972L);
        JsonNode pnlAfter = after.json().findValue("PNL");
        for (int i = 0; i < pnl.size(); i++) {
            assertThat(Double.doubleToRawLongBits(pnlAfter.get(i).asDouble())).as("scenario " + i + " bit for bit")
                    .isEqualTo(Double.doubleToRawLongBits(pnl.get(i).asDouble()));
        }
        assertThat(Double.doubleToRawLongBits(after.json().findValue("VAR").asDouble())).isEqualTo(Double.doubleToRawLongBits(record.findValue("VAR").asDouble()));
        assertThat(Double.doubleToRawLongBits(after.json().findValue("ES").asDouble())).isEqualTo(Double.doubleToRawLongBits(record.findValue("ES").asDouble()));

        JsonNode audit = server.send("GET", "/api/v1/admin/audit?subject=pack/market-risk&limit=20").json();
        assertThat(audit.toString()).contains("pack-suspend").contains("pack-resume").contains("pack-suspend-refused");
        JsonNode graph = server.send("GET", "/api/v1/admin/lifecycle/graph").json();
        assertThat(graph.path("cycle").size()).isZero();
        assertThat(graph.path("nodes").toString()).contains("pack:market-risk");
    }

    private static void deleteTree(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (var s = Files.walk(p)) {
                for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(x);
                }
            }
        }
    }
}
