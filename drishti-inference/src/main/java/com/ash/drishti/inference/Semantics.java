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
package com.ash.drishti.inference;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.NodeType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Semantic hints from {@code inference/semantics.yaml}: recognises roles (money, rate, date, tenor, ...) from
 * field names and sample values, and turns names into labels. Immutable after load; role lookups are
 * memoised per field name and value class, so hot paths do no regex work.
 */
public final class Semantics {

    private record RoleRule(Pattern pattern, Role role, boolean fractionOnly, boolean dateOnly) {}

    private final List<RoleRule> rules = new ArrayList<>();
    private final Pattern tenor;
    private final Pattern date;
    private final List<String> idFields = new ArrayList<>();
    private final Map<String, Integer> limits = new ConcurrentHashMap<>();
    private final Map<String, Role> memo = new ConcurrentHashMap<>();
    /** Field name -> label, and word -> spelling (UTI, DV01), from the core and pack semantics files. */
    private static volatile Map<String, String> labels = Map.of();
    private static volatile Map<String, String> acronyms = Map.of();

    private Semantics(JsonNode root, List<JsonNode> packs) {
        List<JsonNode> ordered = new ArrayList<>(packs);
        ordered.add(root);
        StringBuilder tenors = new StringBuilder();
        for (JsonNode src : ordered) {
            addRoles(src);
            String t = src.path("tenorPattern").asText("");
            if (!t.isEmpty()) {
                tenors.append(tenors.isEmpty() ? "" : "|").append("(?:").append(t).append(")");
            }
        }
        List<JsonNode> idOrder = new ArrayList<>();
        idOrder.add(root);
        idOrder.addAll(packs);
        for (JsonNode src : idOrder) {
            src.path("idFields").forEach(n -> {
                if (!idFields.contains(n.asText())) {
                    idFields.add(n.asText());
                }
            });
        }
        tenor = Pattern.compile(tenors.isEmpty() ? "^$" : tenors.toString());
        date = Pattern.compile(root.path("datePattern").asText("^\\d{4}-\\d{2}-\\d{2}"));
        root.path("limits").fields().forEachRemaining(e -> limits.put(e.getKey(), e.getValue().asInt()));
        Map<String, String> l = new java.util.HashMap<>();
        Map<String, String> a = new java.util.HashMap<>();
        for (JsonNode src : ordered.reversed()) {                      // packs win over the core
            src.path("labels").fields().forEachRemaining(e -> l.put(e.getKey(), e.getValue().asText()));
            src.path("acronyms").forEach(n -> a.put(n.asText().toLowerCase(java.util.Locale.ROOT), n.asText()));
        }
        labels = Map.copyOf(l);
        acronyms = Map.copyOf(a);
    }

    private void addRoles(JsonNode root) {
        for (JsonNode r : root.path("roles")) {
            rules.add(new RoleRule(Pattern.compile(r.path("pattern").asText(), Pattern.CASE_INSENSITIVE),
                    new Role(r.path("role").asText(), r.hasNonNull("fmt") ? r.get("fmt").asText() : null,
                            r.hasNonNull("tone") ? r.get("tone").asText() : null, r.path("weight").asInt(10)),
                    r.path("fractionOnly").asBoolean(false), r.path("dateOnly").asBoolean(false)));
        }
    }

    public static Semantics load(String overrideFile) {
        return load(overrideFile, List.of());
    }

    /** The core hints (or the site's replacement), with each pack's roles tried first, in pack order. */
    public static Semantics load(String overrideFile, List<String> packFiles) {
        try {
            ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
            List<JsonNode> packs = new ArrayList<>();
            for (String f : packFiles) {
                try (InputStream in = Files.newInputStream(Path.of(f))) {
                    packs.add(yaml.readTree(in));
                }
            }
            if (overrideFile != null && !overrideFile.isBlank() && Files.isRegularFile(Path.of(overrideFile))) {
                try (InputStream in = Files.newInputStream(Path.of(overrideFile))) {
                    return new Semantics(yaml.readTree(in), packs);
                }
            }
            try (InputStream in = Semantics.class.getClassLoader().getResourceAsStream("inference/semantics.yaml")) {
                return new Semantics(yaml.readTree(in), packs);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Semantics defaults() {
        return load(null);
    }

    /** The role of field {@code name} holding {@code sample}. */
    public Role role(String name, DataNode sample) {
        String cls = sample.type() == NodeType.NUMBER ? (Math.abs(sample.asDouble()) < 1 ? "f" : "n")
                : sample.type() == NodeType.STRING ? (isDate(sample.asText()) ? "d" : "s") : sample.type().name();
        return memo.computeIfAbsent(name + '\u0000' + cls, k -> compute(name, cls));
    }

    private Role compute(String name, String cls) {
        boolean number = cls.equals("f") || cls.equals("n");
        for (RoleRule r : rules) {
            if (!r.pattern.matcher(name).find()) {
                continue;
            }
            if (r.dateOnly && !cls.equals("d")) {
                continue;
            }
            if (r.fractionOnly && !cls.equals("f")) {
                continue;
            }
            if (r.role.numeric() && !number) {
                continue;
            }
            return r.role;
        }
        if (cls.equals("d")) {
            return new Role("date", "date", null, 40);
        }
        return number ? new Role("number", "amount0", null, 30) : Role.PLAIN;
    }

    public boolean isTenor(String s) {
        return s != null && tenor.matcher(s).matches();
    }

    public boolean isDate(String s) {
        return s != null && date.matcher(s).find();
    }

    public List<String> idFields(String kind) {
        String camel = camel(kind);
        return idFields.stream().map(f -> f.replace("{kind}", camel)).toList();
    }

    public int limit(String name, int fallback) {
        return limits.getOrDefault(name, fallback);
    }

    /**
     * The label for a field name when nobody gave one: an explicit {@code labels:} entry, else the name in words
     * with the vocabulary's spellings: {@code paymentLag} becomes {@code Payment lag}, {@code dv01ByTenor} becomes
     * {@code DV01 by tenor} and {@code uti} becomes {@code UTI} when the pack lists those acronyms.
     */
    public static String humanize(String name) {
        String explicit = labels.get(name);
        if (explicit != null) {
            return explicit;
        }
        String[] words = words(name).split(" ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            String spelled = acronyms.get(w.toLowerCase(java.util.Locale.ROOT));
            if (spelled != null) {
                w = spelled;
            } else if (i == 0 && !w.isEmpty()) {
                w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            }
            out.append(i == 0 ? "" : " ").append(w);
        }
        return out.toString();
    }

    private static String words(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_' || c == '-') {
                sb.append(' ');
            } else if (Character.isUpperCase(c) && i > 0 && !Character.isUpperCase(name.charAt(i - 1))) {
                sb.append(' ').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim().replaceAll(" +", " ");
    }

    static String camel(String kind) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : kind.toCharArray()) {
            if (c == '-' || c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : Character.toLowerCase(c));
                up = false;
            }
        }
        return sb.toString();
    }
}
