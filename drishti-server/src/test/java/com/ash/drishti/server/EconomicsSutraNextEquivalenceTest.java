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

import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Sutra Next phase 1: the world-economy pack's seven economics screens, rebuilt from the fragment library, draw exactly what the
 * generated, inline layouts drew, on the pack's committed samples: the same view model (title, strip, panels in order, their titles,
 * values, formats, masks, links) for a reader who sees everything and for one whose role masks fields. The inline layouts are set
 * beside them as version 2 (test data) and the views compared, leaving out only what names the layout itself (its version and
 * fingerprint) and the timings. Written the way {@link SutraNextEquivalenceTest} proves ir-curve.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.packs.enabled=world-economy", "drishti.security.redact=GDP_USD_BN,GROWTH,DEBT,UNEMPLOYMENT,LATEST,ISO3",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true"})
@AutoConfigureMockMvc
class EconomicsSutraNextEquivalenceTest {

    /** Each migrated Sutra and a committed sample it draws. */
    static final Map<String, String> SCREENS = new LinkedHashMap<>();

    static {
        SCREENS.put("country-group", "ECG-SAS");
        SCREENS.put("economic-forecast", "FCST-JPN-WEO");
        SCREENS.put("economy", "ECON-IND");
        SCREENS.put("fiscal-position", "FISC-USA");
        SCREENS.put("labour-market", "LABR-NGA");
        SCREENS.put("macro-indicator", "MACRO-BRA-GDP-GROWTH");
        SCREENS.put("region-economy", "REGE-AU-NSW");
    }

    static final Path SITE = TestRun.root().resolve(Path.of("sutra-next-economics", "sutras"));
    /** What names the layout or the moment rather than the page: left out of the comparison. */
    static final Set<String> VOLATILE = Set.of("fingerprint", "layout", "fetchedAt", "generation", "builtAt", "tookMs", "elapsedMs", "servedBy", "timings");

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired SutraRegistry sutras;
    private final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void site(DynamicPropertyRegistry r) throws Exception {
        Files.createDirectories(SITE);
        try (var s = Files.list(SITE)) {
            for (Path f : s.toList()) {
                Files.delete(f);
            }
        }
        r.add("drishti.rachana.dirs", SITE::toString);
    }

    private JsonNode read(String kind, String role) throws Exception {
        String url = "/api/v1/views/" + kind + "/" + SCREENS.get(kind);
        return json.readTree(mvc.perform(get(url).header("Authorization", "Bearer " + tokens.mint("u-" + role, List.of(role), 300)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode normal(JsonNode n) {
        if (n instanceof ObjectNode o) {
            for (Iterator<String> it = o.fieldNames(); it.hasNext(); ) {
                if (VOLATILE.contains(it.next())) {
                    it.remove();
                }
            }
            o.forEach(EconomicsSutraNextEquivalenceTest::normal);
        } else if (n != null && n.isArray()) {
            n.forEach(EconomicsSutraNextEquivalenceTest::normal);
        }
        return n;
    }

    /** The view as text, normalised, with the layout's own version (the provenance panel's "Sutra kind vN") made one. */
    private static String same(String kind, JsonNode view) {
        return normal(view.deepCopy()).toString().replace("Sutra " + kind + " v2", "Sutra " + kind + " v1");
    }

    private static String layout(JsonNode view) {
        return view.path("provenance").path("layout").asText();
    }

    @Test
    void theSevenMigratedEconomicsScreensDrawExactlyWhatTheInlineLayoutsDrewForAFullAndAMaskedReader() throws Exception {
        Map<String, JsonNode[]> after = new LinkedHashMap<>();
        for (String kind : SCREENS.keySet()) {
            JsonNode full = read(kind, "full");
            assertThat(layout(full)).as(kind).contains(kind + " v1");
            after.put(kind, new JsonNode[] {full, read(kind, "masked")});
        }
        List<Path> inline = new ArrayList<>();
        for (String kind : SCREENS.keySet()) {
            Path p = SITE.resolve(kind + ".v2.sutra.yaml");
            Files.copy(Path.of("src/test/resources/sutra-next/" + kind + ".v2.sutra.yaml"), p);
            inline.add(p);
        }
        try {
            sutras.reload();
            var w = json.writerWithDefaultPrettyPrinter();
            for (String kind : SCREENS.keySet()) {
                JsonNode beforeFull = read(kind, "full");
                JsonNode beforeMasked = read(kind, "masked");
                assertThat(layout(beforeFull)).as(kind).contains(kind + " v2");
                JsonNode afterFull = after.get(kind)[0];
                JsonNode afterMasked = after.get(kind)[1];

                // the page is not empty and the mask holds (so the comparison is of something)
                assertThat(afterFull.path("panels").size()).as(kind).isGreaterThanOrEqualTo(4);
                assertThat(afterMasked.toString()).as(kind).contains("•••");
                assertThat(afterFull.toString()).as(kind).isNotEqualTo(afterMasked.toString());

                Path out = SITE.getParent();       // the views, normalised, for a reader of a failed run (target/test-data/sutra-next-economics)
                w.writeValue(out.resolve(kind + "-after-full.json").toFile(), normal(afterFull.deepCopy()));
                w.writeValue(out.resolve(kind + "-before-full.json").toFile(), normal(beforeFull.deepCopy()));
                w.writeValue(out.resolve(kind + "-after-masked.json").toFile(), normal(afterMasked.deepCopy()));
                w.writeValue(out.resolve(kind + "-before-masked.json").toFile(), normal(beforeMasked.deepCopy()));
                assertThat(same(kind, afterFull)).as(kind + " full").isEqualTo(same(kind, beforeFull));
                assertThat(same(kind, afterMasked)).as(kind + " masked").isEqualTo(same(kind, beforeMasked));
            }
        } finally {
            for (Path p : inline) {
                Files.deleteIfExists(p);
            }
            sutras.reload();
        }
        assertThat(layout(read("economy", "full"))).contains("economy v1");
    }
}
