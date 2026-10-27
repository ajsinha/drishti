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
package com.ash.drishti.server.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * A Sutra's test cases (2.9.4, docs/guides/DATA_FORMATS.md): {@code tests/<sutra>/cases.jsonl}, one case a line,
 * {@code {"case": "<name>", "document": {...}}}, beside the folder's {@code expect.yaml}. The older layout, one {@code <name>.json} file per
 * case, is still read during 2.9.4, with a deprecation warning.
 */
public final class SutraCases {

    /** The file of a Sutra's cases in its test folder. */
    public static final String FILE = "cases.jsonl";

    private static final ObjectMapper JSON = new ObjectMapper();

    private SutraCases() {}

    /** One case: its name and its document, or why the line could not be read (then document is null). */
    public record Case(String name, JsonNode document, String error) {}

    /** The cases of a {@code cases.jsonl} text, in order; an unreadable line is a case with its error, named by its line number. */
    public static List<Case> parse(String text, String source) {
        List<Case> out = new ArrayList<>();
        int n = 0;
        for (String line : text.split("\\R")) {
            n++;
            if (line.isBlank()) {
                continue;
            }
            String where = source + ":" + n;
            JsonNode node;
            try {
                node = JSON.readTree(line);
            } catch (JsonProcessingException e) {
                out.add(new Case(where, null, where + " is not valid JSON: " + e.getOriginalMessage()));
                continue;
            }
            if (node == null || !node.isObject() || !node.path("case").isTextual() || node.path("case").asText().isBlank() || !node.path("document").isObject()) {
                out.add(new Case(where, null, where + ": a case is one line {\"case\": \"<name>\", \"document\": {...}}"));
                continue;
            }
            out.add(new Case(node.get("case").asText(), node.get("document"), null));
        }
        return out;
    }

    /** One case as its line, newline included. */
    public static String line(String name, JsonNode document) {
        ObjectNode o = JSON.createObjectNode();
        o.put("case", name);
        o.set("document", document);
        try {
            return JSON.writeValueAsString(o) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("a case could not be written: " + e.getOriginalMessage(), e);
        }
    }
}
