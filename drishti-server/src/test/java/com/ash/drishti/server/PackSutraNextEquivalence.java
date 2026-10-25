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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Sutra Next, generated screens: a pack whose generator now writes its Sutras as Drishti UI kit fragment uses (with a generated
 * doc:) draws exactly what the inline layouts drew. Every old layout kept under {@code src/test/resources/sutra-next/<pack>/}
 * ({@code <kind>.v2.sutra.yaml}, the generated file before the change, as version 2) is set beside the pack's Sutras and the view of
 * the kind's first committed sample (the pack's {@code samples/catalog.json}) compared, for a reader who sees everything and for
 * one whose role masks fields, leaving out only what names the layout (its version and fingerprint) and the timings. A subclass
 * names the pack and enables it alone, with the fields its masked role redacts; written the way {@link
 * EconomicsSutraNextEquivalenceTest} proves the economics screens.
 */
abstract class PackSutraNextEquivalence {

    /** What names the layout or the moment rather than the page: left out of the comparison. */
    static final Set<String> VOLATILE = Set.of("fingerprint", "layout", "fetchedAt", "generation", "builtAt", "tookMs", "elapsedMs", "servedBy", "timings");

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    @Autowired SutraRegistry sutras;
    private final ObjectMapper json = new ObjectMapper();

    /** The pack under test (its folder under config/packs and under the test resources' sutra-next). */
    abstract String pack();

    /** The site Sutra folder of a pack's test: emptied, and given to the server as drishti.rachana.dirs. */
    static Path site(DynamicPropertyRegistry r, String pack) throws Exception {
        Path site = TestRun.root().resolve(Path.of("sutra-next-" + pack, "sutras"));
        Files.createDirectories(site);
        try (var s = Files.list(site)) {
            for (Path f : s.toList()) {
                Files.delete(f);
            }
        }
        r.add("drishti.rachana.dirs", site::toString);
        return site;
    }

    /**
     * Synthetic records for kinds a pack has no committed sample of (derived pages, live connectors' answers): the test resources'
     * {@code sutra-next-data/<pack>/<kind>.jsonl} (the loaders' rows), copied undated (whatever the business date) into the folder
     * of a file connector of their own that each kind is routed to.
     */
    static Path lake(DynamicPropertyRegistry r, String pack, String... kinds) throws Exception {
        return lakeNamed(r, pack, "sutra-next-" + pack + "-lake", kinds);
    }

    /** As {@link #lake(DynamicPropertyRegistry, String, String...)}, the connector (and its folder under target/test-data) named {@code c}. */
    static Path lakeNamed(DynamicPropertyRegistry r, String pack, String c, String... kinds) throws Exception {
        Path day = TestRun.root().resolve(Path.of(c));
        Files.createDirectories(day);
        r.add("drishti.sources.connectors." + c + ".plugin", () -> "file");
        r.add("drishti.sources.connectors." + c + ".settings.root", day::toString);
        r.add("drishti.sources.connectors." + c + ".settings.rescan-seconds", () -> "3600");
        for (int i = 0; i < kinds.length; i++) {
            String kind = kinds[i];
            Files.copy(Path.of("src", "test", "resources", "sutra-next-data", pack, kind + ".jsonl"), day.resolve(kind + ".jsonl"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            r.add("drishti.sources.connectors." + c + ".kinds[" + i + "]", () -> kind);
            r.add("drishti.sources.routes." + kind, () -> c);
        }
        return day;
    }

    /** The first record of each synthetic kind ({@link #lake}): {@code kind -> kind/id}. */
    static Map<String, String> lakeRecords(String pack, String... kinds) throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, String> out = new LinkedHashMap<>();
        for (String kind : kinds) {
            String line = Files.readAllLines(Path.of("src", "test", "resources", "sutra-next-data", pack, kind + ".jsonl")).get(0);
            out.put(kind, kind + "/" + json.readTree(line).path("id").asText());
        }
        return out;
    }

    private Path siteDir() {
        return TestRun.root().resolve(Path.of("sutra-next-" + pack(), "sutras"));
    }

    /** The old layouts and what they need (an old block a deal layout uses), copied beside the pack's Sutras for the "before" view. */
    static Path resources(String pack) {
        return Path.of("src", "test", "resources", "sutra-next", pack);
    }

    /** The Sutra names with an old layout ({@code <name>.v2.sutra.yaml}). */
    List<String> names() throws Exception {
        try (var s = Files.list(resources(pack()))) {
            return s.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".v2.sutra.yaml")).sorted()
                    .map(n -> n.replace(".v2.sutra.yaml", "")).toList();
        }
    }

    /** The screens drawn on synthetic records ({@link #lake}) rather than a committed sample: {@code kind -> kind/id}; none by default. */
    Map<String, String> synthetic() throws Exception {
        return Map.of();
    }

    /** Each Sutra with an old layout and the record it draws ({@code kind/id}): by default the first committed sample of the kind it is named after. */
    Map<String, String> screens() throws Exception {
        Map<String, String> first = new LinkedHashMap<>();
        for (JsonNode e : json.readTree(Path.of("..", "config", "packs", pack(), "samples", "catalog.json").toFile())) {
            first.putIfAbsent(e.path("kind").asText(), e.path("id").asText());
        }
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, String> synthetic = synthetic();
        for (String kind : names()) {
            if (synthetic.containsKey(kind)) {
                out.put(kind, synthetic.get(kind));
                continue;
            }
            assertThat(first).as("a committed sample of " + kind).containsKey(kind);
            out.put(kind, kind + "/" + first.get(kind));
        }
        assertThat(out).isNotEmpty();
        return out;
    }

    private JsonNode read(String name, String record, String role) throws Exception {
        String url = "/api/v1/views/" + record;
        return json.readTree(mvc.perform(get(url).header("Authorization", "Bearer " + tokens.mint("u-" + role, List.of(role), 300)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    static JsonNode normal(JsonNode n) {
        if (n instanceof ObjectNode o) {
            for (Iterator<String> it = o.fieldNames(); it.hasNext(); ) {
                if (VOLATILE.contains(it.next())) {
                    it.remove();
                }
            }
            o.forEach(PackSutraNextEquivalence::normal);
        } else if (n != null && n.isArray()) {
            n.forEach(PackSutraNextEquivalence::normal);
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
    void everyMigratedScreenDrawsExactlyWhatTheInlineLayoutDrewForAFullAndAMaskedReader() throws Exception {
        Map<String, String> screens = screens();
        Map<String, JsonNode[]> after = new LinkedHashMap<>();
        for (var e : screens.entrySet()) {
            JsonNode full = read(e.getKey(), e.getValue(), "full");
            assertThat(layout(full)).as(e.getKey()).contains(e.getKey() + " v1");
            after.put(e.getKey(), new JsonNode[] {full, read(e.getKey(), e.getValue(), "masked")});
        }
        Path site = siteDir();
        List<Path> inline = new ArrayList<>();
        try (var s = Files.list(resources(pack()))) {
            for (Path f : s.toList()) {
                Path p = site.resolve(f.getFileName().toString());
                Files.copy(f, p);
                inline.add(p);
            }
        }
        try {
            sutras.reload();
            var w = json.writerWithDefaultPrettyPrinter();
            for (var e : screens.entrySet()) {
                String kind = e.getKey();
                JsonNode beforeFull = read(kind, e.getValue(), "full");
                JsonNode beforeMasked = read(kind, e.getValue(), "masked");
                assertThat(layout(beforeFull)).as(kind).contains(kind + " v2");
                JsonNode afterFull = after.get(kind)[0];
                JsonNode afterMasked = after.get(kind)[1];

                // the page is not empty and the mask holds (so the comparison is of something)
                assertThat(afterFull.path("panels").size()).as(kind).isGreaterThanOrEqualTo(2);
                assertThat(afterMasked.toString()).as(kind).contains("•••");
                assertThat(afterFull.toString()).as(kind).isNotEqualTo(afterMasked.toString());

                Path out = site.getParent();       // the views, normalised, for a reader of a failed run (target/test-data/sutra-next-<pack>)
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
        String kind = screens.keySet().iterator().next();
        assertThat(layout(read(kind, screens.get(kind), "full"))).contains(kind + " v1");
    }
}
