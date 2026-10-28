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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.ash.drishti.server.PinnedBusinessDate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * An upgraded server whose data predates the limit letters (2.9.5, the product owner's rule of 2026-10-28): the market-risk pack
 * configures the hierarchy LL from {@code LIMIT_LETTER}, but the P&amp;L vectors and the exposure paths in the lake are the earlier ones,
 * without the column ({@code VarLakeFixture}, {@code ExposureLakeFixture}). VaR and exposure still answer (var-result BANK and
 * exposure-result ready, MGMT and LEGAL as before); only LL is skipped, said as DRS-5211 with the fix in the provenance's warnings, in
 * Admin → Health (the skipped hierarchies) and in the log.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=market-risk,counterparty-risk",
        "drishti.sources.plugins.demo.enabled=false",
        "drishti.sources.connectors-dir=target/hierarchy-upgrade/connectors", "drishti.data.dir=target/hierarchy-upgrade/data",
        "drishti.var.official.on-start=false", "drishti.ccr.exposure.official.on-start=false", "drishti.ccr.exposure.close-grace-seconds=0",
        "drishti.var.threads=2", "drishti.var.tier.close-grace-seconds=0",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@AutoConfigureMockMvc
@Import(PinnedBusinessDate.class)
class HierarchyUpgradeWithoutLettersTest {

    static final Path BASE = Path.of("target", "hierarchy-upgrade").toAbsolutePath();
    static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    static {
        try {
            prepare();
        } catch (Exception e) {
            throw new IllegalStateException("the lakes cannot be laid out: " + e, e);
        }
    }

    /** The VaR and exposure lakes of before 2.9.5 (no LIMIT_LETTER) where the packs' generated connectors read them. */
    static void prepare() throws Exception {
        if (Files.exists(BASE)) {
            try (Stream<Path> s = Files.walk(BASE)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        Path delta = Files.createDirectories(BASE.resolve("data/delta"));
        fixture("com.ash.drishti.server.var.VarLakeFixture", delta);
        Path ccr = Files.createDirectories(BASE.resolve("ccr"));
        fixture("com.ash.drishti.server.exposure.ExposureLakeFixture", ccr);
        for (String[] t : new String[][] {{"simulation-run", "risk"}, {"exposure-path", "risk"}, {"netting-set", "credit"}, {"csa", "legal"},
                {"collateral-balance", "collateral"}, {"counterparty", "reference"}, {"counterparty-group", "reference"}}) {
            Path from = ccr.resolve("risk").resolve(t[0]);
            if (Files.isDirectory(from)) {
                Path to = delta.resolve(t[1]).resolve(t[0]);
                try (Stream<Path> s = Files.walk(from)) {
                    for (Path p : s.toList()) {
                        Path q = to.resolve(from.relativize(p).toString());
                        if (Files.isDirectory(p)) {
                            Files.createDirectories(q);
                        } else {
                            Files.createDirectories(q.getParent());
                            Files.copy(p, q);
                        }
                    }
                }
            }
        }
    }

    private static void fixture(String cls, Path root) throws Exception {
        var m = Class.forName(cls).getDeclaredMethod("write", Path.class);
        m.setAccessible(true);
        m.invoke(null, root);
    }

    @Autowired MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    private JsonNode compute(String connector, String kind, String body) throws Exception {
        var r = mvc.perform(post("/api/v1/compute/" + connector + "/" + kind).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        assertThat(r.getStatus()).as(connector + "/" + kind + ": " + r.getContentAsString()).isEqualTo(200);
        return json.readTree(r.getContentAsString());
    }

    @Test
    void vaRAndExposureAnswerWithoutTheLettersAndOnlyLlIsSkippedWithAWarning() throws Exception {
        JsonNode v = compute("var-engine", "var-result", "{\"id\": \"BANK\", \"asOf\": \"" + DAY + "\", \"wait\": 20000}");
        assertThat(v.get("state").asText()).as(v.toString()).isEqualTo("ready");
        assertThat(v.at("/result/VAR").isNumber()).isTrue();
        JsonNode x = compute("exposure-engine", "exposure-result", "{\"id\": \"NSET:NS-X\", \"asOf\": \"" + DAY + "\", \"wait\": 20000}");
        assertThat(x.get("state").asText()).as(x.toString()).isEqualTo("ready");
        // the VaR answer says why LL is not there, with the fix
        JsonNode desk = json.readTree(mvc.perform(get("/api/v1/var/scope").param("scope", "DESK:DESK-X").param("asOf", DAY.toString()))
                .andReturn().getResponse().getContentAsString());
        assertThat(desk.get("var").asDouble()).as(desk.toString()).isEqualTo(390);       // MGMT as before (VarApiTest's figure)
        assertThat(desk.at("/provenance/warnings").toString()).contains("DRS-5211").contains("hierarchy LL").contains("LIMIT_LETTER")
                .contains("layout.pnl-vector.columns");
        JsonNode set = json.readTree(mvc.perform(get("/api/v1/exposure/scope").param("scope", "NSET:NS-X").param("asOf", DAY.toString()))
                .andReturn().getResponse().getContentAsString());
        assertThat(set.at("/provenance/warnings").toString()).contains("DRS-5211").contains("layout.exposure-path.columns");
        assertThat(com.ash.drishti.server.var.Hierarchies.skipped()).containsKeys("var LL", "exposure LL");
        JsonNode health = json.readTree(mvc.perform(get("/api/v1/admin/health")).andReturn().getResponse().getContentAsString());
        assertThat(health.path("hierarchiesSkipped").toString()).contains("var LL").contains("LIMIT_LETTER");
    }
}
