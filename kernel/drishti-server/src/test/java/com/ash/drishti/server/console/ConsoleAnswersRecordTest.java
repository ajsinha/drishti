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
package com.ash.drishti.server.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.packs.distribution.Distribution;
import com.ash.drishti.packs.distribution.Distributions;
import com.ash.drishti.packs.distribution.ModuleManifests;
import com.ash.drishti.packs.edition.EditionEnvironmentPostProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The recorded installation answers the console's unit tests read (docs/architecture/THIN_CONSOLE_DESIGN.md 5.2, "Recorded answers"):
 * {@code drishti-console/tests/answers/bootstrap-<distribution>.json}, one per distribution of the product, made here by the server's own
 * code from the repository's files (the distribution's own lenses offered, every lens's arrangement kept, every module loaded). One direction only: the server
 * makes them, the console reads them. Run with {@code -Ddrishti.record=true} to write them again; otherwise a file that differs from what
 * the server makes fails here, so a console test never reads a stale sample.
 */
class ConsoleAnswersRecordTest {

    static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
    static final Path OUT = REPO.resolve("drishti-console/tests/answers");

    @Test
    void theRecordedBootstrapsAreTheServersAnswers() throws Exception {
        Path dir = REPO.resolve("config/distributions");
        Map<String, ModuleManifests.Entry> known = ModuleManifests.read(REPO.resolve("modules"));
        List<String> lenses = lensNames(REPO.resolve("config/lenses"));
        Map<String, List<String>> mapping = EditionEnvironmentPostProcessor.featureMapping(dir, "console");
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        boolean record = Boolean.getBoolean("drishti.record");
        List<String> stale = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.getFileName().toString().matches("drishti\\.[a-z0-9-]+\\.yaml")).sorted().toList()) {
                Distribution d = Distributions.read(f, dir.resolve(Distributions.KERNEL), REPO.resolve("modules"), REPO.resolve("config/packs"));
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("schema", ConsoleAnswers.SCHEMA);
                Map<String, Object> dist = ConsoleDistribution.of(d, REPO.resolve("config/lenses"), lenses, mapping);
                @SuppressWarnings("unchecked")
                Map<String, Object> l = (Map<String, Object>) dist.get("lenses");
                l.put("offered", d.lenses());                    // the file's own; every lens's arrangement is kept for tests that offer others
                l.put("default", d.lenses().contains(String.valueOf(l.get("default"))) ? l.get("default") : "");
                doc.put("distribution", dist);
                List<Map<String, Object>> modules = new ArrayList<>();
                known.forEach((id, k) -> modules.add(ConsoleAnswers.moduleRow(id, k, null, null, null, true)));
                doc.put("modules", modules);
                String text = json.writeValueAsString(doc).replace("\r\n", "\n") + "\n";
                assertThat(text).as("no folder of the repository").doesNotContain(REPO.toString());
                Path out = OUT.resolve("bootstrap-" + d.shortName() + ".json");
                if (record || !Files.isRegularFile(out)) {
                    Files.createDirectories(OUT);
                    Files.writeString(out, text, StandardCharsets.UTF_8);
                } else if (!Files.readString(out, StandardCharsets.UTF_8).equals(text)) {
                    stale.add(out.getFileName().toString());
                }
            }
        }
        assertThat(stale).as("recorded answers older than the server's: run this test with -Ddrishti.record=true").isEmpty();
    }

    static List<String> lensNames(Path dir) throws Exception {
        TreeSet<String> out = new TreeSet<>();
        try (Stream<Path> s = Files.walk(dir, 2)) {
            s.filter(p -> p.getFileName().toString().endsWith(".yaml")).forEach(p -> out.add(p.getFileName().toString().replace(".yaml", "")));
        }
        return List.copyOf(out);
    }
}
