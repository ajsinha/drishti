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
package com.ash.drishti.lakeload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Child kinds: an array of records in a parent's documents ({@code LEGS} of {@code trade}) loaded as a kind of its own
 * ({@code trade-leg}), one row per element (docs/guides/DATA_LAKE_GUIDE.md section 5.7). The rules are the pack's
 * {@code ingest:} block, {@code child-of} entries, read here as tools/packgen/childkinds.py reads them: the Python loaders and this one split
 * by the same declaration and the same steps ({@link #explode}), and one fixture of documents and the rows they must give
 * ({@code src/test/resources/childkinds}) is checked by both languages so they cannot drift.
 *
 * <p>A child row is the element's own fields with two more in front: its id ({@code key}: the parent's id, {@code :}, the element's natural
 * key when it has one, else its index in the array) and the link to its parent ({@code link}: the parent's id). The parent keeps its array.
 *
 * @param parent    the parent kind
 * @param field     the parent's array
 * @param kind      the child kind
 * @param key       the child's id field
 * @param link      the field carrying the parent's id
 * @param parentKey the parent's id field (a dotted path)
 * @param natural   the element's field that is its unique id within a parent, or null (the index is used)
 */
record ChildKinds(String parent, String field, String kind, String key, String link, String parentKey, String natural) {

    static final String SEP = ":";

    /** The child kinds of {@code parent} that the pack's {@code ingest:} block declares (none: empty). */
    static List<ChildKinds> of(Path pack, String parent) throws IOException {
        Path file = Files.isDirectory(pack) ? pack.resolve("pack.yaml") : pack;
        JsonNode ingest = new YAMLMapper().readTree(Files.readString(file)).path("ingest");
        return of(ingest).getOrDefault(parent, List.of());
    }

    /** parent kind -> its child kinds, from an {@code ingest:} block. */
    static Map<String, List<ChildKinds>> of(JsonNode ingest) {
        Map<String, List<ChildKinds>> out = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = ingest.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode of = e.getValue().path("child-of");
            if (!of.isObject()) {
                continue;
            }
            String parent = of.path("kind").asText();
            JsonNode pk = ingest.path(parent).path("key");
            String parentKey = pk.isTextual() && !pk.asText().isEmpty() ? pk.asText() : "id";
            String link = of.hasNonNull("link") && !of.get("link").asText().isEmpty() ? of.get("link").asText() : linkOf(parent, parentKey);
            String natural = of.hasNonNull("natural") && !of.get("natural").asText().isEmpty() ? of.get("natural").asText() : null;
            out.computeIfAbsent(parent, k -> new ArrayList<>()).add(new ChildKinds(parent, of.path("field").asText(), e.getKey(),
                    e.getValue().path("key").asText(), link, parentKey, natural));
        }
        return out;
    }

    /** The link field's name: the parent's id field (TRADE_ID); a bare {@code id} becomes TRADE_ID after the parent kind. */
    static String linkOf(String parent, String parentKey) {
        String last = parentKey.substring(parentKey.lastIndexOf('.') + 1);
        return !last.equalsIgnoreCase("id") ? last : parent.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "") + "_ID";
    }

    /** The child rows of one parent document (none when it has no id or no such array): its id and link first, then the element's fields. */
    List<ObjectNode> explode(JsonNode doc) {
        JsonNode pid = doc;
        for (String part : parentKey.split("\\.")) {
            pid = pid.isObject() ? pid.get(part) : null;
            if (pid == null) {
                return List.of();
            }
        }
        JsonNode arr = doc.get(field);
        if (pid.isNull() || pid.isContainerNode() || arr == null || !arr.isArray()) {
            return List.of();
        }
        List<ObjectNode> out = new ArrayList<>();
        int i = -1;
        for (JsonNode el : arr) {
            i++;
            if (!el.isObject()) {
                continue;
            }
            JsonNode nat = natural == null ? null : el.get(natural);
            String tail = nat != null && (nat.isTextual() && !nat.asText().isEmpty() || nat.isIntegralNumber()) ? nat.asText() : Integer.toString(i);
            ObjectNode row = JsonNodeFactory.instance.objectNode();
            row.put(key, text(pid) + SEP + tail);
            row.set(link, pid);
            for (Iterator<Map.Entry<String, JsonNode>> it = el.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> f = it.next();
                if (!f.getKey().equals(key) && !f.getKey().equals(link)) {
                    row.set(f.getKey(), f.getValue());
                }
            }
            out.add(row);
        }
        return out;
    }

    /** The parent's id as text, as Python's {@code str()} writes it (a boolean is {@code True}). */
    private static String text(JsonNode v) {
        return v.isBoolean() ? (v.asBoolean() ? "True" : "False") : v.asText();
    }
}
