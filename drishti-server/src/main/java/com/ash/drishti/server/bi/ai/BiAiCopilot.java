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
package com.ash.drishti.server.bi.ai;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.report.ReportYaml;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The authoring helpers on the AI layer (docs/guides/BI_GUIDE.md sections 9 to 11), each a feature of the AI policy of its own ({@code bi-suggest},
 * {@code bi-explain-checks}, {@code bi-helper}). Like the report draft they send the model the dataset's MODEL (names, types, descriptions) and nothing else, and they
 * propose: a visual is YAML the report checker has passed, a dataset item is checked against the model; nothing is applied until the author accepts it item by item.
 */
public final class BiAiCopilot {

    public static final String SUGGEST = "bi-suggest";
    public static final String CHECKS = "bi-explain-checks";
    public static final String HELPER = "bi-helper";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("(?m)^\\s*(?:-\\s+)?id:\\s*([A-Za-z0-9_-]+)");
    private static final Pattern REF = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\b");

    private final BiAiService ai;

    public BiAiCopilot(BiAiService ai) {
        this.ai = ai;
    }

    // ---- designer copilot: visuals, wells and filters for a goal ----------------------------------------------------------------------------------------------------

    /**
     * Visuals (and slicers, the filters) for {@code goal} on the dataset, each as the YAML to add to a page. {@code currentYaml} (may be blank) is the report being
     * designed: its ids are avoided and its titles tell the model what is already there.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> suggest(Principal p, String datasetRef, String goal, String currentYaml) {
        ai.on();
        ai.bi().requirePower(p, BiAccess.AUTHOR);
        String text = ai.text(goal, "say what you want to see");
        Map<String, Object> model = ai.grounding(p, datasetRef);                            // a model of the model layer, or a version 1 dataset
        String have = currentYaml == null ? "" : currentYaml.length() > 20_000 ? currentYaml.substring(0, 20_000) : currentYaml;
        Set<String> taken = new LinkedHashSet<>();
        Matcher m = ID.matcher(have);
        while (m.find()) {
            taken.add(m.group(1));
        }
        int max = ai.props().suggestions();
        Map<String, Object> checked = ai.checkedYaml(p, SUGGEST, "bi-ai-suggest", BiAiService.pinOf(model, datasetRef), BiAiPrompts.draftSystem() + SUGGEST_RULES,
                problems -> "TASK: suggest-visuals\n" + BiAiPrompts.model(model) + "GOAL: " + text + "\nAT MOST " + max + " VISUALS\n"
                        + (have.isBlank() ? "" : "ALREADY IN THE REPORT (do not repeat):\n" + titles(have) + "\n")
                        + (problems.isEmpty() ? "" : "YOUR PREVIOUS ANSWER WAS REFUSED: " + problems + "\nFix those and reply with the whole YAML again.\n"));
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> report = (Map<String, Object>) checked.get("report");
        for (Object pg : (List<Object>) report.getOrDefault("pages", List.of())) {
            for (Object vo : (List<Object>) ((Map<String, Object>) pg).getOrDefault("visuals", List.of())) {
                if (items.size() >= max) {
                    break;
                }
                Map<String, Object> v = new LinkedHashMap<>((Map<String, Object>) vo);
                String id = String.valueOf(v.get("id"));
                String fresh = id;
                for (int i = 2; taken.contains(fresh); i++) {
                    fresh = id + "-" + i;
                }
                taken.add(fresh);
                v.put("id", fresh);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", fresh);
                item.put("kind", "slicer".equals(v.get("type")) ? "filter" : "visual");
                item.put("type", v.get("type"));
                item.put("title", v.get("title") == null ? fresh : v.get("title"));
                item.put("why", v.get("alt") == null ? "" : v.get("alt"));
                item.put("visual", v);
                item.put("yaml", ReportYaml.visual(v));
                items.add(item);
            }
        }
        ai.audit().record(p.user(), "bi-ai-suggest", datasetRef, "ok " + items.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", datasetRef);
        out.put("model", model.get(BiAiService.LAYER_REF));
        out.put("suggestions", items);
        out.put("applied", false);
        return out;
    }

    private static final String SUGGEST_RULES = """
            For this task answer with the same report YAML shape holding ONE page of the visuals you suggest. Prefer a card for a single total, a bar or column for a measure by
            one field, a line for a measure over a date, a table for detail, and a slicer (wells: { field: [table.column] }) for each field that is worth filtering by.
            Give every visual a title and an `alt` sentence that says why it is useful.
            """;

    private static String titles(String yaml) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^\\s*-?\\s*title:\\s*(.+)$").matcher(yaml);
        while (m.find() && out.size() < 30) {
            out.add("- " + m.group(1).strip());
        }
        return String.join("\n", out);
    }

    // ---- Check failures in plain words -------------------------------------------------------------------------------------------------------------------------------

    /**
     * The report checker's problems for {@code yaml}, each with a plain explanation and a way to fix it, written by the model from the problem codes and messages and the
     * dataset's model. No value goes in. Without problems nothing is asked of the model.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> explainChecks(Principal p, String yaml) {
        ai.on();
        ai.bi().requirePower(p, BiAccess.AUTHOR);
        if (yaml == null || yaml.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "send the report's YAML (yaml)");
        }
        Map<String, Object> checked = ai.reports().validate(p, yaml, true);
        List<Map<String, Object>> problems = new ArrayList<>();
        for (Object o : (List<Object>) checked.get("problems")) {
            problems.add(new LinkedHashMap<>((Map<String, Object>) o));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", checked.get("valid"));
        out.put("problems", problems);
        if (problems.isEmpty()) {
            return out;
        }
        List<Map<String, Object>> shown = problems.size() > 12 ? problems.subList(0, 12) : problems;
        StringBuilder facts = new StringBuilder("TASK: explain-checks\n");
        Matcher ds = Pattern.compile("(?m)^(?:model|dataset):\\s*(\\S+)").matcher(yaml);         // a report on a model names it with model:
        if (ds.find()) {
            try {
                facts.append(BiAiPrompts.model(ai.grounding(p, ds.group(1).replaceAll("^['\"]|['\"]$", ""))));
            } catch (DrishtiException e) {
                // the dataset cannot be read by this author: the problems alone are explained
            }
        }
        for (int i = 0; i < shown.size(); i++) {
            Map<String, Object> pr = shown.get(i);
            facts.append(i + 1).append(". [").append(pr.get("level")).append("] ").append(pr.get("code")).append(' ').append(pr.get("path")).append(": ").append(pr.get("message")).append('\n');
        }
        String reply = ai.call(p, CHECKS, """
                You explain the problems a report checker found to a business author who is not a programmer. Reply with one JSON object only:
                {"items": [{"n": 1, "explain": "what it means in one or two plain sentences", "fix": "what to change, using names from the dataset where you can"}]}
                One item per numbered problem, in order. Do not invent fields or measures that are not in the dataset. No code fence.""", facts.toString(), true);
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            String s = BiAiService.unfence(reply);
            JsonNode n = JSON.readTree(s.substring(Math.max(0, s.indexOf('{')), s.lastIndexOf('}') + 1));
            for (JsonNode it : n.path("items")) {
                int k = it.path("n").asInt(0);
                if (k >= 1 && k <= shown.size()) {
                    shown.get(k - 1).put("explain", it.path("explain").asText(""));
                    shown.get(k - 1).put("fix", it.path("fix").asText(""));
                    items.add(Map.of("n", k));
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            throw new DrishtiException(ErrorCode.AI_ANSWER, "the model's explanation was not usable; the problems are listed without it");
        }
        out.put("explained", items.size());
        ai.audit().record(p.user(), "bi-ai-explain-checks", "-", "ok " + items.size());
        return out;
    }

    // ---- dataset helper: relationships, measures, column descriptions --------------------------------------------------------------------------------

    /**
     * Proposals for the dataset from its model alone: relationships between columns of the same type, measures with descriptions, and descriptions for the columns that
     * have none. Each item is checked against the model before it is shown (a name that is not there, a relationship that exists, a measure that reads an unknown
     * column is dropped) and carries the YAML to add. The model sees names, types and descriptions, never a row or a value.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> helper(Principal p, String datasetRef) {
        ai.on();
        ai.bi().requirePower(p, BiAccess.AUTHOR);
        Map<String, Object> model = ai.reports().layerRef(p, datasetRef) != null ? ai.grounding(p, datasetRef) : ai.datasets().model(p, datasetRef);
        boolean layer = model.get(BiAiService.LAYER_REF) != null;                          // proposals for a model: its relationships, measures and fields: blocks
        Map<String, String> types = new LinkedHashMap<>();
        Set<String> described = new LinkedHashSet<>();
        for (Object t : (List<Object>) model.getOrDefault("tables", List.of())) {
            Map<String, Object> tm = (Map<String, Object>) t;
            for (Object c : (List<Object>) tm.getOrDefault("columns", List.of())) {
                Map<String, Object> cm = (Map<String, Object>) c;
                if (Boolean.TRUE.equals(cm.get("masked"))) {
                    continue;                                                                  // a masked column is not shown to the model or proposed for
                }
                String ref = tm.get("name") + "." + cm.get("name");
                types.put(ref, String.valueOf(cm.get("type")));
                if (cm.get("description") != null && !String.valueOf(cm.get("description")).isBlank()) {
                    described.add(ref);
                }
            }
        }
        Set<String> haveRel = new LinkedHashSet<>();
        for (Object r : (List<Object>) model.getOrDefault("relationships", List.of())) {
            Map<String, Object> rm = (Map<String, Object>) r;
            haveRel.add(rm.get("from") + ">" + rm.get("to"));
            haveRel.add(rm.get("to") + ">" + rm.get("from"));
        }
        Set<String> haveMeasure = new LinkedHashSet<>();
        for (Object x : (List<Object>) model.getOrDefault("measures", List.of())) {
            haveMeasure.add(String.valueOf(((Map<String, Object>) x).get("name")).toLowerCase(java.util.Locale.ROOT));
        }
        StringBuilder facts = new StringBuilder("TASK: dataset-helper\n").append(BiAiPrompts.model(model));
        facts.append("RELATIONSHIPS ALREADY THERE:\n");
        haveRel.stream().filter(x -> x.compareTo(reverse(x)) < 0).forEach(x -> facts.append("  ").append(x.replace(">", " -> ")).append('\n'));
        facts.append("COLUMNS WITHOUT A DESCRIPTION:\n");
        types.keySet().stream().filter(c -> !described.contains(c)).limit(60).forEach(c -> facts.append("  ").append(c).append('\n'));
        int max = ai.props().helperItems();
        String reply = ai.call(p, HELPER, """
                You help a business analyst document a dataset. From the names, types and descriptions given (you see no data), reply with one JSON object only:
                {"relationships": [{"from": "table.column", "to": "table.column", "cardinality": "many-to-one", "why": "..."}],
                 "measures": [{"name": "Measure name", "expr": "sum(table.column)", "description": "one sentence"}],
                 "columns": [{"field": "table.column", "description": "one sentence"}]}
                Relationships join columns of the SAME type, from the many side to the one side (usually an id). A measure's expr is one aggregate (sum, avg, min, max, count,
                distinct) over a listed numeric column, or count(table). Use ONLY listed names. Do not repeat what is already there. No code fence.""", facts.toString(), true);
        JsonNode n;
        try {
            String s = BiAiService.unfence(reply);
            n = JSON.readTree(s.substring(Math.max(0, s.indexOf('{')), s.lastIndexOf('}') + 1));
        } catch (java.io.IOException | RuntimeException e) {
            throw new DrishtiException(ErrorCode.AI_ANSWER, "the model's proposals were not usable");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        int count = 0;
        for (JsonNode r : n.path("relationships")) {
            String from = r.path("from").asText("");
            String to = r.path("to").asText("");
            if (count < max && types.containsKey(from) && types.containsKey(to) && types.get(from).equals(types.get(to)) && !from.equals(to)
                    && !from.substring(0, from.indexOf('.')).equals(to.substring(0, to.indexOf('.'))) && !haveRel.contains(from + ">" + to)) {
                String card = Set.of("many-to-one", "one-to-many", "one-to-one", "many-to-many").contains(r.path("cardinality").asText()) ? r.path("cardinality").asText() : "many-to-one";
                haveRel.add(from + ">" + to);
                items.add(item("relationship", from + " -> " + to, r.path("why").asText(""), "- { from: " + from + ", to: " + to + ", cardinality: " + card + " }",
                        Map.of("from", from, "to", to, "cardinality", card)));
                count++;
            }
        }
        count = 0;
        for (JsonNode x : n.path("measures")) {
            String name = x.path("name").asText("").strip();
            String expr = x.path("expr").asText("").strip();
            if (count < max && name.matches("[A-Za-z][A-Za-z0-9 ._()%-]{0,60}") && !haveMeasure.contains(name.toLowerCase(java.util.Locale.ROOT)) && expr.matches("(?i)(sum|avg|min|max|count|distinct)\\(.+\\)")
                    && readsKnown(expr, types, model)) {
                haveMeasure.add(name.toLowerCase(java.util.Locale.ROOT));
                String desc = x.path("description").asText("").strip();
                items.add(item("measure", name, desc, name + ": { expr: " + quote(expr) + (desc.isEmpty() ? "" : ", description: " + quote(desc)) + " }",
                        Map.of("name", name, "expr", expr, "description", desc)));
                count++;
            }
        }
        count = 0;
        for (JsonNode c : n.path("columns")) {
            String field = c.path("field").asText("");
            String desc = c.path("description").asText("").strip();
            if (count < max && types.containsKey(field) && !described.contains(field) && !desc.isEmpty() && desc.length() <= 200) {
                described.add(field);
                items.add(item(layer ? "field" : "column", field, desc, layer ? field + ": { description: " + quote(desc) + " }"      // a model's fields: block
                        : field.substring(field.indexOf('.') + 1) + ": { type: " + types.get(field) + ", description: " + quote(desc) + " }", Map.of("field", field, "description", desc)));
                count++;
            }
        }
        ai.audit().record(p.user(), "bi-ai-helper", datasetRef, "ok " + items.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", datasetRef);
        out.put("model", model.get(BiAiService.LAYER_REF));
        out.put("items", items);
        out.put("applied", false);
        return out;
    }

    private static String reverse(String pair) {
        int i = pair.indexOf('>');
        return pair.substring(i + 1) + ">" + pair.substring(0, i);
    }

    /** Every {@code table.column} an expression reads is a column the model has (and a bare {@code count(table)} names a table). */
    @SuppressWarnings("unchecked")
    private static boolean readsKnown(String expr, Map<String, String> types, Map<String, Object> model) {
        Matcher m = REF.matcher(expr);
        boolean any = false;
        while (m.find()) {
            any = true;
            if (!types.containsKey(m.group(1) + "." + m.group(2))) {
                return false;
            }
        }
        if (any) {
            return true;
        }
        Matcher t = Pattern.compile("(?i)count\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)").matcher(expr);
        if (!t.matches()) {
            return false;
        }
        for (Object o : (List<Object>) model.getOrDefault("tables", List.of())) {
            if (t.group(1).equals(((Map<String, Object>) o).get("name"))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> item(String kind, String label, String why, String yaml, Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", kind + ":" + label);
        m.put("kind", kind);
        m.put("label", label);
        m.put("why", why);
        m.put("yaml", yaml);
        m.put("data", data);
        return m;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
