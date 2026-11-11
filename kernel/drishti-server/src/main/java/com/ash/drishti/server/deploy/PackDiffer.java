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
package com.ash.drishti.server.deploy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.yaml.snakeyaml.Yaml;

/**
 * What changes between two versions of a pack, and what it breaks: the Java twin of {@code diff_packs} in
 * tools/packdiff.py (the CLI's {@code pack diff}). The two must agree finding for finding, in the same order and words;
 * {@code tools/testdata/packdiff} holds the shared cases both test suites read. Levels:
 *
 * <ul>
 *   <li>{@code breaking}: a kind or a mnemonic removed or renamed (monitors, workspaces and alerts name them)</li>
 *   <li>{@code selection}: a Sutra added or removed, or its match where/priority changed (documents get other screens)</li>
 *   <li>{@code layout}: the data layout changed (connectors, routes, ingest, columns)</li>
 *   <li>{@code change}: Sutra content, about text or glossary changed</li>
 * </ul>
 */
public final class PackDiffer {

    public static final List<String> LEVELS = List.of("breaking", "selection", "layout", "change");

    /** One difference. */
    public record Finding(String level, String what, String name, String detail) {}

    /** A pack read from a folder. */
    public record Loaded(Map<String, Object> meta, Map<String, Sutra> sutras, Map<String, Object> about) {}

    /** One Sutra file: its path in the pack, text and parsed document. */
    public record Sutra(String file, String text, Map<String, Object> doc) {}

    private PackDiffer() {}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static Map<String, Object> yaml(Path p) throws IOException {
        Object o = new Yaml().load(Files.readString(p, StandardCharsets.UTF_8));
        return map(o);
    }

    /** Reads {@code pack.yaml}, every {@code <sutras>/**}{@code /*.sutra.yaml}, and {@code config/about.yaml}. */
    public static Loaded load(Path root) throws IOException {
        Map<String, Object> meta = yaml(root.resolve("pack.yaml"));
        Map<String, Sutra> sutras = new LinkedHashMap<>();
        Path sdir = root.resolve(py(meta.getOrDefault("sutras", "sutras")));
        if (Files.isDirectory(sdir)) {
            List<Path> files;
            try (Stream<Path> s = Files.walk(sdir)) {
                files = s.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(".sutra.yaml"))
                        .sorted(Comparator.comparing((Path f) -> root.relativize(f).toString().split("[/\\\\]"), PackDiffer::byParts)).toList();
            }
            for (Path f : files) {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Map<String, Object> d = map(new Yaml().load(text));
                Object nm = d.get("sutra");
                String name = nm == null || "".equals(nm) ? f.getFileName().toString() : py(nm);
                sutras.put(name, new Sutra(root.relativize(f).toString().replace('\\', '/'), text, d));
            }
        }
        Path ab = root.resolve("config").resolve("about.yaml");
        return new Loaded(meta, sutras, Files.isRegularFile(ab) ? yaml(ab) : new LinkedHashMap<>());
    }

    private static int byParts(String[] a, String[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int c = a[i].compareTo(b[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    /** Python's {@code str()} of a YAML value. */
    static String py(Object o) {
        if (o == null) {
            return "None";
        }
        if (o instanceof Boolean b) {
            return b ? "True" : "False";
        }
        return String.valueOf(o);
    }

    /** Python's {@code repr()} of a YAML scalar. */
    static String repr(Object o) {
        if (o instanceof String s) {
            boolean dq = s.contains("'") && !s.contains("\"");
            String body = s.replace("\\", "\\\\").replace("\n", "\\n");
            return dq ? "\"" + body + "\"" : "'" + body.replace("'", "\\'") + "'";
        }
        return py(o);
    }

    private static boolean truthy(Object o) {
        return o != null && !"".equals(o) && !Boolean.FALSE.equals(o) && !(o instanceof Number n && n.doubleValue() == 0)
                && !(o instanceof Map<?, ?> m && m.isEmpty()) && !(o instanceof List<?> l && l.isEmpty());
    }

    private static Map<String, Object> match(Sutra s) {
        Map<String, Object> m = map(s.doc().get("match"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", m.get("kind"));
        out.put("where", m.get("where"));
        out.put("priority", m.get("priority"));
        return out;
    }

    private static String orAny(Object where) {
        return truthy(where) ? py(where) : "any";
    }

    private static List<String> panels(Sutra s) {
        List<String> out = new ArrayList<>();
        for (Object p : list(s.doc().get("panels"))) {
            if (p instanceof Map<?, ?> m) {
                out.add(py(m.get("id")));
            }
        }
        return out;
    }

    public static List<Finding> diff(Loaded old, Loaded now) {
        List<Finding> out = new ArrayList<>();
        Map<String, Object> om = old.meta();
        Map<String, Object> nm = now.meta();
        if (!py(om.get("version")).equals(py(nm.get("version")))) {
            out.add(new Finding("change", "version", py(nm.get("pack")), py(om.get("version")) + " -> " + py(nm.get("version"))));
        }

        List<Object> ok = list(om.get("kinds"));
        List<Object> nk = list(nm.get("kinds"));
        Map<String, Object> omn = map(om.get("mnemonics"));
        Map<String, Object> nmn = map(nm.get("mnemonics"));
        Map<Object, Object> renamedKinds = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : omn.entrySet()) {
            Map<String, Object> o = map(e.getValue());
            Object nv = nmn.get(e.getKey());
            if (truthy(nv)) {
                Map<String, Object> n = map(nv);
                if (!Objects.equals(o.get("kind"), n.get("kind")) && !nk.contains(o.get("kind")) && !ok.contains(n.get("kind"))) {
                    renamedKinds.put(o.get("kind"), n.get("kind"));
                }
            }
        }
        for (Object k : ok) {
            if (renamedKinds.containsKey(k)) {
                out.add(new Finding("breaking", "kind renamed", py(k), py(k) + " -> " + py(renamedKinds.get(k)) + " (monitors, workspaces and alerts that name '"
                        + py(k) + "' stop matching)"));
            } else if (!nk.contains(k)) {
                out.add(new Finding("breaking", "kind removed", py(k), "monitors, workspaces and alerts that name it stop working"));
            }
        }
        for (Object k : nk) {
            if (!ok.contains(k) && !renamedKinds.containsValue(k)) {
                out.add(new Finding("change", "kind added", py(k), ""));
            }
        }
        Map<Object, String> kindOfOld = new LinkedHashMap<>();
        Map<Object, String> kindOfNew = new LinkedHashMap<>();
        omn.forEach((m, v) -> kindOfOld.put(map(v).get("kind"), m));
        nmn.forEach((m, v) -> kindOfNew.put(map(v).get("kind"), m));
        for (Map.Entry<String, Object> e : omn.entrySet()) {
            String m = e.getKey();
            Map<String, Object> v = map(e.getValue());
            Object k = v.get("kind");
            if (nmn.containsKey(m) && Objects.equals(map(nmn.get(m)).get("kind"), k)) {
                if (!Objects.equals(map(nmn.get(m)).get("label"), v.get("label"))) {
                    out.add(new Finding("change", "mnemonic label", m, repr(v.get("label")) + " -> " + repr(map(nmn.get(m)).get("label"))));
                }
                continue;
            }
            if (kindOfNew.containsKey(k) && !kindOfNew.get(k).equals(m)) {
                out.add(new Finding("breaking", "mnemonic renamed", m, m + " -> " + kindOfNew.get(k) + " for kind " + py(k) + " (typed commands and saved links use it)"));
            } else if (!nmn.containsKey(m) && renamedKinds.containsKey(k) && kindOfNew.containsKey(renamedKinds.get(k))) {
                continue;
            } else if (!nmn.containsKey(m)) {
                out.add(new Finding("breaking", "mnemonic removed", m, "kind " + py(k)));
            }
        }
        for (Map.Entry<String, Object> e : nmn.entrySet()) {
            Object k = map(e.getValue()).get("kind");
            if (!omn.containsKey(e.getKey()) && !kindOfOld.containsKey(k) && !renamedKinds.containsValue(k)) {
                out.add(new Finding("change", "mnemonic added", e.getKey(), "kind " + py(k)));
            }
        }

        Map<String, Sutra> osu = old.sutras();
        Map<String, Sutra> nsu = now.sutras();
        for (String n : new TreeSet<>(osu.keySet())) {
            if (!nsu.containsKey(n)) {
                out.add(new Finding("selection", "sutra removed", n, osu.get(n).file() + ": its documents fall to another Sutra (match " + orAny(match(osu.get(n)).get("where")) + ")"));
            }
        }
        for (String n : new TreeSet<>(nsu.keySet())) {
            if (!osu.containsKey(n)) {
                Map<String, Object> mm = match(nsu.get(n));
                out.add(new Finding("selection", "sutra added", n, nsu.get(n).file() + ": match " + orAny(mm.get("where")) + ", priority " + py(mm.get("priority"))));
            }
        }
        for (String n : new TreeSet<>(osu.keySet())) {
            if (!nsu.containsKey(n)) {
                continue;
            }
            Sutra a = osu.get(n);
            Sutra b = nsu.get(n);
            if (a.text().equals(b.text())) {
                continue;
            }
            Map<String, Object> ma = match(a);
            Map<String, Object> mb = match(b);
            if (!ma.equals(mb)) {
                List<String> parts = new ArrayList<>();
                for (String k : List.of("kind", "where", "priority")) {
                    if (!Objects.equals(ma.get(k), mb.get(k))) {
                        parts.add(k + " " + repr(ma.get(k)) + " -> " + repr(mb.get(k)));
                    }
                }
                out.add(new Finding("selection", "screen selection changes", n, String.join("; ", parts)));
            }
            List<String> pa = panels(a);
            List<String> pb = panels(b);
            List<String> detail = new ArrayList<>();
            TreeSet<String> gone = new TreeSet<>(pa);
            gone.removeAll(pb);
            TreeSet<String> added = new TreeSet<>(pb);
            added.removeAll(pa);
            if (!gone.isEmpty()) {
                detail.add("panels removed " + String.join(", ", gone));
            }
            if (!added.isEmpty()) {
                detail.add("panels added " + String.join(", ", added));
            }
            if (detail.isEmpty()) {
                detail.add("content changed");
            }
            out.add(new Finding("change", "sutra changed", n, String.join("; ", detail)));
        }

        Map<String, Object> oab = map(old.about().get("kinds"));
        Map<String, Object> nab = map(now.about().get("kinds"));
        TreeSet<String> aboutKinds = new TreeSet<>(oab.keySet());
        aboutKinds.addAll(nab.keySet());
        for (String k : aboutKinds) {
            Object ao = oab.get(k);
            Object bo = nab.get(k);
            if (ao == null || bo == null) {
                out.add(new Finding("change", "about " + (ao == null ? "added" : "removed"), k, ""));
                continue;
            }
            Map<String, Object> a = map(ao);
            Map<String, Object> b = map(bo);
            for (String fld : List.of("title", "about")) {
                if (!Objects.equals(a.get(fld), b.get(fld))) {
                    out.add(new Finding("change", "about " + fld, k, "text changed"));
                }
            }
            Map<String, Object> ga = map(a.get("glossary"));
            Map<String, Object> gb = map(b.get("glossary"));
            TreeSet<String> fields = new TreeSet<>(ga.keySet());
            fields.addAll(gb.keySet());
            for (String f : fields) {
                if (!ga.containsKey(f)) {
                    out.add(new Finding("change", "glossary added", k + "." + f, ""));
                } else if (!gb.containsKey(f)) {
                    out.add(new Finding("change", "glossary removed", k + "." + f, ""));
                } else if (!Objects.equals(ga.get(f), gb.get(f))) {
                    out.add(new Finding("change", "glossary changed", k + "." + f, ""));
                }
            }
            if (!Objects.equals(map(a.get("panels")), map(b.get("panels")))) {
                out.add(new Finding("change", "panel notes", k, "changed"));
            }
        }

        for (String key : List.of("connectors", "routes", "ingest", "columns")) {
            Map<String, Object> a = map(om.get(key));
            Map<String, Object> b = map(nm.get(key));
            TreeSet<String> names = new TreeSet<>(a.keySet());
            names.addAll(b.keySet());
            for (String n : names) {
                if (!a.containsKey(n)) {
                    out.add(new Finding("layout", key + " added", n, ""));
                } else if (!b.containsKey(n)) {
                    out.add(new Finding("layout", key + " removed", n, ""));
                } else if (!Objects.equals(a.get(n), b.get(n))) {
                    String detail = "";
                    if (key.equals("columns")) {
                        TreeSet<String> ca = new TreeSet<>(list(a.get(n)).stream().map(PackDiffer::py).toList());
                        TreeSet<String> cb = new TreeSet<>(list(b.get(n)).stream().map(PackDiffer::py).toList());
                        TreeSet<String> gone = new TreeSet<>(ca);
                        gone.removeAll(cb);
                        TreeSet<String> fresh = new TreeSet<>(cb);
                        fresh.removeAll(ca);
                        List<String> parts = new ArrayList<>();
                        if (!gone.isEmpty()) {
                            parts.add("columns removed " + String.join(", ", gone));
                        }
                        if (!fresh.isEmpty()) {
                            parts.add("columns added " + String.join(", ", fresh));
                        }
                        detail = parts.isEmpty() ? "order changed" : String.join("; ", parts);
                    }
                    out.add(new Finding("layout", key + " changed", n, detail));
                }
            }
        }
        return out;
    }

    /** Findings counted per level, in level order. */
    public static Map<String, Integer> counts(List<Finding> found) {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (String lv : LEVELS) {
            c.put(lv, (int) found.stream().filter(f -> f.level().equals(lv)).count());
        }
        return c;
    }
}
