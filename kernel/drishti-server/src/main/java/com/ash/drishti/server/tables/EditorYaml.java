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
package com.ash.drishti.server.tables;

import com.ash.drishti.tables.def.ModelDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.yaml.ModelYaml;
import com.ash.drishti.tables.yaml.TableYaml;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The editors' two sides of one definition (docs/architecture/SHARED_SCREENS.md section 1): the YAML text and the document the visual side edits (the same tree as plain
 * maps and lists). A visual edit sends the document and gets the canonical YAML back (the layer's own writer, {@link TableYaml#write} and
 * {@link ModelYaml#write}, its comment header kept); a YAML edit sends the text and gets the document back. Each key's line is known, so the
 * line being edited is highlighted and a problem is shown at its line.
 */
public final class EditorYaml {

    static final String TABLE = "table";
    static final String MODEL = "model";

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    private EditorYaml() {}

    /** A text read: the document (null when the text is not YAML), and where it broke (line, 1-based; 0 when it did not). */
    public record Read(Map<String, Object> doc, int errorLine, String error) {
        public boolean ok() {
            return doc != null;
        }
    }

    /** The document of {@code text}: a YAML mapping as maps and lists; a syntax error with its line. */
    @SuppressWarnings("unchecked")
    public static Read read(String text) {
        if (text == null || text.isBlank()) {
            return new Read(null, 1, "the definition is empty");
        }
        try {
            Object o = YAML.readValue(text, Object.class);
            if (!(o instanceof Map<?, ?> m)) {
                return new Read(null, 1, "a definition is a YAML mapping (key: value lines)");
            }
            return new Read(new LinkedHashMap<>((Map<String, Object>) m), 0, null);
        } catch (JsonProcessingException e) {
            JsonLocation at = e.getLocation();
            return new Read(null, at == null ? 1 : Math.max(1, at.getLineNr()), e.getOriginalMessage());
        }
    }

    /** The comment lines and blank lines before the first key: a file's licence and notes, kept when the visual side rewrites the text. */
    public static List<String> header(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n", -1)) {
            String s = line.strip();
            if (s.startsWith("#") || s.isEmpty()) {
                out.add(line.stripTrailing());
            } else {
                break;
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /**
     * The YAML of {@code doc}: the layer's canonical form when the document reads as a definition of {@code kind}, else the document as plain
     * YAML (so a half-made definition still shows), with {@code header} first.
     */
    public static String write(String kind, Map<String, Object> doc, List<String> header) {
        String json;
        try {
            json = JSON.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("the document cannot be written: " + e.getOriginalMessage(), e);
        }
        if (TABLE.equals(kind)) {
            TableYaml.Result r = TableYaml.read(json);
            TableDef t = r.table();
            if (t != null) {
                return TableYaml.write(t, header);
            }
        } else if (MODEL.equals(kind)) {
            ModelYaml.Result r = ModelYaml.read(json);
            ModelDef m = r.model();
            if (m != null) {
                return ModelYaml.write(m, header);
            }
        }
        try {
            StringBuilder b = new StringBuilder();
            header.forEach(h -> b.append(h).append('\n'));
            String plain = YAML.writeValueAsString(doc);
            b.append(plain.startsWith("---\n") ? plain.substring(4) : plain);
            return b.toString();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("the document cannot be written: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Each key's line in {@code text} (1-based), by its path as the layer's problems name it: {@code table}, {@code from.kind},
     * {@code columns.MTM.expr}, {@code relationships[0].from}, {@code tests[1]}. Empty when the text is not YAML.
     */
    public static Map<String, Integer> lines(String text) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        try (JsonParser p = YAML.getFactory().createParser(text)) {
            Deque<Frame> stack = new ArrayDeque<>();
            String pending = null;
            JsonToken t;
            while ((t = p.nextToken()) != null) {
                int line = p.currentTokenLocation().getLineNr();
                Frame top = stack.peek();
                switch (t) {
                    case FIELD_NAME -> {
                        pending = join(top == null ? "" : top.path, p.currentName());
                        out.putIfAbsent(pending, line);
                    }
                    case START_OBJECT, START_ARRAY -> {
                        String path = element(top, pending);
                        if (top != null && top.array && !path.isEmpty()) {
                            out.putIfAbsent(path, line);
                        }
                        stack.push(new Frame(path, t == JsonToken.START_ARRAY));
                        pending = null;
                    }
                    case END_OBJECT, END_ARRAY -> {
                        stack.pop();
                        Frame up = stack.peek();
                        if (up != null && up.array) {
                            up.index++;
                        }
                        pending = null;
                    }
                    default -> {
                        if (top != null && top.array) {
                            out.putIfAbsent(top.path + "[" + top.index + "]", line);
                            top.index++;
                        }
                        pending = null;
                    }
                }
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    /** The line of {@code path}, or of its nearest parent the text has; 0 when none. */
    public static int lineOf(Map<String, Integer> lines, String path) {
        String at = path == null ? "" : path;
        while (!at.isEmpty()) {
            Integer l = lines.get(at);
            if (l != null) {
                return l;
            }
            int cut = Math.max(at.lastIndexOf('.'), at.lastIndexOf('['));
            at = cut <= 0 ? "" : at.substring(0, cut);
        }
        return 0;
    }

    private static String element(Frame top, String pending) {
        if (pending != null) {
            return pending;
        }
        if (top != null && top.array) {
            return top.path + "[" + top.index + "]";
        }
        return top == null ? "" : top.path;
    }

    private static String join(String parent, String key) {
        return parent == null || parent.isEmpty() ? key : parent + "." + key;
    }

    private static final class Frame {
        final String path;
        final boolean array;
        int index;

        Frame(String path, boolean array) {
            this.path = path;
            this.array = array;
        }
    }
}
