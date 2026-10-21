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
package com.ash.drishti.server.bi;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.model.PackModels;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Business intelligence, phase 2 (docs/architecture/RUPAKA.md sections 6 and 7): datasets and their query engine. Every call is answered from the
 * caller's identity: which dataset they may read is the catalogue's decision ({@link BiService}), which rows and which fields their query shows
 * is the dataset's row rules and Drishti's masks ({@link DatasetService}).
 */
@RestController
@RequestMapping("/api/v1/bi")
public class DatasetController {

    public static final String ARROW = "application/vnd.apache.arrow.stream";

    private final DatasetService datasets;
    private final BiService bi;

    public DatasetController(DatasetService datasets, BiService bi) {
        this.datasets = datasets;
        this.bi = bi;
    }

    @GetMapping("/datasets")
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean drafts) {
        return Map.of("datasets", datasets.list(p, drafts));
    }

    /** Checks a dataset's YAML without storing it: the problems with their DRS codes and the path of each. */
    @PostMapping("/datasets/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.AUTHOR);
        return datasets.validate(p, String.valueOf(body.getOrDefault("yaml", "")));
    }

    @GetMapping("/datasets/{id}")
    public Map<String, Object> model(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return datasets.model(p, id);
    }

    @GetMapping("/datasets/{id}/layers")
    public Map<String, Object> layers(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return Map.of("tables", datasets.layers(p, id));
    }

    /**
     * Runs a visual query: body {@code {fields, measures, filters, dates, orderBy, limit, layer}}. {@code format=json} (the default) answers a
     * small result as JSON; {@code format=arrow} answers an Arrow IPC stream with the layer, rows and masked columns in {@code X-Bi-*} headers.
     * {@code explain=true} (BI authors) adds the compiled SQL (values are placeholders) and skips the result cache.
     */
    /**
     * Removed in 2.2 (docs/architecture/BI_MODEL_SPEC.md sections 5.9 and 8.5): a dataset is asked through the model query language. Answers {@code 410} with
     * the equivalent model query ({@code mq}) to send to {@code POST /api/v1/bi/mq}: on the model the converter made of a 2.1 dataset (same name), or on a
     * {@code rachana: 2} dataset's implicit model ({@code dataset:<name>}).
     */
    @PostMapping("/datasets/{id}/query")
    public ResponseEntity<Map<String, Object>> query(@PathVariable String id, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "about:blank");
        m.put("title", "dataset query removed");
        m.put("status", 410);
        m.put("code", "DRS-9150");
        m.put("detail", "POST /api/v1/bi/datasets/{id}/query was removed in 2.2: ask the model with POST /api/v1/bi/mq (the query is in `mq`); "
                + "drishti.py bi mq FILE does the same from a shell");
        m.put("mq", mqEquivalent(p, id, body));
        return ResponseEntity.status(HttpStatus.GONE).header(HttpHeaders.CACHE_CONTROL, "no-store").body(m);
    }

    /** The model query a 2.1 dataset query asked: its fields, measures, filters, dates, sort and limit, on the dataset's model. */
    private Map<String, Object> mqEquivalent(Principal p, String id, JsonNode body) {
        String model = id.startsWith("file:dataset:") ? id.substring("file:dataset:".length()) + "@1" : id;     // a 2.1 file converted: the model of its name
        try {
            com.ash.drishti.identity.bi.BiStore.Item item = bi.readableItem(p, id);
            String text = bi.bodyFor(p, item);
            String name = com.ash.drishti.server.bi.dataset.LegacySources.declaredName(text);
            if (!name.isBlank()) {
                model = com.ash.drishti.server.bi.layer.DatasetDefParser.isVersion2(text) ? "dataset:" + name : name + "@1";
            }
        } catch (DrishtiException e) {
            // not one the caller may read: the reference as given
        }
        Map<String, Object> mq = new LinkedHashMap<>();
        mq.put("mq", 1);
        mq.put("model", model);
        QueryRequest q;
        try {
            q = QueryRequest.of(body);
        } catch (DrishtiException e) {
            return mq;
        }
        mq.put("select", Map.of("fields", q.fields(), "measures", q.measures()));
        if (!q.filters().isEmpty()) {
            mq.put("where", q.filters().stream().map(f -> Map.of("field", f.field(), "op", f.op(), "values", f.values())).toList());   // a filter on a measure goes to `having`
        }
        if (q.from() != null) {
            mq.put("time", q.to() == null || q.from().equals(q.to()) ? Map.of("as-of", q.from().toString()) : Map.of("window", Map.of("from", q.from().toString(), "to", q.to().toString())));
        }
        if (!q.orderBy().isEmpty()) {
            mq.put("sort", q.orderBy().stream().map(x -> Map.of("by", x.by(), "desc", x.desc())).toList());
        }
        if (q.limit() != null) {
            mq.put("limit", q.limit());
        }
        return mq;
    }

    /** Clears the dataset's layers for the current business date and reads them again from the connectors. */
    @PostMapping("/datasets/{id}/refresh")
    public Map<String, Object> refresh(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.AUTHOR);
        return datasets.refresh(p, id);
    }

    /**
     * Runs the Python Transform of a derived dataset now, as the caller, and keeps its result for them (BI authors): the columns it returned are remembered, so a
     * dataset that declares none gets a typed table. Answers the columns, rows, whether the result was already kept, and the time the run took.
     */
    @PostMapping("/datasets/{id}/transform/run")
    public Map<String, Object> runTransform(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.AUTHOR);
        return datasets.runTransform(p, id);
    }

    /** When the dataset refreshes and how the last refresh went: schedule, status, last run, next run, rows, duration, failures (a reader of the dataset). */
    @GetMapping("/datasets/{id}/refresh")
    public Map<String, Object> refreshState(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return datasets.refreshState(p, id);
    }

    // ---- the model from the packs ------------------------------------------------------------------------------------

    /** Every kind of every pack the caller may open, as a table with its columns. */
    @GetMapping("/model/kinds")
    public Map<String, Object> kinds(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        return Map.of("tables", datasets.models(p).offer());
    }

    /** Body {@code {kinds: [...], name}}: a draft dataset YAML, the relationships the packs declare (across packs), proposals from the data, and warnings. */
    @PostMapping("/model/start")
    public PackModels.Draft start(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.AUTHOR);
        return datasets.models(p).start(kinds(body), String.valueOf(body.getOrDefault("name", "")));
    }

    /** Body {@code {kinds: [...]}}: relationships proposed by matching values, with the match rate; nothing is accepted by being proposed. */
    @PostMapping("/model/infer")
    public Map<String, Object> infer(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.AUTHOR);
        return Map.of("proposals", datasets.models(p).proposals(kinds(body)));
    }

    private static List<String> kinds(Map<String, Object> body) {
        List<String> out = new ArrayList<>();
        if (body.get("kinds") instanceof List<?> l) {
            l.forEach(x -> out.add(String.valueOf(x)));
        }
        return out;
    }

    /** The engine's counters and limits (BI administrators). */
    @GetMapping("/engine")
    public Map<String, Object> engine(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.ADMIN);
        Map<String, Object> m = new LinkedHashMap<>(datasets.engine());
        java.util.function.Supplier<Map<String, Object>> r = runs;
        if (r != null) {
            m.put("runs", r.get());                                               // BI runs: the queue, workers and the engine they run on (BI_RUNS 17.2)
        }
        return m;
    }

    private volatile java.util.function.Supplier<Map<String, Object>> runs;

    /** What {@code /engine} reports for BI runs (set by the runs wiring when runs are on). */
    public void runs(java.util.function.Supplier<Map<String, Object>> stats) {
        this.runs = stats;
    }
}
