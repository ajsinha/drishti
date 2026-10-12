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

import com.ash.drishti.ai.CallContext;
import com.ash.drishti.ai.llm.Canonical;
import com.ash.drishti.ai.llm.ChatCompletionRequest;
import com.ash.drishti.ai.llm.LLMFactory;
import com.ash.drishti.ai.LlmException;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.api.AiController;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.DatasetService;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.report.BiReportService;
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
 * The three BI hooks on the AI layer (docs/guides/BI_AI.md): report from words, ask the data, explain this number. Every model call goes through the
 * {@link LlmGateway} under its own feature name ({@code bi-draft}, {@code bi-ask}, {@code bi-explain}), so the AI policy (masks, audit, limits, roles) applies;
 * each hook adds an access-log row of its own. The model never runs anything: a draft is validated by the report parser and checker, a query is run by the
 * dataset engine as the reader (row rules, masks), and a number is computed by the engine before the model is asked to word it.
 */
public final class BiAiService {

    public static final String DRAFT = "bi-draft";
    public static final String ASK = "bi-ask";
    public static final String EXPLAIN = "bi-explain";
    /** The feature name of a subscription's narrative summary (the AI policy's per-feature roles and limits apply under it). */
    public static final String NARRATE = "subscription-narrative";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern FENCE = Pattern.compile("(?s)```[a-zA-Z]*\\s*\\n?(.*?)```");
    private static final Pattern DATASET_LINE = Pattern.compile("(?m)^dataset:.*$");

    private final LLMFactory gateway;
    private final com.ash.drishti.server.ai.AiProperties ai;
    private final BiAiProperties props;
    private final BiService bi;
    private final BiReportService reports;
    private final DatasetService datasets;
    private final AuditLog audit;

    public BiAiService(LLMFactory gateway, com.ash.drishti.server.ai.AiProperties ai, BiAiProperties props, BiService bi, BiReportService reports, DatasetService datasets,
            AuditLog audit) {
        this.gateway = gateway;
        this.ai = ai;
        this.props = props;
        this.bi = bi;
        this.reports = reports;
        this.datasets = datasets;
        this.audit = audit;
    }

    /** Whether the hooks are on, and in which mode (the UI shows or hides its buttons from this). */
    public Map<String, Object> status(Principal p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", ai.enabled());
        m.put("values", props.values());
        m.put("draft", ai.enabled() && bi.mayAuthor(p));
        m.put("ask", ai.enabled());
        m.put("explain", ai.enabled());
        m.put("assist", ai.enabled() && bi.mayAuthor(p));          // designer copilot, check explanations, dataset helper
        return m;
    }

    // ---- report from words ----------------------------------------------------------------------------------------

    /** A draft report over {@code datasetRef}, written by the model from the dataset's model alone and accepted only if the report checker passes it. */
    public Map<String, Object> draft(Principal p, String datasetRef, String request) {
        on();
        bi.requirePower(p, BiAccess.AUTHOR);
        String text = text(request, "describe the report you want");
        Map<String, Object> model = reports.datasetModel(p, datasetRef);                    // authors; the fields masked for this author are left out of the prompt
        Map<String, Object> checked = checkedYaml(p, DRAFT, "bi-ai-draft", datasetRef, BiAiPrompts.draftSystem(), problems -> BiAiPrompts.draftUser(model, text, problems));
        audit.record(p.user(), "bi-ai-draft", datasetRef, "ok");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("yaml", checked.get("yaml"));
        out.put("valid", true);
        out.put("problems", checked.get("problems"));
        out.put("dataset", datasetRef);
        out.put("saved", false);
        return out;
    }

    /**
     * Asks the model for report YAML (one more try when the report checker refuses the first answer, with its problems in the prompt) and returns the checker's answer
     * with {@code yaml} added. Nothing the checker refuses is returned: the second refusal is DRS-4020.
     */
    Map<String, Object> checkedYaml(Principal p, String feature, String auditAction, String datasetRef, String system, java.util.function.Function<String, String> userPrompt) {
        String problems = "";
        List<String> last = List.of();
        for (int attempt = 0; attempt < 2; attempt++) {
            String yaml = pinDataset(unfence(call(p, feature, system, userPrompt.apply(problems), false)), datasetRef);
            Map<String, Object> checked = new LinkedHashMap<>(reports.validate(p, yaml, true));
            if (Boolean.TRUE.equals(checked.get("valid"))) {
                checked.put("yaml", yaml);
                return checked;
            }
            last = errors(checked);
            problems = String.join("; ", last);
        }
        audit.record(p.user(), auditAction, datasetRef, "refused");
        throw new DrishtiException(ErrorCode.AI_ANSWER, "the model's draft did not pass the report check, so it is not shown: " + String.join("; ", last.stream().limit(3).toList()));
    }

    // ---- ask the data ---------------------------------------------------------------------------------------------

    /** The model maps {@code question} to a dataset query; the engine runs it as the reader. {@code report} names the report the question is asked on, else {@code dataset}. */
    public Map<String, Object> ask(Principal p, String report, String dataset, String question) {
        on();
        bi.requirePower(p, BiAccess.BI);
        String text = text(question, "ask a question");
        Map<String, Object> model;
        java.util.function.Function<QueryRequest, Map<String, Object>> run;
        String target;
        if (report != null && !report.isBlank()) {
            BiReportService.Base t = reports.target(p, report);
            model = t.model();
            target = String.valueOf(model.get("id"));
            run = q -> datasets.json(datasets.ask(p, t.dsItem(), q, false));
        } else if (dataset != null && !dataset.isBlank()) {
            model = datasets.model(p, dataset);
            target = dataset;
            run = q -> datasets.json(datasets.ask(p, dataset, q, false));
        } else {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "name the report (report) or the dataset (dataset) the question is asked of");
        }
        QueryRequest q = capped(parseQuery(call(p, ASK, BiAiPrompts.askSystem(), BiAiPrompts.askUser(model, text), true)));
        Map<String, Object> answer;
        try {
            answer = run.apply(q);                                                           // the engine, as this reader: row rules, masks, limits
        } catch (DrishtiException e) {
            audit.record(p.user(), "bi-ai-ask", target, "refused " + e.errorCode().code());
            throw new DrishtiException(e.errorCode(), "the query the model chose was refused: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>(answer);
        out.put("question", text);
        out.put("query", describe(q));
        out.put("visual", suggest(q, model, ((List<?>) answer.get("rows")).size()));
        out.put("values", props.values());
        out.put("summary", props.aggregates() ? summarise(p, ASK, "QUESTION: " + text + "\n" + table(answer)) : null);
        audit.record(p.user(), "bi-ai-ask", target, "ok " + answer.get("rowCount") + " rows");
        return out;
    }

    // ---- explain this number --------------------------------------------------------------------------------------

    /** Lineage of the visual's measure, the engine's breakdown of its value by the main dimensions and, in aggregates mode, the model's wording of that breakdown. */
    public Map<String, Object> explain(Principal p, String report, String page, String visual, String measure, JsonNode state) {
        on();
        bi.requirePower(p, BiAccess.BI);
        BiReportService.Base b = reports.base(p, report, page, visual, state);
        String name = measure == null || measure.isBlank() ? (b.query().measures().isEmpty() ? "" : b.query().measures().get(0)) : measure;
        Dataset.Measure m = b.dataset().measures().get(name);
        if (m == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the dataset has no measure '" + name + "'");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("measure", name);
        out.put("lineage", lineage(b.dataset(), b.model(), m));
        QueryRequest base = new QueryRequest(List.of(), List.of(name), b.query().filters(), b.query().from(), b.query().to(), List.of(), 1, b.query().layer());
        Map<String, Object> total = datasets.json(datasets.ask(p, b.dsItem(), base, false));
        Object value = firstCell(total);
        out.put("value", value);
        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (String dim : dimensions(b, m)) {
            if (breakdown.size() >= props.explainDimensions()) {
                break;
            }
            QueryRequest q = new QueryRequest(List.of(dim), List.of(name), b.query().filters(), b.query().from(), b.query().to(),
                    List.of(new QueryRequest.Sort(name, true)), props.explainMembers(), b.query().layer());
            try {
                Map<String, Object> r = datasets.json(datasets.ask(p, b.dsItem(), q, false));
                if (masked(r)) {
                    continue;                                                                // a field masked for this reader says nothing to them, or to the model
                }
                breakdown.add(Map.of("dimension", dim, "members", members(r, value)));
            } catch (DrishtiException e) {
                // a dimension the engine will not group by for this reader is left out
            }
        }
        out.put("breakdown", breakdown);
        out.put("values", props.values());
        out.put("narrative", props.aggregates() && !breakdown.isEmpty() ? summarise(p, EXPLAIN, breakdownText(name, value, breakdown)) : null);
        audit.record(p.user(), "bi-ai-explain", report + "/" + page + "/" + visual, "ok " + name);
        return out;
    }

    // ---- pieces ---------------------------------------------------------------------------------------------------

    /**
     * Words a recipient's own figures (a subscription's narrative summary): {@code facts} was made from what that person may see, masked columns left out. Only when the
     * AI policy lets the model see values ({@code drishti.bi.ai.values: aggregates}); otherwise, or when the AI layer is off or the policy refuses the recipient, this
     * throws and the caller leaves the summary out.
     */
    public String narrate(Principal p, String facts) {
        on();
        if (!props.aggregates()) {
            throw new DrishtiException(ErrorCode.AI_OFF, "the AI policy lets the model see labels only (drishti.bi.ai.values), so it writes no summary of figures");
        }
        String text = call(p, NARRATE, BiAiPrompts.summariseSystem(), "TASK: summarise\n" + facts, false).strip();
        audit.record(p.user(), "ai-" + NARRATE, "-", "ok");
        return text;
    }

    /** Whether the AI layer is on, for the callers that ask before they build a prompt. */
    public boolean enabled() {
        return ai.enabled();
    }

    BiAiProperties props() {
        return props;
    }

    BiReportService reports() {
        return reports;
    }

    DatasetService datasets() {
        return datasets;
    }

    BiService bi() {
        return bi;
    }

    AuditLog audit() {
        return audit;
    }

    void on() {
        if (!ai.enabled()) {
            throw new DrishtiException(ErrorCode.AI_OFF, "the AI layer is switched off (drishti.ai.enabled)");
        }
    }

    String text(String s, String hint) {
        String t = s == null ? "" : s.strip();
        if (t.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, hint);
        }
        if (t.length() > props.maxQuestionChars()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "at most " + props.maxQuestionChars() + " characters (drishti.bi.ai.max-question-chars)");
        }
        return t;
    }

    /** One model call as {@code p} under {@code feature} (the policy's name for it). Package-visible for the designer helpers. */
    String call(Principal p, String feature, String system, String user, boolean jsonMode) {
        ChatCompletionRequest req = new ChatCompletionRequest(props.model().isEmpty() ? null : props.model(),
                List.of(Canonical.ChatMessage.system(system), Canonical.ChatMessage.user(user)), null, null, null,
                jsonMode ? new Canonical.ResponseFormat("json_object", null) : null, 0.0, null, props.maxTokens(), null, null, null, null, null, null, null, null, null, null);
        try {
            return gateway.model(props.model(), "chat", new CallContext(p.user(), Set.copyOf(p.roles()), feature)).chatCompletionsCreate(req).text();
        } catch (LlmException e) {
            audit.record(p.user(), feature.startsWith("bi-") ? "bi-ai-" + feature.substring(3) : "ai-" + feature, "-", e.kind().name().toLowerCase(java.util.Locale.ROOT));
            throw AiController.map(e);
        }
    }

    private String summarise(Principal p, String feature, String facts) {
        return call(p, feature, BiAiPrompts.summariseSystem(), "TASK: summarise\n" + facts, false).strip();
    }

    static String unfence(String reply) {
        String s = reply == null ? "" : reply.strip();
        Matcher m = FENCE.matcher(s);
        return (m.find() ? m.group(1) : s).strip() + "\n";
    }

    static String pinDataset(String yaml, String ref) {
        return DATASET_LINE.matcher(yaml).find() ? DATASET_LINE.matcher(yaml).replaceFirst(Matcher.quoteReplacement("dataset: " + ref)) : yaml;
    }

    @SuppressWarnings("unchecked")
    static List<String> errors(Map<String, Object> checked) {
        List<String> out = new ArrayList<>();
        for (Object o : (List<Object>) checked.get("problems")) {
            Map<String, Object> pm = (Map<String, Object>) o;
            if ("error".equals(String.valueOf(pm.get("level")))) {
                out.add(pm.get("path") + ": " + pm.get("message"));
            }
        }
        return out;
    }

    static QueryRequest parseQuery(String reply) {
        String s = unfence(reply);
        int a = s.indexOf('{');
        int z = s.lastIndexOf('}');
        if (a < 0 || z < a) {
            throw new DrishtiException(ErrorCode.AI_ANSWER, "the model did not answer with a query");
        }
        try {
            JsonNode n = JSON.readTree(s.substring(a, z + 1));
            if (n.path("fields").size() + n.path("measures").size() == 0) {
                throw new DrishtiException(ErrorCode.AI_ANSWER, "the model could not turn the question into a query on this dataset");
            }
            return QueryRequest.of(n);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new DrishtiException(ErrorCode.AI_ANSWER, "the model's query was not valid JSON");
        }
    }

    private QueryRequest capped(QueryRequest q) {
        int limit = q.limit() == null ? props.askRows() : Math.min(q.limit(), props.askRows());
        return new QueryRequest(q.fields(), q.measures(), q.filters(), q.from(), q.to(), q.orderBy(), limit, q.layer());
    }

    static Map<String, Object> describe(QueryRequest q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fields", q.fields());
        m.put("measures", q.measures());
        m.put("filters", q.filters().stream().map(f -> Map.of("field", f.field(), "op", f.op(), "values", f.values())).toList());
        if (q.from() != null) {
            m.put("dates", Map.of("from", q.from().toString(), "to", (q.to() == null ? q.from() : q.to()).toString()));
        }
        m.put("orderBy", q.orderBy().stream().map(s -> Map.of("by", s.by(), "desc", s.desc())).toList());
        m.put("limit", q.limit());
        return m;
    }

    /** The visual that suits the shape of the question, worked out from the query and the model, not by the model. */
    @SuppressWarnings("unchecked")
    static String suggest(QueryRequest q, Map<String, Object> model, int rows) {
        if (q.fields().isEmpty()) {
            return q.measures().size() == 1 ? "card" : "table";
        }
        if (q.fields().size() == 1 && !q.measures().isEmpty()) {
            String f = q.fields().get(0);
            for (Object t : (List<Object>) model.getOrDefault("tables", List.of())) {
                Map<String, Object> tm = (Map<String, Object>) t;
                for (Object c : (List<Object>) tm.getOrDefault("columns", List.of())) {
                    Map<String, Object> cm = (Map<String, Object>) c;
                    String dotted = tm.get("name") + "." + cm.get("name");
                    if (f.equals(dotted) || f.startsWith(dotted + ".")) {
                        if ("date".equals(cm.get("type")) || "timestamp".equals(cm.get("type"))) {
                            return "line";
                        }
                    }
                }
            }
            return rows <= 30 ? "bar" : "table";
        }
        return "table";
    }

    private static Object firstCell(Map<String, Object> r) {
        List<?> rows = (List<?>) r.get("rows");
        return rows.isEmpty() ? null : ((List<?>) rows.get(0)).get(0);
    }

    private static boolean masked(Map<String, Object> r) {
        return ((List<?>) r.get("columns")).stream().anyMatch(c -> Boolean.TRUE.equals(((Map<?, ?>) c).get("masked")));
    }

    private static List<Map<String, Object>> members(Map<String, Object> r, Object total) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object row : (List<?>) r.get("rows")) {
            List<?> cells = (List<?>) row;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("member", cells.get(0));
            m.put("value", cells.get(1));
            if (total instanceof Number t && cells.get(1) instanceof Number v && t.doubleValue() != 0) {
                m.put("share", Math.round(v.doubleValue() / t.doubleValue() * 1000.0) / 10.0);
            }
            out.add(m);
        }
        return out;
    }

    /** The dimensions to break a value down by: the visual's own fields, then the text columns of the tables the measure reads, in the dataset's order. */
    private static List<String> dimensions(BiReportService.Base b, Dataset.Measure m) {
        Set<String> out = new LinkedHashSet<>(b.query().fields());
        Set<String> read = tablesOf(b.dataset(), m);
        for (Dataset.Table t : b.dataset().tables().values()) {
            if (!read.contains(t.name())) {
                continue;
            }
            for (Dataset.Column c : t.columns().values()) {
                if ("string".equals(c.type()) && !c.key() && c.expr() == null) {
                    out.add(t.name() + "." + c.name());
                }
            }
        }
        return List.copyOf(out);
    }

    private static Set<String> tablesOf(Dataset d, Dataset.Measure m) {
        Set<String> out = new LinkedHashSet<>();
        String expr = m.expr() == null ? "" : m.expr();
        for (String t : d.tables().keySet()) {
            if (Pattern.compile("\\b" + Pattern.quote(t) + "\\b").matcher(expr).find()) {
                out.add(t);
            }
        }
        for (String in : m.inputs()) {
            out.add(in.contains(".") ? in.substring(0, in.indexOf('.')) : in);
        }
        return out.isEmpty() ? new LinkedHashSet<>(d.tables().keySet()) : out;
    }

    /** Where a number comes from: the dataset, the measure's definition, the columns it reads and the pack kinds and connectors under them. No value. */
    private static Map<String, Object> lineage(Dataset d, Map<String, Object> model, Dataset.Measure m) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("dataset", Map.of("name", d.name(), "title", String.valueOf(model.getOrDefault("title", d.name())), "version", d.version()));
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", m.name());
        def.put("expr", m.expr());
        def.put("description", m.description());
        if (m.python() != null && !m.python().isBlank()) {
            def.put("python", m.python());
        }
        l.put("measure", def);
        List<Map<String, Object>> cols = new ArrayList<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        for (String tn : tablesOf(d, m)) {
            Dataset.Table t = d.tables().get(tn);
            if (t == null) {
                continue;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("table", t.name());
            s.put("kind", t.kind());
            s.put("connector", t.connector().isEmpty() ? "(any that serves the kind)" : t.connector());
            s.put("mode", t.mode());
            sources.add(s);
            for (Dataset.Column c : t.columns().values()) {
                if (m.expr() != null && Pattern.compile("\\b" + Pattern.quote(t.name() + "." + c.name()) + "\\b").matcher(m.expr()).find()) {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("name", t.name() + "." + c.name());
                    cm.put("type", c.type());
                    cm.put("calculated", c.expr() != null);
                    cm.put("expr", c.expr());
                    cols.add(cm);
                }
            }
        }
        l.put("columns", cols);
        l.put("sources", sources);
        return l;
    }

    /** The reader's own aggregate answer as text for the model: unmasked columns only, at most {@code askRows} rows. */
    private String table(Map<String, Object> answer) {
        List<?> cols = (List<?>) answer.get("columns");
        StringBuilder b = new StringBuilder("RESULT (the reader's own aggregates):\n");
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            Map<?, ?> c = (Map<?, ?>) cols.get(i);
            if (!Boolean.TRUE.equals(c.get("masked"))) {
                keep.add(i);
                b.append(i == keep.get(0) ? "" : " | ").append(c.get("name"));
            }
        }
        b.append('\n');
        int n = 0;
        for (Object row : (List<?>) answer.get("rows")) {
            if (n++ >= props.askRows()) {
                break;
            }
            List<?> cells = (List<?>) row;
            for (int k = 0; k < keep.size(); k++) {
                b.append(k == 0 ? "" : " | ").append(cells.get(keep.get(k)));
            }
            b.append('\n');
        }
        return b.toString();
    }

    private static String breakdownText(String measure, Object value, List<Map<String, Object>> breakdown) {
        StringBuilder b = new StringBuilder("MEASURE: ").append(measure).append("\nTOTAL: ").append(value).append('\n');
        for (Map<String, Object> d : breakdown) {
            b.append("BY ").append(d.get("dimension")).append(":\n");
            for (Object m : (List<?>) d.get("members")) {
                Map<?, ?> mm = (Map<?, ?>) m;
                b.append("  ").append(mm.get("member")).append(" = ").append(mm.get("value")).append(mm.get("share") == null ? "" : " (" + mm.get("share") + "%)").append('\n');
            }
        }
        return b.toString();
    }
}
