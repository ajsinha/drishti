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
package com.ash.drishti.server.bi.runs;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.DatasetController;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.mq.ModelCatalog;
import com.ash.drishti.server.bi.mq.ModelQuery;
import com.ash.drishti.server.bi.mq.MqParser;
import com.ash.drishti.server.bi.mq.MqService;
import com.ash.drishti.server.bi.plan.SecuritySignature;
import com.ash.drishti.server.bi.report.BiReportService;
import com.ash.drishti.server.bi.sql.DsqlException;
import com.ash.drishti.server.bi.sql.SqlService;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Turns a BI question (a model query, a SQL statement, a report visual) into a {@link RunService.Question} computed as its caller, and a run's
 * outcome into the HTTP answer (docs/architecture/BI_RUNS.md sections 6.1 and 14): {@code 200} with today's body when it finished inside the wait,
 * {@code 202 {run}} when it became a visible job. Used by {@code POST /api/v1/bi/runs} and by the existing endpoints when the caller sends
 * {@code Prefer: respond-async}; without that header the existing endpoints answer exactly as before.
 */
public final class RunSources {

    /** This process: an engine generation is only a data version within one process (generations start again at every start). */
    static final String BOOT = UUID.randomUUID().toString().substring(0, 8);
    private static final Pattern WAIT = Pattern.compile("(?i)\\bwait\\s*=\\s*(\\d+(?:\\.\\d+)?)");
    private static final ObjectMapper CANON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final RunService runs;
    private final ModelCatalog catalog;
    private final MqService mq;
    private final SqlService sql;
    private final BiReportService reports;

    public RunSources(RunService runs, ModelCatalog catalog, MqService mq, SqlService sql, BiReportService reports) {
        this.runs = runs;
        this.catalog = catalog;
        this.mq = mq;
        this.sql = sql;
        this.reports = reports;
    }

    public RunService runs() {
        return runs;
    }

    /** Whether a request asked for an asynchronous answer ({@code Prefer: respond-async}) and runs are on. */
    public boolean async(String prefer) {
        return runs.properties().enabled() && prefer != null && prefer.toLowerCase(java.util.Locale.ROOT).contains("respond-async");
    }

    /** The inline wait a {@code Prefer} header asks for ({@code wait=<seconds>}), capped by {@code inline.max-wait}; the default otherwise. */
    public Duration waitOf(String prefer) {
        Matcher m = WAIT.matcher(prefer == null ? "" : prefer);
        Duration d = m.find() ? Duration.ofMillis((long) (Double.parseDouble(m.group(1)) * 1000)) : runs.properties().inline().inlineWait();
        return d.compareTo(runs.properties().inline().maxWait()) > 0 ? runs.properties().inline().maxWait() : d;
    }

    static String canonical(Object o) {
        try {
            return CANON.writeValueAsString(CANON.convertValue(o, Object.class));
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }

    /** A data version from the model's generation within this process, or null (not reusable) when the model has none. */
    static String version(MqService.Bound b) {
        if (b.generation() == null) {
            return null;
        }
        return "gen:" + BOOT + ":" + b.generation().getAsLong() + ":" + Integer.toHexString(b.view().flat().hashCode());
    }

    /** The interaction flags of a body ({@code interaction}, {@code supersedes}), removed from it so the question parses as before. */
    record Flags(boolean interaction, String supersedes, String title) {
        static Flags take(JsonNode body) {
            if (body == null || !body.isObject()) {
                return new Flags(false, null, null);
            }
            ObjectNode o = (ObjectNode) body;
            boolean i = o.path("interaction").asBoolean(false);
            String s = o.path("supersedes").isTextual() ? o.path("supersedes").asText() : null;
            o.remove("interaction");
            o.remove("supersedes");
            return new Flags(i, s, null);
        }
    }

    // ---- questions ----------------------------------------------------------------------------------------------------

    public RunService.Question mq(Principal p, ModelQuery q, JsonNode raw, String modelRef, boolean interaction, String supersedes, String title) {
        String model = modelRef != null ? modelRef : q.model();
        ModelQuery query = modelRef != null ? q.withModel(modelRef) : q;
        MqService.Bound b = catalog.bind(p, model);
        Map<String, Object> canon = new LinkedHashMap<>();
        canon.put("type", "mq");
        canon.put("model", model);
        canon.put("query", query.toString());
        if (raw != null) {
            canon.put("mq", CANON.convertValue(raw, Object.class));        // replayable after a restart
        }
        RunPlanner.Planned planned = plan(b, query, interaction);
        return new RunService.Question(p, "mq", model, null, title, null, model, canonical(canon), null, b.signature(), version(b, planned), interaction,
                supersedes, () -> {
                    MqService.Answer a = mq.ask(b, query, false);
                    return new RunService.Answer(a.body(), a.result().columns(), a.result().rows());
                }, planned);
    }

    /** The planner's view of a model query (section 4), from metadata only; null when it cannot be sized without reading (a top filter, staged tables). */
    RunPlanner.Planned plan(MqService.Bound b, ModelQuery q, boolean interaction) {
        RunPlanner pl = runs.planner();
        if (pl == null) {
            return null;
        }
        return mq.footprint(b, q).map(f -> pl.plan(f, interaction)).orElse(null);
    }

    /**
     * The data version of a planned question (section 8.2): the hash of the data version vector of every table and date it reads, with the model's
     * definition. It is the same after a restart while the data is unchanged, so a stored result is reused then, and it moves when a date is restated,
     * so it is never reused over changed data. Null (not reusable) when a source cannot tell its version or a live table is read. Unplanned
     * questions keep the process's generation (reused within this process only).
     */
    static String version(MqService.Bound b, RunPlanner.Planned planned) {
        if (planned == null) {
            return version(b);
        }
        // the dates each table resolves to (as of D: each its latest on or before D, 2.6.7 patch) are part of the key: a reused answer is for the same dates
        return planned.data().complete() ? "dv:" + planned.data().hash() + ":" + b.view().hash() + ":" + planned.dates() : null;
    }

    public RunService.Question sql(Principal p, String model, String statement, boolean interaction, String supersedes, String title) {
        MqService.Bound b = catalog.bind(p, model);
        SqlService.Compiled c;
        try {
            c = sql.compile(b, model, statement);
        } catch (DsqlException e) {
            throw e.toMq();
        }
        if (c.explain()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "EXPLAIN answers at once; ask it without Prefer: respond-async");
        }
        Map<String, Object> canon = Map.of("type", "sql", "model", model, "sql", statement.strip());
        RunPlanner.Planned planned = c.leaf() == null ? null : plan(b, c.leaf().query(), interaction);   // a composite reads several questions
        return new RunService.Question(p, "sql", model, null, title, null, model, canonical(canon), null, b.signature(), version(b, planned), interaction,
                supersedes, () -> {
                    SqlService.Answer a = sql.run(b, c);
                    return new RunService.Answer(a.body(), a.columns(), a.rows());
                }, planned);
    }

    /**
     * A report visual. Its signature is personal (the caller's name and roles): a report's result is reused only by the same person, never across
     * people whose row rules could differ by attribute. It has no data version, so it is stored for its runner and never served for another submit.
     */
    public RunService.Question report(Principal p, String id, String page, String visual, JsonNode state, boolean interaction, String supersedes, String title) {
        Map<String, Object> canon = new LinkedHashMap<>();
        canon.put("type", "report");
        canon.put("id", id);
        canon.put("page", page);
        canon.put("visual", visual);
        canon.put("state", state == null ? null : CANON.convertValue(state, Object.class));
        String signature = SecuritySignature.sha("personal|" + p.user() + "|" + p.securitySignature());
        String params = state == null ? null : canonical(state);
        RunPlanner pl = runs.planner();
        RunPlanner.Planned planned = pl == null || reports == null ? null : reportPlan(reports.footprint(p, id, page, visual, state).map(f -> pl.plan(f, interaction)).orElse(null));
        if (planned != null) {
            canon.put("dates", planned.dates().stream().map(String::valueOf).toList());   // the resolved dates: a reused answer is for the same dates (2.6.7 patch)
        }
        BiReportService.Titles titles = reports == null ? new BiReportService.Titles(null, null) : reports.titles(p, id, page, visual);
        String named = title != null && !title.isBlank() ? title : titles.visual() != null ? titles.visual() : titles.report();   // BIR-10 (14.2)
        return new RunService.Question(p, "report", id, page + "/" + visual, named, null, null, canonical(canon), params, signature, null, interaction,
                supersedes, () -> {
                    Map<String, Object> body = reports.query(p, id, null, page, visual, state, false);
                    Object rc = body.get("rowCount");
                    Object rows = body.get("rows");
                    int n = rc instanceof Number x ? x.intValue() : rows instanceof List<?> l ? l.size() : 0;
                    return new RunService.Answer(body, null, n);
                }, planned, titles.report());
    }

    /**
     * The plan of a report visual (BIR-06, sections 5 and 6): a visual sized as ad-hoc heavy is a background run at once, asks to confirm above the
     * thresholds ({@code DRS-9082}) and, as an interaction, needs a run ({@code DRS-9089}); one past every engine is refused {@code DRS-9086}. A visual whose
     * size is not known (a connector that cannot count, nothing built yet) is not asked to confirm and not refused: it waits inline and becomes a run card
     * past the wait, as before (asking to confirm every visual of a page on such a source would break dashboards), the same rule as an interaction of
     * unknown size.
     */
    static RunPlanner.Planned reportPlan(RunPlanner.Planned planned) {
        if (planned == null || planned.estimate().known()) {
            return planned;
        }
        return planned.with(new RunEstimator.Decision(Tier.INTERACTIVE, false, Optional.empty()));
    }

    /**
     * A statement (a preview, or its PDF or Excel file) made for the caller: a personal signature and no data version, as a report visual (stored for its
     * runner, never served to another). {@code work} answers the JSON body (the preview, or the file as base64 with its name and type).
     */
    public RunService.Question statement(Principal p, JsonNode body, String kind, String title, java.util.function.Supplier<Map<String, Object>> work) {
        Map<String, Object> canon = new LinkedHashMap<>();
        canon.put("type", "statement");
        canon.put("kind", kind);
        canon.put("request", body == null ? null : CANON.convertValue(body, Object.class));
        String id = body == null ? null : body.path("report").asText(null);
        String signature = SecuritySignature.sha("personal|" + p.user() + "|" + p.securitySignature());
        return new RunService.Question(p, "statement", id, kind, title, null, null, canonical(canon), null, signature, null, false, null, () -> {
            Map<String, Object> b = work.get();
            Object rows = b.get("pages");
            return new RunService.Answer(b, null, rows instanceof List<?> l ? l.size() : rows instanceof Number n ? n.intValue() : 0);
        });
    }

    /** Makes a statement again as its runner (a replayed statement run): the preview, or the file as {@link #statement} stores it. */
    @FunctionalInterface
    public interface StatementWork {
        Map<String, Object> make(Principal p, JsonNode body, String kind);
    }

    private volatile StatementWork statements;

    /** How a statement run is made again after a restart (the statement module sets it; without it such a run fails DRS-9085 as before). */
    public void statements(StatementWork w) {
        this.statements = w;
    }

    /** The question of {@code POST /api/v1/bi/runs}'s {@code source} (14.2). */
    public RunService.Question of(Principal p, JsonNode source, JsonNode options) {
        String type = source.path("type").asText("");
        boolean interaction = options.path("interaction").asBoolean(false);
        String supersedes = options.path("supersedes").isTextual() ? options.path("supersedes").asText() : null;
        String title = options.path("title").isTextual() ? options.path("title").asText() : null;
        return switch (type) {
            case "mq" -> {
                JsonNode q = source.path("mq");
                ModelQuery query = q.isTextual() ? MqParser.parse(q.asText()) : MqParser.parse(q);
                yield mq(p, query, q.isTextual() ? null : q, null, interaction, supersedes, title);
            }
            case "sql" -> sql(p, source.path("model").asText(""), source.path("sql").asText(""), interaction, supersedes, title);
            case "report", "board" -> report(p, source.path("id").asText(""), source.path("page").asText(""), source.path("visual").asText(""),
                    source.get("state"), interaction, supersedes, title);
            default -> throw new DrishtiException(ErrorCode.BAD_REQUEST, "source.type is mq, sql, report or board, not '" + type + "'");
        };
    }

    /** Replays a stored run as its runner after a restart: a model query, a SQL statement, a report visual or a statement (preview or file). */
    public Optional<RunService.Question> replay(Run r, Principal runner) {
        Object q = RunJson.parse(r.question());
        if (!(q instanceof Map<?, ?> m) || runner == null) {
            return Optional.empty();
        }
        try {
            return switch (String.valueOf(m.get("type"))) {
                case "mq" -> m.get("mq") == null ? Optional.empty() : Optional.of(mq(runner, MqParser.parse(CANON.valueToTree(m.get("mq"))),
                        CANON.valueToTree(m.get("mq")), String.valueOf(m.get("model")), false, null, r.title()));
                case "report" -> Optional.of(report(runner, String.valueOf(m.get("id")), String.valueOf(m.get("page")), String.valueOf(m.get("visual")),
                        m.get("state") == null ? null : CANON.valueToTree(m.get("state")), false, null, r.title()));
                case "sql" -> Optional.of(sql(runner, String.valueOf(m.get("model")), String.valueOf(m.get("sql")), false, null, r.title()));
                case "statement" -> {                                           // made again as its runner, from the request it was asked with
                    StatementWork w = statements;
                    JsonNode body = m.get("request") == null ? null : CANON.valueToTree(m.get("request"));
                    String kind = String.valueOf(m.get("kind"));
                    yield w == null || body == null || !Set.of("preview", "file").contains(kind) ? Optional.empty()
                            : Optional.of(statement(runner, body, kind, r.title(), () -> w.make(runner, body, kind)));
                }
                default -> Optional.empty();
            };
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Whether a model already has a rollup over the drafted table at the drafted grain (a suggestion is then {@code built}, 8.3a). */
    public java.util.function.BiPredicate<String, Map<String, Object>> rollupBuilt(Principal p) {
        return (model, rollup) -> {
            try {
                java.util.Set<String> by = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                if (rollup.get("by") instanceof List<?> l) {
                    l.forEach(x -> by.add(String.valueOf(x)));
                }
                return catalog.bind(p, model).view().def().rollups().stream().anyMatch(r -> r.table().equals(rollup.get("table")) && r.by().size() == by.size()
                        && by.containsAll(r.by()));
            } catch (RuntimeException e) {
                return false;
            }
        };
    }

    // ---- answers ------------------------------------------------------------------------------------------------------

    /** The HTTP answer of an outcome: {@code 200} today's body (JSON or Arrow) with {@code X-Bi-Run}, or {@code 202 {run}} with {@code Location}. */
    public ResponseEntity<?> respond(RunService.Outcome o, String format, Principal p) {
        if (o.inline()) {
            RunService.Answer a = o.answer();
            if ("arrow".equalsIgnoreCase(format) && a.columns() != null) {
                return ResponseEntity.ok().contentType(MediaType.parseMediaType(DatasetController.ARROW)).header("X-Bi-Rows", Integer.toString(a.rows()))
                        .header("X-Bi-Run", o.run().id()).header("Drishti-Run", o.run().id()).header(HttpHeaders.CACHE_CONTROL, "no-store").body(ArrowIpcWriter.write(a.columns(), a.rows()));
            }
            return ResponseEntity.ok().header("X-Bi-Run", o.run().id()).header("Drishti-Run", o.run().id()).header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(a.body());
        }
        return ResponseEntity.accepted().location(URI.create("/api/v1/bi/runs/" + o.run().id())).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("run", RunJson.of(o.run(), RunJson.View.of(p.user()), java.time.Instant.now())));
    }

    /** Submits and answers in one step (the existing endpoints with {@code Prefer: respond-async}). */
    public ResponseEntity<?> submit(RunService.Question q, String prefer, String format, Principal p) {
        return submit(q, prefer, format, p, false, null);
    }

    /**
     * As {@link #submit(RunService.Question, String, String, Principal)}, with the body's {@code confirm} (accepted in advance: no {@code DRS-9082}; also
     * the re-ask after a {@code DRS-9089} with {@code interaction: false}) and {@code confirmToken} (confirms the {@code awaiting-confirm} run it was given
     * for, instead of starting another).
     */
    public ResponseEntity<?> submit(RunService.Question q, String prefer, String format, Principal p, boolean confirm, String confirmToken) {
        if (confirmToken != null && !confirmToken.isBlank()) {
            Optional<Run> confirmed = runs.confirmByToken(p.user(), confirmToken);
            if (confirmed.isPresent()) {
                return respond(new RunService.Outcome(null, confirmed.get()), format, p);
            }
        }
        Duration wait = waitOf(prefer);
        return respond(runs.submit(q, new RunService.Options(wait, wait.isZero(), confirm || confirmToken != null && !confirmToken.isBlank())), format, p);
    }
}
