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
package com.ash.drishti.server.ui;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The chip and board documents as the Chip Store reads them (UI_BOARD.md sections 2 and 3): YAML or JSON to a map, the header fields,
 * the uses of a board, the chip publish checks the store needs (manual complete, pins declared, every out pin driven, the examples
 * readable and using the chip) and the {@code compatible-with} verification of section 9.3. The full board check (BC-01 to BC-11) is
 * the grammar's (phase P1); this class is the store's own minimum and its single place to call it from.
 */
public final class UiDocs {

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    static final Pattern NAME = com.ash.drishti.rachana.ui.ChipRef.NAME;
    static final Pattern USE = Pattern.compile("([a-z][a-z0-9-]{0,63})@([1-9][0-9]{0,8})");
    static final List<String> MANUAL_KEYS = List.of("purpose", "pins", "behaviour", "defaults", "limits", "examples", "not-for", "accessibility");
    static final List<String> TYPES = List.of("id", "text", "number", "bool", "enum", "date", "datetime", "period", "measure", "dimension",
            "filter", "selection", "document", "rowset");

    private UiDocs() {}

    /** A name of a chip or a board: lower-case letters, digits and hyphens, up to 64. */
    public static String name(String s, String what) {
        if (s == null || !NAME.matcher(s).matches()) {
            throw new DrishtiException(ErrorCode.UI_DOCUMENT_INVALID, "'" + s + "' is not a " + what + " name: a lower-case letter, then letters, digits and hyphens, up to 64");
        }
        return s;
    }

    /** Reads a YAML (or JSON) document to a map; {@code DRS-2070} when it is not one. */
    public static Map<String, Object> read(String text) {
        if (text == null || text.isBlank()) {
            throw new DrishtiException(ErrorCode.UI_DOCUMENT_INVALID, "the document is empty");
        }
        try {
            Map<String, Object> m = YAML.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {});
            if (m == null) {
                throw new DrishtiException(ErrorCode.UI_DOCUMENT_INVALID, "the document is empty");
            }
            return m;
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.UI_DOCUMENT_INVALID, "not YAML or JSON: " + (e instanceof com.fasterxml.jackson.core.JsonProcessingException j ? j.getOriginalMessage() : e.getMessage()));
        }
    }

    public static String write(Map<String, Object> doc) {
        try {
            return YAML.writeValueAsString(doc);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    static List<?> list(Object o) {
        return o instanceof List<?> l ? l : o == null ? List.of() : List.of(o);
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static int version(Map<String, Object> doc) {
        Object v = doc.get("version");
        try {
            int n = v instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(v));
            if (n > 0) {
                return n;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new DrishtiException(ErrorCode.UI_DOCUMENT_INVALID, "'version' must be a positive integer");
    }

    /** The header of a chip document: required keys present, the name well formed; answers the name. */
    public static String chipHeader(Map<String, Object> doc) {
        for (String k : List.of("rachana", "chip", "version", "title", "category", "manual", "pins", "body")) {
            if (!doc.containsKey(k)) {
                throw new DrishtiException(ErrorCode.UI_DOCUMENT_INVALID, "a chip document needs '" + k + "'");
            }
        }
        version(doc);
        return name(str(doc.get("chip")), "chip");
    }

    /** The in pins of a chip (name to declaration). */
    static Map<String, Object> inPins(Map<String, Object> chip) {
        return map(map(chip.get("pins")).get("in"));
    }

    static Map<String, Object> outPins(Map<String, Object> chip) {
        return map(map(chip.get("pins")).get("out"));
    }

    /** The grammar's reading of a chip document (P1, rule BC-01): its errors as text, empty when it reads cleanly. */
    public static List<String> chipParseProblems(String text) {
        return errors(com.ash.drishti.rachana.ui.UiDocuments.parseChip(text).problems());
    }

    /** The grammar's reading of a board document (BC-01); {@code fragment} for a manual example. */
    public static List<String> boardParseProblems(String text, boolean fragment) {
        if (!fragment) {
            return errors(com.ash.drishti.rachana.ui.UiDocuments.parseBoard(text).problems());
        }
        List<com.ash.drishti.rachana.ui.UiProblem> p = new ArrayList<>();
        try {
            com.ash.drishti.rachana.ui.UiDocuments.board(com.ash.drishti.rachana.ui.UiDocuments.tree(text), "", p, true);
        } catch (com.ash.drishti.rachana.ui.UiDocumentException e) {
            p.addAll(e.problems());
        }
        return errors(p);
    }

    private static List<String> errors(List<com.ash.drishti.rachana.ui.UiProblem> problems) {
        return problems.stream().filter(com.ash.drishti.rachana.ui.UiProblem::error).map(Object::toString).toList();
    }

    /**
     * The publish checks the store applies to a chip document (section 2 rules and 9.1): header, a complete manual with at least one
     * example, typed pins, every out pin driven by an {@code emits:} or {@code exposes:} of the body and every {@code emits:} naming a
     * declared out pin, no source named in the body, and every example a board fragment that uses this chip. Empty when it passes.
     */
    public static List<String> publishProblems(Map<String, Object> chip) {
        List<String> out = new ArrayList<>();
        String name;
        try {
            name = chipHeader(chip);
        } catch (DrishtiException e) {
            return List.of(e.getMessage());
        }
        out.addAll(chipParseProblems(write(chip)));
        Map<String, Object> manual = map(chip.get("manual"));
        for (String k : MANUAL_KEYS) {
            Object v = manual.get(k);
            if (v == null || String.valueOf(v).isBlank()) {
                out.add("the manual has no '" + k + "'");
            }
        }
        Map<String, Object> in = inPins(chip);
        Map<String, Object> outs = outPins(chip);
        for (Map.Entry<String, Object> e : in.entrySet()) {
            pinType(e.getKey(), map(e.getValue()), out);
            if (!List.of("data", "control").contains(str(map(e.getValue()).get("role")))) {
                out.add("in pin '" + e.getKey() + "' needs a role: data or control");
            }
        }
        for (Map.Entry<String, Object> e : outs.entrySet()) {
            pinType(e.getKey(), map(e.getValue()), out);
        }
        Map<String, Object> pinsManual = map(manual.get("pins"));
        for (String p : in.keySet()) {
            if (!pinsManual.containsKey(p)) {
                out.add("the manual does not describe pin '" + p + "'");
            }
        }
        for (String p : outs.keySet()) {
            if (!pinsManual.containsKey(p)) {
                out.add("the manual does not describe pin '" + p + "'");
            }
        }
        String body = write(map(chip.get("body")));
        Set<String> driven = new LinkedHashSet<>();
        Matcher em = Pattern.compile("emits:\\s*\"?([a-z0-9-]+)").matcher(body);
        while (em.find()) {
            driven.add(em.group(1));
            if (!outs.containsKey(em.group(1))) {
                out.add("the body emits '" + em.group(1) + "', which is not a declared out pin");
            }
        }
        for (Map.Entry<String, Object> e : map(map(chip.get("body"))).entrySet()) {
            collectExposes(e.getValue(), driven);
        }
        for (String o : outs.keySet()) {
            if (!driven.contains(o)) {
                out.add("out pin '" + o + "' is driven by no emits: or exposes: in the body");
            }
        }
        if (Pattern.compile("\\b(source|model|dataset|connector|url):\\s*[\"']?[a-zA-Z]").matcher(body).find()) {
            out.add("the body names a source, model, dataset, connector or URL; a chip reads only its pins");
        }
        List<?> examples = list(manual.get("examples"));
        if (examples.isEmpty()) {
            out.add("the manual needs at least one example");
        }
        int i = 0;
        for (Object o : examples) {
            i++;
            int idx = i;
            String board = str(map(o).get("board"));
            if (board == null) {
                out.add("example " + i + " has no 'board'");
                continue;
            }
            boardParseProblems(board, true).forEach(x -> out.add("example " + idx + ": " + x));
            try {
                Map<String, Object> b = read(board);
                boolean uses = uses(b).values().stream().anyMatch(u -> u.startsWith(name + "@"));
                if (!uses) {
                    out.add("example " + i + " does not use " + name);
                }
            } catch (DrishtiException e) {
                out.add("example " + i + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static void collectExposes(Object node, Set<String> driven) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if ("exposes".equals(e.getKey()) && e.getValue() instanceof Map<?, ?> x) {
                    x.keySet().forEach(k -> driven.add(String.valueOf(k)));
                } else if ("emits".equals(e.getKey()) && e.getValue() != null) {
                    driven.add(String.valueOf(e.getValue()));
                } else {
                    collectExposes(e.getValue(), driven);
                }
            }
        } else if (node instanceof List<?> l) {
            l.forEach(x -> collectExposes(x, driven));
        }
    }

    private static void pinType(String pin, Map<String, Object> decl, List<String> out) {
        String t = str(decl.get("type"));
        if (t == null || !TYPES.contains(t)) {
            out.add("pin '" + pin + "' has no known type (" + String.join(", ", TYPES) + ")");
        }
    }

    /** The chip uses of a board: instance to {@code name@version}; {@code DRS-2070} for an unversioned or malformed use. */
    public static Map<String, String> uses(Map<String, Object> board) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map(board.get("chips")).entrySet()) {
            String use = str(map(e.getValue()).get("use"));
            if (use == null || !USE.matcher(use).matches()) {
                throw new DrishtiException(ErrorCode.UI_DOCUMENT_INVALID, "chip '" + e.getKey() + "' needs 'use: <chip>@<version>' (got '" + use + "')");
            }
            out.put(e.getKey(), use);
        }
        return out;
    }

    /** The board-local chip names of a board (a use of one of them is not a Chip Store use). */
    static Set<String> localChips(Map<String, Object> board) {
        Set<String> out = new LinkedHashSet<>();
        for (Object o : list(board.get("local-chips"))) {
            String n = str(map(o).get("chip"));
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }

    /** A source type that a pin of type {@code to} accepts unchanged (section 4.2, the rows with no transform). */
    static boolean accepts(String to, String from) {
        if (to == null || from == null || to.equals(from)) {
            return to != null && to.equals(from);
        }
        return switch (to) {
            case "selection" -> from.equals("id");
            case "datetime", "period" -> from.equals("date");
            case "number" -> from.equals("measure");
            case "measure" -> from.equals("number");
            case "text" -> from.equals("dimension");
            case "dimension" -> List.of("id", "text", "number", "bool", "enum", "date", "datetime", "measure").contains(from);
            default -> false;
        };
    }

    /**
     * Verifies that {@code next} may claim {@code compatible-with} {@code prev} (section 9.3 step 2): every in pin of the earlier version
     * exists with a type that accepts what it accepted; no required pin was added without a default; every out pin still exists with the
     * same type. Empty when it holds.
     */
    public static List<String> incompatibilities(Map<String, Object> prev, Map<String, Object> next) {
        List<String> out = new ArrayList<>();
        Map<String, Object> pin = inPins(prev);
        Map<String, Object> nin = inPins(next);
        for (Map.Entry<String, Object> e : pin.entrySet()) {
            Map<String, Object> was = map(e.getValue());
            Map<String, Object> now = map(nin.get(e.getKey()));
            if (now.isEmpty()) {
                out.add("in pin '" + e.getKey() + "' was removed");
            } else if (!accepts(str(now.get("type")), str(was.get("type")))) {
                out.add("in pin '" + e.getKey() + "' no longer accepts " + was.get("type"));
            } else if ("rowset".equals(was.get("type"))) {
                Map<String, Object> ws = map(was.get("shape"));
                for (Map.Entry<String, Object> c : map(now.get("shape")).entrySet()) {
                    if (Boolean.TRUE.equals(map(c.getValue()).get("required")) && !Boolean.TRUE.equals(map(ws.get(c.getKey())).get("required"))) {
                        out.add("column '" + c.getKey() + "' of in pin '" + e.getKey() + "' became required");
                    }
                }
            }
        }
        for (Map.Entry<String, Object> e : nin.entrySet()) {
            Map<String, Object> d = map(e.getValue());
            if (!pin.containsKey(e.getKey()) && Boolean.TRUE.equals(d.get("required")) && !d.containsKey("default")) {
                out.add("required in pin '" + e.getKey() + "' was added without a default");
            }
        }
        Map<String, Object> nout = outPins(next);
        for (Map.Entry<String, Object> e : outPins(prev).entrySet()) {
            Map<String, Object> now = map(nout.get(e.getKey()));
            if (now.isEmpty()) {
                out.add("out pin '" + e.getKey() + "' was removed");
            } else if (!String.valueOf(map(e.getValue()).get("type")).equals(String.valueOf(now.get("type")))) {
                out.add("out pin '" + e.getKey() + "' changed type");
            }
        }
        return out;
    }

    /** The versions a chip claims to be compatible with. */
    static List<Integer> compatibleWith(Map<String, Object> chip) {
        List<Integer> out = new ArrayList<>();
        for (Object o : list(chip.get("compatible-with"))) {
            try {
                out.add(Integer.parseInt(String.valueOf(o)));
            } catch (NumberFormatException e) {
                throw new DrishtiException(ErrorCode.UI_DOCUMENT_INVALID, "'compatible-with' lists versions (numbers), not '" + o + "'");
            }
        }
        return out;
    }
}
