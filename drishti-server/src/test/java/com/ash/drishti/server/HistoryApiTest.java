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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/history/{kind}/{id}} (HISTORY_QUERIES.md sections 7, 8, 13) over the Delta test lake (trade T-1 on 2026-09-28, 29 and
 * 30): the answer has exactly the fixture's shape, every day is masked for a role without raw, rights are checked, and each error has its
 * code.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=finance",
        "drishti.sources.connectors.finance-lake.settings.root=../plugins/drishti-plugin-delta/src/test/resources/lake",
        "drishti.sources.connectors.finance-lake.settings.domain=desk",
        "drishti.sources.connectors.finance-lake.settings.mode.counterparty=effective",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=nettingSet",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true",
        "drishti.security.roles.other.kinds[0]=counterparty", "drishti.security.roles.other.raw=true"})
@AutoConfigureMockMvc
class HistoryApiTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    private final ObjectMapper json = new ObjectMapper();

    private String as(String role) {
        return "Bearer " + tokens.mint("u-" + role, List.of(role), 300);
    }

    private JsonNode read(String role, String url, String... params) throws Exception {
        var req = get(url).header("Authorization", as(role));
        for (int i = 0; i + 1 < params.length; i += 2) {
            req = req.param(params[i], params[i + 1]);
        }
        return json.readTree(mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static TreeSet<String> keys(JsonNode n) {
        TreeSet<String> out = new TreeSet<>();
        for (Iterator<String> it = n.fieldNames(); it.hasNext(); ) {
            out.add(it.next());
        }
        return out;
    }

    /** Every object of the answer has the same fields as the fixture's object at the same place (the contract builder B builds against). */
    private static void sameShape(JsonNode fixture, JsonNode answer, String at, List<String> diffs) {
        if (fixture.isObject()) {
            if (!answer.isObject()) {
                diffs.add(at + ": not an object");
                return;
            }
            boolean freeKeys = at.endsWith(".values") || at.endsWith(".display") || at.endsWith(".parts") && at.contains("rows[");
            if (!freeKeys && !keys(fixture).equals(keys(answer))) {
                diffs.add(at + ": fields " + keys(answer) + " instead of " + keys(fixture));
            }
            if (!freeKeys) {
                for (String k : keys(fixture)) {
                    if (answer.has(k) && !fixture.get(k).isNull() && !answer.get(k).isNull()) {
                        sameShape(fixture.get(k), answer.get(k), at + "." + k, diffs);
                    }
                }
            }
        } else if (fixture.isArray() && answer.isArray() && fixture.size() > 0 && answer.size() > 0) {
            sameShape(fixture.get(0), answer.get(0), at + "[0]", diffs);
        }
    }

    @Test
    void theAnswerHasTheContractsShape() throws Exception {
        JsonNode fixture;
        try (InputStream in = getClass().getResourceAsStream("/history/trade-1000015-30.json")) {
            fixture = json.readTree(in);
        }
        JsonNode a = read("full", "/api/v1/history/trade/T-1", "from", "2026-09-28", "to", "2026-09-30", "diff", "false");
        List<String> diffs = new ArrayList<>();
        sameShape(fixture, a, "$", diffs);
        assertThat(diffs).isEmpty();
        assertThat(a.at("/rows").size()).isEqualTo(3);
        assertThat(a.at("/rows/0/date").asText()).isEqualTo("2026-09-28");
        assertThat(a.at("/rows/2/values/mtm").asDouble()).isEqualTo(125.0);
        assertThat(a.at("/rows/1/changed").toString()).contains("mtm");
        assertThat(a.at("/window/mode").asText()).isEqualTo("between");
        assertThat(a.at("/shape").asText()).isEqualTo("document");
        assertThat(a.at("/rows/0/source").asText()).isEqualTo("finance-lake");
        assertThat(a.at("/mnemonic").asText()).isEqualTo("TRD");
    }

    @Test
    void theLastDaysWithDataAndTheIdForm() throws Exception {
        JsonNode a = read("full", "/api/v1/history/trade/~", "id", "T-1", "days", "2", "to", "2026-09-30");
        assertThat(a.at("/rows").size()).isEqualTo(2);
        assertThat(a.at("/window/baseline").asText()).isEqualTo("2026-09-28");
        assertThat(a.at("/rows/0/changed").toString()).contains("mtm");
    }

    @Test
    void everyDayIsMaskedAndAMaskedFieldNeverChanges() throws Exception {
        JsonNode a = read("masked", "/api/v1/history/trade/T-1", "from", "2026-09-28", "to", "2026-09-30");
        for (JsonNode r : a.at("/rows")) {
            assertThat(r.at("/values/nettingSet").asText()).isEqualTo("•••");
            assertThat(r.at("/changed").toString()).doesNotContain("nettingSet");
        }
        boolean masked = false;
        for (JsonNode c : a.at("/columns")) {
            if (c.path("path").asText().equals("nettingSet")) {
                masked = c.path("masked").asBoolean();
            }
        }
        assertThat(masked).isTrue();
        JsonNode full = read("full", "/api/v1/history/trade/T-1", "from", "2026-09-28", "to", "2026-09-30");
        assertThat(full.at("/rows/0/values/nettingSet").asText()).isEqualTo("NS-A");
    }

    @Test
    void anyDatedKindWithNoHistoryBlockHasTheDocumentShape() throws Exception {
        // counterparty: an effective-dated kind with no history: block; its rows are the days it changed
        JsonNode a = read("full", "/api/v1/history/counterparty/CP-X", "from", "2026-09-28", "to", "2026-09-30");
        assertThat(a.at("/shape").asText()).isEqualTo("document");
        assertThat(a.at("/rows").size()).isEqualTo(2);
        assertThat(a.at("/rows/0/date").asText()).isEqualTo("2026-09-28");
        assertThat(a.at("/rows/1/date").asText()).isEqualTo("2026-09-30");
        assertThat(a.at("/rows/1/values/rating").asText()).isEqualTo("A-");
        assertThat(a.at("/rows/1/changed").toString()).contains("rating");
        List<String> paths = new ArrayList<>();
        a.at("/columns").forEach(c -> paths.add(c.path("path").asText()));
        assertThat(paths).contains("id", "rating");
        assertThat(a.at("/window/missing").toString()).contains("2026-09-29");
    }

    @Test
    void rightsAndErrors() throws Exception {
        mvc.perform(get("/api/v1/history/trade/T-1").param("days", "3").header("Authorization", as("other"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/history/trade/T-1").param("days", "9999").header("Authorization", as("full")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4021"));
        mvc.perform(get("/api/v1/history/trade/T-1").param("from", "2026-09-30").param("to", "2026-09-01").header("Authorization", as("full")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4022"));
        mvc.perform(get("/api/v1/history/trade/T-1").param("range", "3Q").header("Authorization", as("full")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DRS-4022"));
        mvc.perform(get("/api/v1/history/trade/T-1").param("from", "2026-01-05").param("to", "2026-01-30").header("Authorization", as("full")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-1013"));
        mvc.perform(get("/api/v1/history/trade/NOPE").param("from", "2026-09-28").param("to", "2026-09-30").header("Authorization", as("full")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DRS-1001"));
        // QA1-13: a bad "known at" names itself, not the business date
        for (String bad : List.of("garbage", "2026-10-20")) {
            mvc.perform(get("/api/v1/history/trade/T-1").param("days", "3").param("knownAt", bad).header("Authorization", as("full")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("knownAt '" + bad
                            + "' is not an instant")));
        }
    }

    @Test
    void knownAtReadsTheDeltaLakeAsItStood() throws Exception {
        // QA1-04: every source of trade that may hold the dates is Delta (time travel); the shipped file connector holds none of them
        JsonNode a = read("full", "/api/v1/history/trade/T-1", "from", "2026-09-28", "to", "2026-09-30", "knownAt", java.time.Instant.now().toString());
        assertThat(a.at("/window/knownAt").asText()).isNotBlank();
        assertThat(a.path("rows")).hasSize(3);
        assertThat(a.at("/rows/2/values/mtm").asDouble()).isEqualTo(125.0);
    }
}
