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
package com.ash.drishti.enginehost;

import com.ash.drishti.api.ColumnSet;
import com.ash.drishti.api.VectorSink;
import com.ash.engine.Filter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/** What the host tests share: the sample engine's JAR as a site copies it, a kind held in arrays, callers, an engine file. */
final class Fixtures {

    private Fixtures() {
    }

    /** The sample engines' JAR (wmean and group-totals), copied by the build from drishti-engine's engine-sample. */
    static Path sampleJar() {
        Path p = Path.of(System.getProperty("engine.sample.jar", "target/engines/engine-sample-1.0.0.jar"));
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("the sample engine JAR is not at " + p + " (the build copies it in generate-test-resources)");
        }
        return p;
    }

    /** A copy of {@code from} whose descriptor of {@code id} has {@code a} replaced by {@code b}. */
    static void rewrite(Path from, Path to, String id, String a, String b) throws IOException {
        try (JarFile in = new JarFile(from.toFile()); JarOutputStream out = new JarOutputStream(Files.newOutputStream(to))) {
            Enumeration<JarEntry> en = in.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                out.putNextEntry(new JarEntry(e.getName()));
                try (InputStream s = in.getInputStream(e)) {
                    byte[] bytes = s.readAllBytes();
                    if (e.getName().equals("META-INF/drishti-engine/" + id + ".engine.yaml")) {
                        bytes = new String(bytes, StandardCharsets.UTF_8).replace(a, b).getBytes(StandardCharsets.UTF_8);
                    }
                    out.write(bytes);
                }
                out.closeEntry();
            }
        }
    }

    /** group-totals' engine file: its items from the kind {@code item}, per viewer, the host's row rules. */
    static final String GROUP_TOTALS = """
            drishti:
              engines:
                group-totals:
                  enabled: true
                  inputs:
                    items: { kinds: [ item ] }
                  access:
                    visibility: row-rules
            """;

    /** A home folder: lib/engines (with the JAR when given), config/engines/<files>, config/licence.yaml when given. */
    static EngineHostSettings home(Path root, Path jar, Map<String, String> engineFiles, String licence) throws IOException {
        Path lib = Files.createDirectories(root.resolve("lib/engines"));
        if (jar != null) {
            Files.copy(jar, lib.resolve(jar.getFileName().toString().startsWith("engine-sample") ? jar.getFileName().toString() : "engine-sample-1.0.0.jar"));
        }
        Path config = Files.createDirectories(root.resolve("config/engines"));
        for (Map.Entry<String, String> f : engineFiles.entrySet()) {
            Files.writeString(config.resolve(f.getKey() + ".yaml"), f.getValue());
        }
        if (licence != null) {
            Files.writeString(root.resolve("config/licence.yaml"), licence);
        }
        return EngineHostSettings.of(k -> null, root, root.resolve("config"), root.resolve("data"));
    }

    /** A host over {@code data}, nothing in tree, no packs. */
    static EngineHost host(EngineHostSettings s, KindData data) {
        return new EngineHost(s, Fixtures.class.getClassLoader(), new EngineHost.Ports(data, KindContracts.NONE, PackUses.NONE, Fixtures::where,
                new Outputs(null), null, Set.of("row-rules"), Function.identity(), id -> false, null));
    }

    /** The kind {@code item}: four items in two groups. */
    static Kinds items() {
        Kinds k = new Kinds();
        k.put("item", new ColumnSet(new String[] {"i1", "i2", "i3", "i4"},
                Map.of("QTY", new double[] {1, 2, 3, 4}, "AMOUNT", new double[] {1.5, 2.5, 3.0, 4.0}),
                Map.of("GROUP", new String[] {"A", "A", "B", "B"}), LocalDate.of(2026, 11, 20)));
        return k;
    }

    /** Kinds held in arrays; counts the column reads. */
    static class Kinds implements KindData {
        final Map<String, ColumnSet> sets = new TreeMap<>();
        final Map<String, String> stamps = new HashMap<>();
        final AtomicInteger reads = new AtomicInteger();

        void put(String kind, ColumnSet set) {
            sets.put(kind, set);
            stamps.put(kind, "v1");
        }

        @Override public boolean serves(String kind) { return sets.containsKey(kind); }
        @Override public Optional<LocalDate> latest(String kind) { return Optional.ofNullable(sets.get(kind)).map(ColumnSet::businessDate); }
        @Override public Optional<String> stamp(String kind, LocalDate date) { return Optional.ofNullable(stamps.get(kind)); }

        @Override
        public Optional<ColumnSet> columns(String kind, Collection<String> paths, LocalDate date) {
            ColumnSet s = sets.get(kind);
            if (s == null) {
                return Optional.empty();
            }
            reads.incrementAndGet();
            Map<String, double[]> n = new HashMap<>();
            Map<String, String[]> t = new HashMap<>();
            for (String p : paths) {
                if (s.numbers().containsKey(p)) {
                    n.put(p, s.numbers().get(p));
                }
                if (s.texts().containsKey(p)) {
                    t.put(p, s.texts().get(p));
                }
            }
            return Optional.of(new ColumnSet(s.ids(), n, t, s.businessDate()));
        }

        @Override
        public boolean vectors(String kind, String column, List<String> texts, LocalDate date, VectorSink sink) {
            return false;
        }
    }

    /** {@code FIELD == 'value'} (the only form these tests write). */
    static RowRule where(String text) {
        String[] p = text.split("==");
        if (p.length != 2) {
            throw new IllegalArgumentException("not an expression over a row: " + text);
        }
        return eq(p[0].strip().replace("@.", ""), p[1].strip().replace("'", ""));
    }

    static RowRule eq(String field, String value) {
        return new RowRule() {
            @Override public boolean all() { return false; }
            @Override public Set<String> fields() { return Set.of(field); }
            @Override public boolean test(Function<String, Object> row) { return value.equals(row.apply(field)); }
            @Override public String text() { return field + " == '" + value + "'"; }
            @Override public Optional<Filter> asFilter() { return Optional.of(new Filter.Eq(field, value)); }
        };
    }

    /** A caller: their row rules by kind and the fields masked for them. */
    static ViewerAccess viewer(String name, Map<String, RowRule> rules, Set<String> masked) {
        return new ViewerAccess() {
            @Override public String actor() { return name; }
            @Override public Set<String> roles() { return Set.of("analyst"); }
            @Override public boolean admin() { return false; }
            @Override public boolean mayOpen(String kind) { return true; }
            @Override public RowRule rows(String kind) { return rules.getOrDefault(kind, RowRule.ALL); }
            @Override public boolean masked(String field) { return masked.contains(field); }
        };
    }
}
