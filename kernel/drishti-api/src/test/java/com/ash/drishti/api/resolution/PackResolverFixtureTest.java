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
package com.ash.drishti.api.resolution;

import com.ash.drishti.testroots.TestRoots;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Java side of the shared fixture of pack resolution (src/test/resources/pack-resolution/cases.yaml; the Python twin is
 * drishti-console/tests/test_resolution.py): every case gives the packs, origins, folders and problems the fixture says, and nothing is
 * written.
 */
class PackResolverFixtureTest {

    static final Path CASES = Path.of(TestRoots.module(), "src", "test", "resources", "pack-resolution", "cases.yaml");

    @TempDir
    Path tmp;

    @Test
    @SuppressWarnings("unchecked")
    void everyCaseResolvesAsTheFixtureSays() throws Exception {
        Map<String, Object> doc = new ObjectMapper(new YAMLFactory()).readValue(CASES.toFile(), new TypeReference<Map<String, Object>>() {});
        List<Map<String, Object>> cases = (List<Map<String, Object>>) doc.get("cases");
        assertThat(cases).hasSizeGreaterThan(10);
        int i = 0;
        for (Map<String, Object> c : cases) {
            String why = String.valueOf(c.get("name"));
            Path root = Files.createDirectories(tmp.resolve("case" + i++));
            ((Map<String, Object>) c.get("tree")).forEach((rel, text) -> {
                try {
                    Path f = root.resolve(rel);
                    Files.createDirectories(f.getParent());
                    Files.writeString(f, String.valueOf(text));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            List<String> active = (List<String>) c.getOrDefault("active", List.of());
            long before = count(root);
            PackResolver.Result r = PackResolver.resolve(PackResolver.Layout.of(root.resolve("site"), root.resolve("modules"), root.resolve("store")),
                    active::contains, ResolutionSettings::yaml);
            Map<String, String> got = new LinkedHashMap<>();
            r.packs().forEach((n, l) -> got.put(n, l.origin()));
            Map<String, String> want = new LinkedHashMap<>();
            ((Map<String, Object>) c.getOrDefault("packs", Map.of())).forEach((n, o) -> want.put(n, String.valueOf(o)));
            assertThat(got).as(why).isEqualTo(want);
            if (c.get("off") instanceof Map<?, ?> off) {
                off.forEach((n, m) -> assertThat(r.off()).as(why).containsEntry(String.valueOf(n), String.valueOf(m)));
            }
            if (c.get("dirs") instanceof Map<?, ?> dirs) {
                dirs.forEach((n, d) -> assertThat(r.dir(String.valueOf(n))).as(why).isEqualTo(root.resolve(String.valueOf(d)).toAbsolutePath().normalize()));
            }
            List<String> problems = new ArrayList<>(r.problems());
            for (Object frag : (List<Object>) c.getOrDefault("problems", List.of())) {
                assertThat(problems).as(why + ": a problem containing " + frag).anyMatch(p -> p.contains(String.valueOf(frag)));
                problems.removeIf(p -> p.contains(String.valueOf(frag)));
            }
            assertThat(problems).as(why + ": no other problem").isEmpty();
            assertThat(count(root)).as(why + ": the resolver writes nothing").isEqualTo(before);
        }
    }

    private static long count(Path root) throws java.io.IOException {
        try (var s = Files.walk(root)) {
            return s.count();
        }
    }
}
