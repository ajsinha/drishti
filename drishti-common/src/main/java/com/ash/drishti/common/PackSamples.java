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
package com.ash.drishti.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A pack's sample documents ({@code <pack>/samples/}), in either layout: one JSON Lines file per kind ({@code <kind>.jsonl}, one
 * document a line, split into {@code <kind>.part-001.jsonl} ... only when large), as the shipped and new packs are written, or one
 * file per document ({@code <kind>/<id>.json}), as older packs and the retired example packs are. {@code catalog.json} lists every
 * sample's kind and id either way; a JSON Lines document's id is the catalogue entry at its position when the document holds it (the
 * writers keep the catalogue's order), else the first top-level value the catalogue lists as an id of its kind.
 * The writers are tools/packgen/common/packsamples.py and the pack wizard's.
 */
public final class PackSamples {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PART = Pattern.compile("\\.part-\\d{3}\\.jsonl$");

    private PackSamples() {
    }

    /** The JSON Lines file(s) of a kind, in order; empty when the kind has none (the older layout, or no samples). */
    public static List<Path> kindFiles(Path samples, String kind) throws IOException {
        Path one = samples.resolve(kind + ".jsonl");
        if (Files.isRegularFile(one)) {
            return List.of(one);
        }
        if (!Files.isDirectory(samples)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(samples)) {
            return s.filter(f -> {
                String n = f.getFileName().toString();
                return n.startsWith(kind + ".part-") && PART.matcher(n).find() && n.length() == kind.length() + ".part-000.jsonl".length();
            }).sorted().toList();
        }
    }

    /**
     * The documents of a kind as JSON text, by id: {@code ids} are the catalogue's ids of that kind (a JSON Lines document is matched to
     * one by its values; with no ids every line is kept under {@code <kind>-<line>}), or from {@code <kind>/<id>.json} in the older layout.
     * At most {@code limit} documents (0: all).
     */
    public static Map<String, String> documents(Path folder, String kind, Set<String> ids, int limit) throws IOException {
        Path samples = folder.toAbsolutePath().normalize();
        Map<String, String> out = new LinkedHashMap<>();
        List<Path> files = kindFiles(samples, kind);
        if (!files.isEmpty()) {
            List<String> inOrder = catalogue(samples, kind);                 // the catalogue's order is the lines' order
            Set<String> known = inOrder.isEmpty() ? ids : new java.util.HashSet<>(inOrder);
            Map<String, String> seen = new LinkedHashMap<>();
            int n = 0;
            for (Path f : files) {
                try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    for (String line = r.readLine(); line != null; line = r.readLine()) {
                        if (line.isBlank()) {
                            continue;
                        }
                        n++;
                        String id = known.isEmpty() ? kind + "-" + n : idAt(JSON.readTree(line), n <= inOrder.size() ? inOrder.get(n - 1) : null, known, seen);
                        if (id == null || seen.putIfAbsent(id, line) != null || (!ids.isEmpty() && !ids.contains(id))) {
                            continue;
                        }
                        out.put(id, line);
                        if (limit > 0 && out.size() >= limit) {
                            return out;
                        }
                    }
                }
            }
            return out;
        }
        Path dir = samples.resolve(kind).normalize();
        if (!dir.startsWith(samples) || !Files.isDirectory(dir)) {
            return out;
        }
        if (!ids.isEmpty()) {
            for (String id : ids) {
                Path f = dir.resolve(id + ".json").normalize();
                if (f.startsWith(dir) && Files.isRegularFile(f)) {
                    out.put(id, Files.readString(f));
                    if (limit > 0 && out.size() >= limit) {
                        break;
                    }
                }
            }
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String name = f.getFileName().toString();
                out.put(name.substring(0, name.length() - 5), Files.readString(f));
                if (limit > 0 && out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }

    /**
     * The first sample of a kind (in the files' order, either layout) that {@code test} accepts, as its id and JSON text; read line by
     * line and stopped at the first match, so a large JSON Lines file is not read whole. A line that is not JSON is skipped.
     */
    public static java.util.Optional<Map.Entry<String, String>> first(Path folder, String kind, java.util.function.Predicate<JsonNode> test)
            throws IOException {
        Path samples = folder.toAbsolutePath().normalize();
        List<Path> files = kindFiles(samples, kind);
        if (files.isEmpty()) {
            for (Map.Entry<String, String> e : documents(samples, kind, Set.of(), 0).entrySet()) {
                if (accepts(test, e.getValue())) {
                    return java.util.Optional.of(e);
                }
            }
            return java.util.Optional.empty();
        }
        List<String> inOrder = catalogue(samples, kind);
        Set<String> known = new java.util.HashSet<>(inOrder);
        Map<String, String> taken = new LinkedHashMap<>();
        int n = 0;
        for (Path f : files) {
            try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                for (String line = r.readLine(); line != null; line = r.readLine()) {
                    if (line.isBlank()) {
                        continue;
                    }
                    n++;
                    JsonNode doc;
                    try {
                        doc = JSON.readTree(line);
                    } catch (IOException e) {
                        continue;
                    }
                    String id = known.isEmpty() ? kind + "-" + n : idAt(doc, n <= inOrder.size() ? inOrder.get(n - 1) : null, known, taken);
                    if (id == null || taken.putIfAbsent(id, "") != null) {
                        continue;
                    }
                    if (test.test(doc)) {
                        return java.util.Optional.of(Map.entry(id, line));
                    }
                }
            }
        }
        return java.util.Optional.empty();
    }

    private static boolean accepts(java.util.function.Predicate<JsonNode> test, String json) {
        try {
            return test.test(JSON.readTree(json));
        } catch (IOException e) {
            return false;
        }
    }

    /** One sample's JSON text, by kind and id (either layout); empty when there is none. */
    public static java.util.Optional<String> document(Path samples, String kind, String id) throws IOException {
        return java.util.Optional.ofNullable(documents(samples, kind, new java.util.LinkedHashSet<>(List.of(id)), 1).get(id));
    }

    /**
     * A line's id: the catalogue entry at its position when the document holds it as a top-level value (the writers keep the catalogue's
     * order), else the first top-level value that is an id of the kind and not yet taken, else the entry at its position.
     */
    static String idAt(JsonNode doc, String positional, Set<String> ids, Map<String, String> taken) {
        if (positional != null && !taken.containsKey(positional)) {
            for (Iterator<JsonNode> it = doc.elements(); it.hasNext();) {
                JsonNode v = it.next();
                if ((v.isTextual() || v.isIntegralNumber()) && positional.equals(v.asText())) {
                    return positional;
                }
            }
        }
        for (Iterator<JsonNode> it = doc.elements(); it.hasNext();) {
            JsonNode v = it.next();
            if ((v.isTextual() || v.isIntegralNumber()) && ids.contains(v.asText()) && !taken.containsKey(v.asText())) {
                return v.asText();
            }
        }
        return positional != null && !taken.containsKey(positional) ? positional : null;
    }

    /** The ids {@code catalog.json} lists for a kind, in its order (empty with no catalogue). */
    static List<String> catalogue(Path samples, String kind) throws IOException {
        Path f = samples.resolve("catalog.json");
        List<String> out = new ArrayList<>();
        if (Files.isRegularFile(f)) {
            for (JsonNode e : JSON.readTree(f.toFile())) {
                if (kind.equals(e.path("kind").asText())) {
                    out.add(e.path("id").asText());
                }
            }
        }
        return out;
    }

    /** The kinds that have samples in this folder, in either layout. */
    public static List<String> kinds(Path samples) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(samples)) {
            return out;
        }
        try (Stream<Path> s = Files.list(samples)) {
            for (Path f : s.sorted().toList()) {
                String n = f.getFileName().toString();
                String kind = Files.isDirectory(f) ? n : n.endsWith(".jsonl") ? PART.matcher(n).replaceFirst("").replaceFirst("\\.jsonl$", "") : null;
                if (kind != null && !out.contains(kind)) {
                    out.add(kind);
                }
            }
        }
        return out;
    }
}
