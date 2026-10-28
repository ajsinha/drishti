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
package com.ash.drishti.server.var;

import com.ash.drishti.identity.content.ContentDocs;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An official date's membership record as JSON Lines (2.9.4, docs/guides/DATA_FORMATS.md): a header line {@code {"row": "header",
 * businessDate, resultVersion, positionRules, ...}}, then one {@code {"row": "position", book, position, trades}} a position and one
 * {@code {"row": "node", node, books}} a hierarchy node; with a hierarchy built from columns (2.9.5) also one {@code {"row": "cell", cell,
 * book, values, positions}} a cell (a node of such a hierarchy lists its cells as {@code books}). {@link #read} gives back the document
 * {@code VarOfficial} built.
 */
final class MembershipLines {

    private MembershipLines() {}

    @SuppressWarnings("unchecked")
    static byte[] write(Map<String, Object> membership) {
        StringBuilder b = new StringBuilder();
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("row", "header");
        membership.forEach((k, v) -> {
            if (!"books".equals(k) && !"nodes".equals(k) && !"cells".equals(k)) {
                head.put(k, v);
            }
        });
        line(b, head);
        Map<String, Object> books = membership.get("books") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        books.forEach((book, positions) -> {
            if (positions instanceof Map<?, ?> ps) {
                ps.forEach((position, trades) -> {
                    Map<String, Object> l = new LinkedHashMap<>();
                    l.put("row", "position");
                    l.put("book", book);
                    l.put("position", position);
                    l.put("trades", trades);
                    line(b, l);
                });
            }
        });
        Map<String, Object> cells = membership.get("cells") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        cells.forEach((cell, c) -> {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("row", "cell");
            l.put("cell", cell);
            if (c instanceof Map<?, ?> cm) {
                l.put("book", cm.get("book"));
                l.put("values", cm.get("values"));
                l.put("positions", cm.get("positions"));
            }
            line(b, l);
        });
        Map<String, Object> nodes = membership.get("nodes") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        nodes.forEach((node, bs) -> {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("row", "node");
            l.put("node", node);
            l.put("books", bs);
            line(b, l);
        });
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Map<String, Object>> books = new LinkedHashMap<>();
        Map<String, Object> nodes = new LinkedHashMap<>();
        Map<String, Object> cells = new LinkedHashMap<>();
        for (String s : text.split("\n")) {
            if (s.isBlank()) {
                continue;
            }
            Map<String, Object> m = ContentDocs.decode(s.getBytes(StandardCharsets.UTF_8), Map.class);
            switch (String.valueOf(m.remove("row"))) {
                case "header" -> out.putAll(m);
                case "position" -> books.computeIfAbsent(String.valueOf(m.get("book")), x -> new LinkedHashMap<>())
                        .put(String.valueOf(m.get("position")), m.get("trades") instanceof List<?> l ? l : List.of());
                case "node" -> nodes.put(String.valueOf(m.get("node")), m.get("books"));
                case "cell" -> cells.put(String.valueOf(m.remove("cell")), m);
                default -> { }
            }
        }
        out.put("books", books);
        out.put("nodes", nodes);
        if (!cells.isEmpty()) {
            out.put("cells", cells);
        }
        return out;
    }

    private static void line(StringBuilder b, Map<String, Object> row) {
        try {
            b.append(ContentDocs.JSON.writeValueAsString(row)).append('\n');
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the membership record could not be written: " + e.getOriginalMessage(), e);
        }
    }
}
