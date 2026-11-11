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
package com.ash.drishti.packs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * What a pack offers Calc, the console's Python panel: {@code python: { enabled: true, snippets: [ {title, description,
 * kinds, code} ] }} in {@code pack.yaml}, and snippet files {@code <pack>/python/*.py}, each starting with comment lines
 * {@code # title: …}, {@code # description: …} and {@code # kinds: trade, netting-set} (after the copyright header).
 * Calc is offered on a view when the pack that owns the view's kind enables it. Immutable.
 *
 * @param enabled the pack switches Calc on for its kinds
 * @param snippets starter code, from {@code pack.yaml} first, then the files by name
 */
public record PackPython(boolean enabled, List<Snippet> snippets) {

    /**
     * A starter snippet.
     *
     * @param title shown in the snippet list
     * @param description one sentence
     * @param kinds the kinds it is offered on; empty: every kind of its pack
     * @param code the Python
     * @param file where it came from ({@code python/var_es.py}), or "" for one in {@code pack.yaml}
     */
    public record Snippet(String title, String description, List<String> kinds, String code, String file) {
        public Snippet {
            title = title == null ? "" : title;
            description = description == null ? "" : description;
            kinds = kinds == null ? List.of() : List.copyOf(kinds);
            code = code == null ? "" : code;
            file = file == null ? "" : file;
        }
    }

    /** A snippet file larger than this is skipped (snippets are starters, not programs). */
    static final long MAX_FILE_BYTES = 64 * 1024;
    /** At most this many snippets per pack. */
    static final int MAX_SNIPPETS = 100;
    private static final List<String> KEYS = List.of("title", "description", "kinds");

    public PackPython {
        snippets = snippets == null ? List.of() : List.copyOf(snippets);
    }

    public static final PackPython NONE = new PackPython(false, List.of());

    /** Reads a pack's {@code python:} key and its {@code python/} folder (or the folder {@code python.dir} names). */
    public static PackPython of(Pack pack) {
        Map<String, Object> m = map(pack.manifest().get("python"));
        if (m.isEmpty() && !Files.isDirectory(pack.resolve("python"))) {
            return NONE;
        }
        List<Snippet> out = new ArrayList<>();
        if (m.get("snippets") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> s = map(o);
                if (s.get("code") != null) {
                    out.add(new Snippet(text(s.get("title"), "Snippet " + (out.size() + 1)), text(s.get("description"), ""), kinds(s.get("kinds")),
                            String.valueOf(s.get("code")), ""));
                }
            }
        }
        Path dir = pack.resolve(text(m.get("dir"), "python"));
        if (dir.startsWith(pack.dir()) && Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".py")).sorted().toList()) {
                    if (Files.size(f) <= MAX_FILE_BYTES) {
                        out.add(file(f, pack.dir().relativize(f).toString().replace('\\', '/')));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + dir, e);
            }
        }
        boolean enabled = Boolean.parseBoolean(String.valueOf(m.getOrDefault("enabled", "false")));
        return new PackPython(enabled, out.size() > MAX_SNIPPETS ? out.subList(0, MAX_SNIPPETS) : out);
    }

    /** Whether a snippet of the pack applies to a kind: it names it, or it names none and the pack owns the kind. */
    public static boolean appliesTo(Snippet s, String kind, List<String> packKinds) {
        return s.kinds().isEmpty() ? packKinds.contains(kind) : s.kinds().contains(kind);
    }

    /**
     * A snippet file: the leading comment lines give its title, description and kinds; the code is what follows them.
     * Comment lines before the last of those keys (the copyright header) are not part of the code.
     */
    static Snippet file(Path f, String rel) throws IOException {
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        Map<String, String> meta = new java.util.HashMap<>();
        int body = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (!line.isEmpty() && !line.startsWith("#")) {
                break;
            }
            String content = line.startsWith("#") ? line.substring(1).strip() : "";
            int colon = content.indexOf(':');
            String key = colon > 0 ? content.substring(0, colon).strip().toLowerCase(Locale.ROOT) : "";
            if (KEYS.contains(key)) {
                meta.put(key, content.substring(colon + 1).strip());
                body = i + 1;
            }
        }
        while (body < lines.size() && lines.get(body).isBlank()) {
            body++;
        }
        String stem = f.getFileName().toString().replaceFirst("\\.py$", "");
        String code = String.join("\n", lines.subList(Math.min(body, lines.size()), lines.size()));
        return new Snippet(meta.getOrDefault("title", stem), meta.getOrDefault("description", ""), kinds(meta.get("kinds")),
                code.endsWith("\n") || code.isEmpty() ? code : code + "\n", rel);
    }

    private static List<String> kinds(Object o) {
        if (o instanceof List<?> l) {
            return l.stream().map(x -> String.valueOf(x).trim()).filter(x -> !x.isEmpty()).toList();
        }
        if (o == null || String.valueOf(o).isBlank()) {
            return List.of();
        }
        return Stream.of(String.valueOf(o).split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static String text(Object o, String otherwise) {
        return o == null || String.valueOf(o).isBlank() ? otherwise : String.valueOf(o).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> mm ? (Map<String, Object>) mm : Map.of();
    }
}
