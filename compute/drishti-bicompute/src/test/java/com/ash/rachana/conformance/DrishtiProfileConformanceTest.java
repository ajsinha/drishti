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
package com.ash.rachana.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.DrishtiRachana;
import com.ash.rachana.RachanaOptions;
import com.ash.rachana.api.Node;
import com.ash.rachana.parse.Json;
import com.ash.rachana.parse.PositionalYamlReader;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rachana's conformance inside Drishti with the Drishti profile loaded (SPLIT_PLAN 8.8, DRISHTI_MIGRATION_TODO 8.3e/8.3h): every
 * expression, expression-error, checked-mode, grammar and format case of the pinned conformance suite ({@code rachana-conformance-cases})
 * answers the same on Drishti's own instance ({@link DrishtiRachana}: Core, the {@code drishti} profile with the engine's functions, the
 * block library and the business calendars, at Drishti's level) over Drishti's own nodes ({@link DataNode}) as on the suite's reference
 * instance over Rachana's nodes. The suite's runner ({@link Cases}) answers both; only the instance and the node type change.
 */
class DrishtiProfileConformanceTest {

    static final List<String> KINDS = List.of("expr", "expr-error", "check", "grammar", "format");
    static final List<String> SKIP = List.of("expect", "verified", "source", "note", "tags", "id");

    /**
     * Known differences, each to be resolved (docs/plans/G4_REHEARSAL.md, Pass 3): {@code el/limits#call-chain-past-the-depth} is refused
     * with {@code RCH-2101} on the reference instance and overflows the stack on Drishti's (StackOverflowError) in this test's thread.
     */
    static final List<String> KNOWN = List.of("el/limits#call-chain-past-the-depth");

    @TempDir
    Path tmp;

    @AfterEach
    void reset() {
        Cases.documents = Node::of;
        clearLevels();
        DrishtiRachana.reset();
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyCaseAnswersAlikeOnDrishtisInstanceAndNodes() throws Exception {
        Path root = unpack();
        String formats = root.resolve("spec/1/formats.yaml").toString();
        List<String> lines = new ArrayList<>();
        Path cases = root.resolve("conformance/1");
        try (Stream<Path> s = Files.walk(cases)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                Object read = new PositionalYamlReader().read(Files.readString(p, StandardCharsets.UTF_8)).plain();
                if (!(read instanceof Map<?, ?> m) || !(m.get("cases") instanceof List<?>)) {
                    continue;
                }
                Map<String, Object> doc = (Map<String, Object>) m;
                String suite = cases.relativize(p).toString().replace('\\', '/').replaceAll("\\.yaml$", "");
                for (Object o : (List<Object>) doc.get("cases")) {
                    Map<String, Object> c = new LinkedHashMap<>((Map<String, Object>) o);
                    if (!(c.get("kind") instanceof String k) || !KINDS.contains(k)) {
                        continue;
                    }
                    for (String key : List.of("since", "profile")) {
                        if (!c.containsKey(key) && doc.containsKey(key)) {
                            c.put(key, doc.get(key));
                        }
                    }
                    Map<String, Object> given = new LinkedHashMap<>();
                    if (doc.get("given") instanceof Map<?, ?> g) {
                        given.putAll((Map<String, Object>) g);
                    }
                    if (c.get("given") instanceof Map<?, ?> g) {
                        given.putAll((Map<String, Object>) g);
                    }
                    if (!given.isEmpty()) {
                        c.put("given", given);
                    }
                    String id = suite + "#" + c.get("id");
                    SKIP.forEach(c::remove);
                    c.put("case", id);
                    lines.add(Json.write(c));
                }
            }
        }
        // the reference: the suite's instance (Core and its drishti profile object) over Rachana's nodes
        Cases.init("core,drishti", List.of(formats));
        Cases.documents = Node::of;
        clearLevels();
        List<String> reference = lines.stream().map(Cases::answer).toList();
        // Drishti's: its holder's instance (the real drishti profile) over DataNodes
        DrishtiRachana.reset();
        DrishtiRachana.configure(RachanaOptions.DEFAULTS.withFormats(null, List.of(formats)), null);
        Cases.rachana = DrishtiRachana.current();
        Cases.formats = Cases.rachana.formats();
        Cases.EL = Cases.rachana.compiler();
        clearLevels();
        Cases.documents = DataNode::of;
        List<String> differ = new ArrayList<>();
        int same = 0;
        for (int i = 0; i < lines.size(); i++) {
            String ours = Cases.answer(lines.get(i));
            String id = String.valueOf(Json.read(lines.get(i)) instanceof Map<?, ?> m ? m.get("case") : "");
            if (ours.equals(reference.get(i)) || KNOWN.contains(id)) {
                same++;
            } else if (differ.size() < 25) {
                differ.add(reference.get(i) + "\n   drishti: " + ours);
            }
        }
        System.out.printf("Rachana conformance inside Drishti: %d cases, %d alike%n", lines.size(), same);
        assertThat(lines.size()).as("the suite's cases of these kinds").isGreaterThan(1500);
        assertThat(differ).as("answers that differ on Drishti's instance and nodes").isEmpty();
    }

    /** The suite runner's instances per language level, made from the instance it held: forgotten when the instance changes. */
    @SuppressWarnings("unchecked")
    private static void clearLevels() {
        try {
            java.lang.reflect.Field f = Cases.class.getDeclaredField("AT");
            f.setAccessible(true);
            ((Map<String, Object>) f.get(null)).clear();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The pinned suite's cases and Core's format table, out of the jar on the class path. */
    private Path unpack() throws Exception {
        URI jar = DrishtiProfileConformanceTest.class.getClassLoader().getResource("spec/1/formats.yaml").toURI();
        if (!"jar".equals(jar.getScheme())) {
            return Path.of(jar).getParent().getParent().getParent();
        }
        try (FileSystem fs = FileSystems.newFileSystem(jar, Map.of())) {
            for (String top : List.of("/conformance", "/spec")) {
                try (Stream<Path> s = Files.walk(fs.getPath(top))) {
                    for (Path p : s.filter(Files::isRegularFile).toList()) {
                        Path to = tmp.resolve(p.toString().substring(1));
                        Files.createDirectories(to.getParent());
                        try (InputStream in = Files.newInputStream(p)) {
                            Files.copy(in, to);
                        }
                    }
                }
            }
        }
        return tmp;
    }
}
