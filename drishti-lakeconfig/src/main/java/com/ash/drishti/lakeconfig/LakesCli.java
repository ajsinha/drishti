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
package com.ash.drishti.lakeconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The lake files from the command line, for {@code drishti.py lake} (tools/lake/lakeconfig.py), so the Python tools use the same reader
 * and rules as the server rather than a second copy of them. Every command writes one JSON document to standard output; a refusal is
 * {@code {"error": "..."}} with exit code 2.
 *
 * <pre>
 * dump           [--dir D]                                every lake and table (environment references resolved)
 * table          [--dir D] --lake L --kind K [--domain X] [--table T]  one table, or the refusal a writer gets when the lake file lacks it
 * check          [--dir D] [--connectors C]               the checks of LAKE_CONFIG.md section 6
 * migrate-config [--dir D] --connectors C [--write]       lake files from old connector files; a dry run unless --write
 * add-entry      [--dir D] --lake L --pack P --kind K [--connector N]   a pack's recommended entry copied into the lake file once
 * </pre>
 */
public final class LakesCli {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private LakesCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    static int run(String[] args, PrintStream out) {
        try {
            out.println(JSON.writeValueAsString(command(args)));
            return 0;
        } catch (IllegalArgumentException | java.io.IOException | java.io.UncheckedIOException e) {
            try {
                out.println(JSON.writeValueAsString(Map.of("error", String.valueOf(e.getMessage()))));
            } catch (java.io.IOException ignored) {
                out.println("{\"error\": \"unprintable\"}");
            }
            return 2;
        }
    }

    static Object command(String[] args) throws java.io.IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException("give a command: dump, table, check, migrate-config or add-entry");
        }
        Map<String, String> o = options(args);
        Path dir = o.containsKey("dir") ? Path.of(o.get("dir")) : Lakes.dir();
        switch (args[0]) {
            case "dump" -> {
                return Map.of("dir", dir.toString(), "lakes", Lakes.read(dir, System::getenv).describe());
            }
            case "table" -> {
                Lake l = Lakes.read(dir, System::getenv).get(need(o, "lake"));
                LakeTable t = l.require(o.get("domain"), need(o, "kind"), o.get("table"));
                Map<String, Object> m = t.describe();
                m.put("location", l.location(t));
                m.put("lake", l.name());
                m.put("root", l.root());
                m.put("storage", l.storage());
                m.put("catalog", l.catalog());
                return m;
            }
            case "check" -> {
                Lakes lakes = Lakes.read(dir, System::getenv);
                Map<String, Map.Entry<String, Map<String, String>>> conns = o.containsKey("connectors") ? connectors(Path.of(o.get("connectors")))
                        : Map.of();
                List<LakeCheck.Finding> f = LakeCheck.check(lakes, conns);
                return Map.of("dir", dir.toString(), "lakes", lakes.all().keySet(), "findings", f,
                        "errors", f.stream().filter(x -> x.level().equals("error")).count());
            }
            case "migrate-config" -> {
                LakeFiles.Migration m = LakeFiles.migrate(Path.of(need(o, "connectors")), dir, o.containsKey("write"));
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("written", o.containsKey("write"));
                r.put("lake-files", m.lakeFiles().keySet().stream().map(Path::toString).toList());
                r.put("connector-files", m.connectorFiles().keySet().stream().map(Path::toString).toList());
                r.put("notes", m.notes());
                if (!o.containsKey("write")) {
                    Map<String, String> texts = new LinkedHashMap<>();
                    m.lakeFiles().forEach((p, t) -> texts.put(p.toString(), t));
                    m.connectorFiles().forEach((p, t) -> texts.put(p.toString(), t));
                    r.put("texts", texts);
                }
                return r;
            }
            case "add-entry" -> {
                String lake = need(o, "lake");
                Path file = dir.resolve(lake + ".yaml");
                Lakes.read(dir, System::getenv).get(lake);
                String kind = need(o, "kind");
                LakeTable t = PackEntry.read(Path.of(need(o, "pack")), kind, o.get("connector")).toTable(kind);
                boolean added = LakeFiles.addTable(file, t);
                return Map.of("lake", lake, "table", t.name(), "added", added, "message", added
                        ? "the pack's recommended entry for " + t.name() + " was copied into " + file + "; edit it there from now on"
                        : file + " has an entry for " + t.name() + " already: it is the truth and was left as it is");
            }
            default -> throw new IllegalArgumentException("unknown command " + args[0] + ": dump, table, check, migrate-config or add-entry");
        }
    }

    /** The Delta and Iceberg connector files of a folder: name to (plugin, settings flattened). */
    static Map<String, Map.Entry<String, Map<String, String>>> connectors(Path dir) throws java.io.IOException {
        Map<String, Map.Entry<String, Map<String, String>>> out = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        }
        for (Path f : files) {
            JsonNode c = LakeFiles.YAML.readTree(Files.readString(f));
            if (c != null && c.isObject()) {
                Map<String, String> settings = new Lakes.Values(f.toString(), k -> "").map(c.path("settings"), "settings");
                out.put(f.getFileName().toString().replaceFirst("\\.yaml$", ""), Map.entry(c.path("plugin").asText(), settings));
            }
        }
        return out;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> o = new LinkedHashMap<>();
        List<String> rest = new ArrayList<>(List.of(args).subList(1, args.length));
        for (int i = 0; i < rest.size(); i++) {
            String a = rest.get(i);
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + a);
            }
            String k = a.substring(2);
            if (k.equals("write")) {
                o.put(k, "true");
            } else if (i + 1 < rest.size()) {
                o.put(k, rest.get(++i));
            } else {
                throw new IllegalArgumentException(a + " needs a value");
            }
        }
        return o;
    }

    private static String need(Map<String, String> o, String k) {
        String v = o.get(k);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("--" + k + " is required");
        }
        return v;
    }
}
