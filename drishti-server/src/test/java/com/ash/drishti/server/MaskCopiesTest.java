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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * S2-11, {@code drishti.security.mask-copies=true}: the exact text of a masked field's value, copied into other text of the
 * same document ("Captured in Murex by TRDR-ASHAH"), reads as the mask for a role without {@code raw}; a role with {@code raw}
 * sees everything. A {@code redact} entry with dots masks a field by the end of its path. Case variants are not matched
 * (the match is exact), short values and over-large documents are left alone.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=trading,counterparty-risk",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=trader,lifecycle.note", "drishti.security.mask-copies=true",
        "drishti.security.mask-copies-max-nodes=5000",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true"})
@AutoConfigureMockMvc
class MaskCopiesTest {

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired Entitlements entitlements;
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode raw(String role) throws Exception {
        String body = mvc.perform(get("/api/v1/entities/trade/MX-20000001/raw").header("Authorization", "Bearer " + tokens.mint("u-" + role, List.of(role), 300)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    @Test
    void aCopyOfTheMaskedTraderInATimelineDescriptionReadsAsTheMask() throws Exception {
        assertThat(raw("full").toString()).contains("Captured in Murex by TRDR-ASHAH");
        String masked = raw("masked").toString();
        assertThat(masked).contains("Captured in Murex by •••:").doesNotContain("TRDR-ASHAH");
    }

    private DataNode doc(String trader, String text) {
        Map<String, DataNode> m = new LinkedHashMap<>();
        m.put("trader", DataNode.of(trader));
        m.put("note", DataNode.of(text));
        m.put("amount", DataNode.of(1234567));
        m.put("lifecycle", DataNode.of(Map.of("note", "secret", "other", "kept secret")));
        return new DataNode.Obj(m);
    }

    private String note(DataNode d) {
        return d.get("note").unwrap().toString();
    }

    @Test
    void matchingIsExactShortValuesAndOversizeDocumentsAreLeftAlone() {
        Principal masked = new Principal("m", List.of("masked"));
        assertThat(note(entitlements.redact(masked, doc("J. Smith", "Captured by J. Smith")))).isEqualTo("Captured by •••");
        assertThat(note(entitlements.redact(masked, doc("J. Smith", "Captured by j. smith")))).isEqualTo("Captured by j. smith");     // case variants: documented, not matched
        assertThat(note(entitlements.redact(masked, doc("ab", "ab is short")))).isEqualTo("ab is short");                              // under the minimum length
        assertThat(entitlements.redact(masked, doc("x", "n 1234567 y")).get("note").unwrap()).isEqualTo("n 1234567 y");               // only masked numbers count
        DataNode path = entitlements.redact(masked, doc("J. Smith", "x"));
        assertThat(path.get("lifecycle").get("note").isMasked()).isTrue();                // a dotted redact entry masks by path
        assertThat(path.get("lifecycle").get("other").unwrap()).isEqualTo("kept \u2022\u2022\u2022"); // ...and its copy in another field is scrubbed
    }

    @Test
    void aVeryLargeDocumentIsNotScannedAndTheScanIsFast() {
        Principal masked = new Principal("m", List.of("masked"));
        List<DataNode> rows = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            rows.add(DataNode.of("row " + i + " by J. Smith"));
        }
        Map<String, DataNode> m = new LinkedHashMap<>();
        m.put("trader", DataNode.of("J. Smith"));
        m.put("rows", new DataNode.Arr(rows));
        long t0 = System.nanoTime();
        DataNode out = entitlements.redact(masked, new DataNode.Obj(m));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(out.get("trader").isMasked()).isTrue();                         // the field is still masked
        assertThat(out.get("rows").get(0).unwrap()).isEqualTo("row 0 by J. Smith"); // over mask-copies-max-nodes: no scrubbing
        assertThat(ms).isLessThan(2000);
    }
}
