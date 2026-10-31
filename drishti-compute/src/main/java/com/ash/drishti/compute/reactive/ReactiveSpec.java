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
package com.ash.drishti.compute.reactive;

import com.ash.drishti.compute.ConfigValues;
import com.ash.drishti.compute.RequestClass;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * A reactive connector's computation as its file declares it (DATA_ARCHITECTURE.md › Reactive connectors, sections 5 and 17): the kinds it answers with their
 * typed parameters and result rules, the inputs it reads as the viewer, the engine, the triggers, coalescing, the cache and the limits.
 * Parsed once when the connector starts; every mistake is refused with the key that holds it.
 */
public record ReactiveSpec(Map<String, KindSpec> kinds, List<ReadSpec> reads, EngineSpec engine, List<TriggerSpec> triggers, Duration quiet,
        Duration maxWait, int cacheResults, Duration cacheTtl, Limits limits, boolean dated, int verifyEvery) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * A computed kind: its parameters, the rules every result must meet, and whether the engine can update it from events.
     *
     * @param required fields a one-row result must have
     * @param types field to type ({@code number}, {@code text}, {@code bool}, {@code list}, {@code object}) a result's fields must have
     * @param maxRows the most rows (and rows per table) a result may have
     * @param engineKind what the engine calls this computation ({@code compute:}; the kind's own name when absent)
     */
    public record KindSpec(String name, Map<String, ParamSpec> params, List<String> required, Map<String, String> types, int maxRows,
            boolean incremental, String engineKind) {

        /** The request's parameters checked and normalised: defaults filled, types converted, unknown names refused; sorted by name. */
        public Map<String, Object> normalise(Map<String, ?> given) {
            Map<String, ?> g = given == null ? Map.of() : given;
            for (String k : g.keySet()) {
                if (!params.containsKey(k)) {
                    throw new IllegalArgumentException(name + " has no parameter '" + k + "'" + (params.isEmpty() ? "" : " (parameters: "
                            + String.join(", ", params.keySet()) + ")"));
                }
            }
            Map<String, Object> out = new java.util.TreeMap<>();
            params.forEach((k, p) -> {
                Object v = p.normalise(name, g.get(k));
                if (v != null) {
                    out.put(k, v);
                }
            });
            return out;
        }

        /** The parameter of type {@code sandbox-ref}, if the kind has one. */
        public java.util.Optional<String> sandboxParam() {
            return params.values().stream().filter(p -> "sandbox-ref".equals(p.type())).map(ParamSpec::name).findFirst();
        }
    }

    /**
     * One input: a kind read through the normal routing as the viewer, under a name the engine knows it by.
     *
     * @param kind the kind read
     * @param as the input's name ({@code [A-Za-z0-9._-]})
     * @param id a template of one entity's id ({@code {id}}, {@code BOOK-{params.book}}), or null
     * @param ids a template of a list of ids ({@code {params.members}}: a list or comma-separated text), or null
     * @param all every entity of the kind on the date, at most {@code limit}
     * @param limit the most entities read
     */
    public record ReadSpec(String kind, String as, String id, String ids, boolean all, int limit) {}

    /**
     * Where the code runs.
     *
     * @param type {@code python} (a compute pool) or {@code java} (in the server's own process)
     * @param entry the Python entry ({@code module:Class}) or the Java class ({@code com.example.MyEngine})
     * @param pool the compute pool of {@code config/compute/compute.yaml} (Python)
     * @param split the input a Python request is split by over every worker of the pool (fan-out), or null for one worker
     * @param chunkRows rows per chunk when split (0: the site's {@code compute.fan-out.chunk-rows})
     * @param version the engine's version as the file pins it (part of every key and stamp)
     * @param reduce how the chunks' answers become one (with {@code split}), or null
     */
    public record EngineSpec(String type, String entry, String pool, String split, int chunkRows, String version, Reduce reduce) {}

    /**
     * When results are recomputed without being asked (section 7).
     *
     * @param on {@code write-back}, {@code live}, {@code data-version} or {@code schedule}
     * @param kinds the kinds whose events fire it (any when empty)
     * @param affects {@code all}, {@code subscribed}, id templates, or an expression the engine evaluates
     * @param recompute the computed kinds it recomputes (every kind when empty)
     * @param cron a schedule's cron expression (six fields, seconds first), or null
     * @param zone the cron's time zone
     * @param every a schedule's interval instead of a cron, or null
     * @param when for {@code write-back}: {@code applied} or {@code confirmed}
     */
    public record TriggerSpec(String on, List<String> kinds, List<String> affects, List<String> recompute, String cron, String zone, Duration every,
            String when) {

        public boolean affectsAll() {
            return affects.size() == 1 && "all".equals(affects.get(0));
        }

        public boolean affectsSubscribed() {
            return affects.isEmpty() || affects.size() == 1 && "subscribed".equals(affects.get(0));
        }

        public boolean firesOn(String type, String kind) {
            return on.equals(type) && (kinds.isEmpty() || kinds.contains(kind) || kind == null || kind.isEmpty());
        }
    }

    /**
     * Budgets and fairness (section 10.3).
     *
     * @param timeout an interactive request's budget
     * @param timeoutBatch an event or batch request's budget
     * @param answerWait how long a panel waits for a result before it is drawn <em>computing</em> and filled from the live channel
     * @param maxInflightPerUser one person's computations running at once on this connector
     * @param eventQueue keys waiting to be recomputed by triggers
     * @param maxInputRows the most documents a request may read as inputs (larger: refused before any read)
     * @param dedupeWindow how long processed event ids are remembered
     */
    public record Limits(Duration timeout, Duration timeoutBatch, Duration answerWait, int maxInflightPerUser, int eventQueue, int maxInputRows,
            Duration dedupeWindow) {

        public Duration budget(RequestClass c) {
            return c == RequestClass.INTERACTIVE ? timeout : timeoutBatch;
        }
    }

    /** Reads the computation from its JSON (the setting {@code reactive.spec}); placeholders resolved by {@code resolve}. */
    public static ReactiveSpec parse(String json, String where, UnaryOperator<String> resolve) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(where + " declares no computation: a reactive connector names its kinds and engine");
        }
        Map<String, Object> m;
        try {
            m = JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new IllegalArgumentException(where + ": the computation is not readable: " + e.getMessage());
        }
        return parse(new ConfigValues(m, "", resolve), where);
    }

    @SuppressWarnings("unchecked")
    static ReactiveSpec parse(ConfigValues c, String where) {
        Map<String, KindSpec> kinds = new LinkedHashMap<>();
        ConfigValues ks = c.section("kinds");
        if (ks.keys().isEmpty()) {
            throw new IllegalArgumentException(where + ": kinds names the kinds it computes, each with its params");
        }
        for (String k : ks.keys()) {
            ConfigValues kc = ks.raw(k) == null ? new ConfigValues(Map.of(), "kinds." + k, null) : ks.section(k);
            Map<String, ParamSpec> params = new LinkedHashMap<>();
            ConfigValues ps = kc.section("params");
            for (String p : ps.keys()) {
                ConfigValues pc = ps.raw(p) instanceof Map<?, ?> ? ps.section(p) : new ConfigValues(Map.of("type", String.valueOf(ps.raw(p))), "", null);
                params.put(p, ParamSpec.parse(k, p, pc));
            }
            if (params.values().stream().filter(p -> "sandbox-ref".equals(p.type())).count() > 1) {
                throw new IllegalArgumentException(where + ": kinds." + k + " has more than one sandbox-ref parameter");
            }
            ConfigValues rc = kc.section("result");
            Map<String, String> types = new LinkedHashMap<>();
            ConfigValues tc = rc.section("types");
            for (String f : tc.keys()) {
                String t = tc.text(f, "").toLowerCase(Locale.ROOT);
                if (!List.of("number", "text", "bool", "list", "object").contains(t)) {
                    throw new IllegalArgumentException(where + ": kinds." + k + ".result.types." + f + " is number, text, bool, list or object");
                }
                types.put(f, t);
            }
            kinds.put(k, new KindSpec(k, params, rc.list("required"), types, rc.integer("max-rows", 100_000, 1, 10_000_000),
                    kc.flag("incremental", false), kc.text("compute", k)));
        }
        List<ReadSpec> reads = new ArrayList<>();
        Object rs = c.raw("reads");
        if (rs instanceof List<?> l) {
            Set<String> names = new LinkedHashSet<>();
            for (int i = 0; i < l.size(); i++) {
                if (!(l.get(i) instanceof Map<?, ?> rm)) {
                    throw new IllegalArgumentException(where + ": reads[" + i + "] is a mapping ({ kind: ..., as: ... })");
                }
                ConfigValues r = new ConfigValues((Map<String, Object>) rm, "reads[" + i + "]", null);
                String kind = r.text("kind", null);
                if (kind == null) {
                    throw new IllegalArgumentException(where + ": reads[" + i + "] names its kind");
                }
                String as = r.text("as", kind);
                if (!as.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || !names.add(as)) {
                    throw new IllegalArgumentException(where + ": reads[" + i + "].as '" + as + "' is not a usable input name (letters, digits, '.', '_', '-'; once each)");
                }
                boolean all = r.flag("all", false);
                String id = r.text("id", null);
                String ids = r.text("ids", null);
                if ((all ? 1 : 0) + (id != null ? 1 : 0) + (ids != null ? 1 : 0) > 1) {
                    throw new IllegalArgumentException(where + ": reads[" + i + "] says one of id, ids or all");
                }
                reads.add(new ReadSpec(kind, as, id == null && ids == null && !all ? "{id}" : id, ids, all, r.integer("limit", 10_000, 1, 5_000_000)));
            }
        } else if (rs != null) {
            throw new IllegalArgumentException(where + ": reads is a list of { kind, as, id | ids | all }");
        }
        ConfigValues e = c.section("engine");
        String type = e.text("type", "python").toLowerCase(Locale.ROOT);
        if (!type.equals("python") && !type.equals("java")) {
            throw new IllegalArgumentException(where + ": engine.type is python or java, not '" + type + "'");
        }
        String entry = e.text("entry", e.text("class", null));
        if (entry == null) {
            throw new IllegalArgumentException(where + ": engine.entry names the code (module:Class for python, a class for java)");
        }
        if (type.equals("python") && !entry.matches("[A-Za-z_][A-Za-z0-9_.]*:[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(where + ": engine.entry is module:Class for a python engine, not '" + entry + "'");
        }
        String pool = e.text("pool", null);
        if (type.equals("python") && pool == null) {
            throw new IllegalArgumentException(where + ": engine.pool names the compute pool of config/compute/compute.yaml that runs it");
        }
        EngineSpec engine = new EngineSpec(type, entry, pool, e.text("split", null), e.integer("chunk-rows", 0, 0, 10_000_000), e.text("version", "1"),
                Reduce.parse(e.section("reduce")));
        List<TriggerSpec> triggers = new ArrayList<>();
        Object ts = c.raw("triggers");
        if (ts instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) {
                if (!(l.get(i) instanceof Map<?, ?> tm)) {
                    throw new IllegalArgumentException(where + ": triggers[" + i + "] is a mapping ({ on: ..., affects: ... })");
                }
                triggers.add(trigger(new ConfigValues((Map<String, Object>) tm, "triggers[" + i + "]", null), where, kinds.keySet()));
            }
        } else if (ts != null) {
            throw new IllegalArgumentException(where + ": triggers is a list of { on, kinds, affects, recompute }");
        }
        ConfigValues co = c.section("coalesce");
        Duration quiet = co.duration("quiet", Duration.ofMillis(250));
        Duration maxWait = co.duration("max-wait", Duration.ofSeconds(2));
        if (maxWait.compareTo(quiet) < 0) {
            throw new IllegalArgumentException(where + ": coalesce.max-wait (" + maxWait.toMillis() + " ms) is shorter than coalesce.quiet (" + quiet.toMillis() + " ms)");
        }
        ConfigValues ca = c.section("cache");
        ConfigValues li = c.section("limits");
        Limits limits = new Limits(li.duration("timeout", Duration.ofSeconds(30)), li.duration("timeout-batch", Duration.ofMinutes(10)),
                li.duration("wait", Duration.ofMillis(150)), li.integer("max-inflight-per-user", 4, 1, 10_000), li.integer("event-queue", 10_000, 1, 10_000_000),
                li.integer("max-input-rows", 1_000_000, 1, 100_000_000), li.duration("dedupe-window", Duration.ofHours(24)));
        return new ReactiveSpec(kinds, reads, engine, triggers, quiet, maxWait, ca.integer("results", 20_000, 0, 10_000_000),
                ca.duration("ttl", Duration.ofHours(8)), limits, c.section("history").flag("dated", true), c.section("incremental").integer("verify-every", 50, 0, 1_000_000));
    }

    private static TriggerSpec trigger(ConfigValues t, String where, Set<String> computed) {
        String on = t.text("on", null);
        List<String> types = List.of("request", "write-back", "live", "data-version", "schedule");
        if (on == null || !types.contains(on)) {
            throw new IllegalArgumentException(where + ": " + t.where() + ".on is one of " + types + ", not '" + on + "'");
        }
        List<String> affects = t.list("affects");
        List<String> recompute = t.list("recompute");
        for (String r : recompute) {
            if (!computed.contains(r)) {
                throw new IllegalArgumentException(where + ": " + t.where() + ".recompute names " + r + ", which this connector does not compute (kinds: " + computed + ")");
            }
        }
        String cron = t.text("cron", null);
        Duration every = t.duration("every", (Duration) null);
        if ("schedule".equals(on) && cron == null && every == null) {
            throw new IllegalArgumentException(where + ": " + t.where() + " is a schedule: it says cron or every");
        }
        if (every != null && every.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException(where + ": " + t.where() + ".every is at least 1s");
        }
        String when = t.text("when", "applied");
        if (!when.equals("applied") && !when.equals("confirmed")) {
            throw new IllegalArgumentException(where + ": " + t.where() + ".when is applied or confirmed, not '" + when + "'");
        }
        return new TriggerSpec(on, t.list("kinds"), affects, recompute, cron, t.text("zone", "UTC"), every, when);
    }

    public KindSpec kind(String kind) {
        return kinds.get(kind);
    }

    /** The kinds the computation reads, for the pin and for cycles. */
    public Set<String> readKinds() {
        Set<String> s = new LinkedHashSet<>();
        reads.forEach(r -> s.add(r.kind()));
        return s;
    }
}
