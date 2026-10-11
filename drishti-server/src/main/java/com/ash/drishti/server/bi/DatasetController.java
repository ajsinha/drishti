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
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.engine.QueryRequest;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.model.PackModels;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return Map.of("datasets", datasets.list(p));
    }

    /** Checks a dataset's YAML without storing it: the problems with their DRS codes and the path of each. */
    @PostMapping("/datasets/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.AUTHOR);
        return datasets.validate(String.valueOf(body.getOrDefault("yaml", "")));
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
    @PostMapping("/datasets/{id}/query")
    public ResponseEntity<?> query(@PathVariable String id, @RequestBody JsonNode body, @RequestParam(defaultValue = "json") String format,
            @RequestParam(defaultValue = "false") boolean explain, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        QueryService.Result r = datasets.query(p, id, QueryRequest.of(body), explain && bi.mayAuthor(p));         // the SQL names server paths: authors only
        if ("arrow".equalsIgnoreCase(format)) {
            byte[] bytes = datasets.arrow(r);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(ARROW))
                    .header("X-Bi-Layer", r.meta().layer())
                    .header("X-Bi-Rollup", r.meta().rollup() == null ? "" : r.meta().rollup())
                    .header("X-Bi-Rows", Integer.toString(r.rows()))
                    .header("X-Bi-Cached", Boolean.toString(r.meta().cached()))
                    .header("X-Bi-Masked", String.join(",", r.meta().masked()))
                    .header("Server-Timing", "query;dur=" + r.meta().millis())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(bytes);
        }
        if (!"json".equalsIgnoreCase(format)) {
            throw new DrishtiException(ErrorCode.QUERY_INVALID, "format is json or arrow");
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(datasets.json(r));
    }

    /** Clears the dataset's layers for the current business date and reads them again from the connectors. */
    @PostMapping("/datasets/{id}/refresh")
    public Map<String, Object> refresh(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.AUTHOR);
        return datasets.refresh(p, id);
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
        return m;
    }
}
