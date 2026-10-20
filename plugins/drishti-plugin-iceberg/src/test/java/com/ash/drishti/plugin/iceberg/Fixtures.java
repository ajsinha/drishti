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
package com.ash.drishti.plugin.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The pyiceberg-written fixture tables ({@code src/test/resources/iceberg-fixtures}, made by
 * {@code tools/iceberg/fixtures.py}) and what pyiceberg read from each of their snapshots ({@code expected.json}).
 * Their paths name {@code /tmp/drishti-iceberg-fixtures}; {@link #settings} relocates them to a copy of the folder, so
 * a test may also add delete files to its own copy.
 */
final class Fixtures {

    static final String WRITTEN_AT = "file:///tmp/drishti-iceberg-fixtures";
    static final ObjectMapper JSON = new ObjectMapper();

    private Fixtures() {}

    /** A copy of the fixture folder under the module's target (tests may change their copy). */
    static Path copy() {
        Path src = Path.of("src/test/resources/iceberg-fixtures").toAbsolutePath();
        try {
            Path dst = Files.createTempDirectory("iceberg-fixtures");
            try (Stream<Path> all = Files.walk(src)) {
                for (Path p : (Iterable<Path>) all.sorted(Comparator.naturalOrder())::iterator) {
                    Path to = dst.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(to);
                    } else {
                        Files.copy(p, to);
                    }
                }
            }
            return dst;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final ObjectMapper SORTED = new ObjectMapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** A JSON value as text with object keys sorted and numbers as JSON parses them (a float 0.25 and a double 0.25 alike). */
    static String canonical(JsonNode n) {
        try {
            return SORTED.writeValueAsString(SORTED.readValue(n.toString(), Object.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode expected(Path root) {
        try {
            return JSON.readTree(root.resolve("expected.json").toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Native-engine settings reading every fixture table by its metadata file, relocated to {@code root}. */
    static Map<String, String> settings(Path root) {
        Map<String, String> s = new HashMap<>();
        s.put("relocate", WRITTEN_AT + " -> " + root.toUri().toString().replaceAll("/+$", ""));
        expected(root).fields().forEachRemaining(e -> s.put("metadata-location." + e.getKey(), WRITTEN_AT + "/" + e.getValue().path("metadata").asText()));
        return s;
    }
}
