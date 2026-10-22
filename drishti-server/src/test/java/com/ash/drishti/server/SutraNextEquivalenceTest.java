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
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Sutra Next phase 1: the ir-curve page rebuilt from the fragment library ({@code /v/ir-curve/CRV-USD-OIS}) draws exactly what the
 * generated, inline layout drew: the same view model (title, strip, panels in order, their titles, values, formats, links) for a
 * reader who sees everything and for one whose role masks fields. The inline layout is set beside it as version 2 (test data) and
 * the two views are compared, leaving out only what names the layout itself (its version and fingerprint) and the fetch time.
 * Also: {@code GET /api/v1/sutras/library} lists the fragments with their docstrings.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.packs.enabled=market-data", "drishti.security.redact=TEN_Y,ZERO_RATE,INSTRUMENT,CALENDAR",
        "drishti.security.roles.masked.kinds[0]=*", "drishti.security.roles.masked.raw=false",
        "drishti.security.roles.full.kinds[0]=*", "drishti.security.roles.full.raw=true"})
@AutoConfigureMockMvc
class SutraNextEquivalenceTest {

    static final String VIEW = "/api/v1/views/ir-curve/CRV-USD-OIS";
    static final Path SITE = Path.of("target", "test-data", "sutra-next", "sutras").toAbsolutePath();
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

    private JsonNode read(String url, String role) throws Exception {
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
            o.forEach(SutraNextEquivalenceTest::normal);
        } else if (n != null && n.isArray()) {
            n.forEach(SutraNextEquivalenceTest::normal);
        }
        return n;
    }

    /** The view as text, normalised, with the layout's own version (the provenance panel's "Sutra ir-curve vN") made one. */
    private static String same(JsonNode view) {
        return normal(view.deepCopy()).toString().replace("Sutra ir-curve v2", "Sutra ir-curve v1");
    }

    private static String layout(JsonNode view) {
        return view.path("provenance").path("layout").asText();
    }

    @Test
    void theMigratedIrCurvePageDrawsExactlyWhatTheInlineLayoutDrewForAFullAndAMaskedReader() throws Exception {
        JsonNode afterFull = read(VIEW, "full");
        JsonNode afterMasked = read(VIEW, "masked");
        assertThat(layout(afterFull)).contains("ir-curve v1");
        Path inline = SITE.resolve("ir-curve.v2.sutra.yaml");
        Files.copy(Path.of("src/test/resources/sutra-next/ir-curve.v2.sutra.yaml"), inline);
        try {
            sutras.reload();
            JsonNode beforeFull = read(VIEW, "full");
            JsonNode beforeMasked = read(VIEW, "masked");
            assertThat(layout(beforeFull)).contains("ir-curve v2");

            // the page is not empty and the mask holds (so the comparison is of something)
            assertThat(afterFull.path("panels")).hasSize(5);
            assertThat(afterFull.toString()).contains("Deposit / future");
            assertThat(afterMasked.toString()).doesNotContain("Deposit / future").contains("•••");

            Path out = SITE.getParent();       // the four views, normalised, for a reader of a failed run (target/test-data/sutra-next)
            var w = json.writerWithDefaultPrettyPrinter();
            w.writeValue(out.resolve("after-full.json").toFile(), normal(afterFull.deepCopy()));
            w.writeValue(out.resolve("before-full.json").toFile(), normal(beforeFull.deepCopy()));
            w.writeValue(out.resolve("after-masked.json").toFile(), normal(afterMasked.deepCopy()));
            w.writeValue(out.resolve("before-masked.json").toFile(), normal(beforeMasked.deepCopy()));
            assertThat(same(afterFull)).isEqualTo(same(beforeFull));
            assertThat(same(afterMasked)).isEqualTo(same(beforeMasked));
        } finally {
            Files.deleteIfExists(inline);
            sutras.reload();
        }
        assertThat(layout(read(VIEW, "full"))).contains("ir-curve v1");
    }

    @Test
    void theLibraryApiListsTheFragmentsWithTheirDocstrings() throws Exception {
        JsonNode lib = read("/api/v1/sutras/library", "full");
        assertThat(lib.path("count").asInt()).isBetween(8, 15);
        JsonNode line = null;
        for (JsonNode f : lib.path("fragments")) {
            assertThat(f.path("fragment").asBoolean()).isTrue();
            assertThat(f.path("doc").path("summary").asText()).isNotBlank();
            assertThat(f.path("doc").path("examples")).isNotEmpty();
            assertThat(f.path("source").asText()).doesNotStartWith("/");
            assertThat(f.path("origin").asText()).as("a Drishti UI kit fragment").isEqualTo("kit");
            if (f.path("ref").asText().equals("series-line@1")) {
                line = f;
            }
        }
        assertThat(line).isNotNull();
        assertThat(line.path("doc").path("params").path("rows").path("type").asText()).isEqualTo("expression");
        assertThat(line.path("params").path("fmt").asText()).isEqualTo("price2");
        assertThat(read("/api/v1/sutras/library?blocks=true", "full").path("count").asInt()).isGreaterThanOrEqualTo(lib.path("count").asInt());
        JsonNode one = read("/api/v1/sutras/library/curve-table/1", "full");
        assertThat(one.path("text").asText()).contains("fragment: curve-table");
        mvc.perform(get("/api/v1/sutras/library/nothing/1").header("Authorization", "Bearer " + tokens.mint("u-full", List.of("full"), 300)))
                .andExpect(status().isNotFound());
    }
}
