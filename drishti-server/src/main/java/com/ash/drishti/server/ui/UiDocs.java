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
import java.util.regex.Pattern;

/**
 * The chip and board documents as the Chip Store handles them (UI_BOARD.md sections 2 and 3): YAML or JSON to a map (the store
 * rewrites a board's {@code owner}, {@code workspace} and {@code version}), the header fields, the uses of a board, and the
 * {@code compatible-with} verification of section 9.3. The board check and the chip publish checks are the grammar's
 * ({@code BoardCheck}, {@code ChipPublishCheck}).
 */
public final class UiDocs {

    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    static final Pattern NAME = com.ash.drishti.rachana.ui.ChipRef.NAME;
    static final Pattern USE = Pattern.compile("([a-z][a-z0-9-]{0,63})@([1-9][0-9]{0,8})");

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
