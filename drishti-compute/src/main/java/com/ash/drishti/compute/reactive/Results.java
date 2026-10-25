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

import com.ash.drishti.api.compute.EngineRequest;
import com.ash.drishti.api.compute.EngineResult;
import com.ash.drishti.compute.ComputeException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks an engine's result against its kind's rules and turns it into the kind's document, stamped (REACTIVE_CONNECTORS.md section
 * 8.1): the one row's fields at the top level, every row under {@code RESULTS}, the tables under {@code TABLES}, and under {@code STAMP} the
 * business date, the pin and its input versions, the trigger, when and by which engine it was computed, and the result's hash. A result
 * that breaks the rules is an error of the connector, never shown as data.
 */
public final class Results {

    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Results() {}

    /** Refuses (as {@link ComputeException.Code#INVALID}) a result that breaks the kind's {@code result:} rules. */
    static void validate(ReactiveSpec.KindSpec kind, EngineResult r, String requestId) {
        if (r.rows().size() > kind.maxRows()) {
            throw invalid(requestId, kind.name() + ": the result has " + r.rows().size() + " rows; at most " + kind.maxRows());
        }
        for (Map.Entry<String, List<Map<String, Object>>> t : r.tables().entrySet()) {
            if (t.getValue().size() > kind.maxRows()) {
                throw invalid(requestId, kind.name() + ": table " + t.getKey() + " has " + t.getValue().size() + " rows; at most " + kind.maxRows());
            }
        }
        if (!kind.required().isEmpty() || !kind.types().isEmpty()) {
            Map<String, Object> top = r.rows().size() == 1 ? r.rows().get(0) : Map.of();
            for (String f : kind.required()) {
                if (top.get(f) == null) {
                    throw invalid(requestId, kind.name() + ": the result has no " + f + (r.rows().size() == 1 ? "" : " (it has " + r.rows().size() + " rows, not one)"));
                }
            }
            kind.types().forEach((f, type) -> {
                Object v = top.get(f);
                if (v != null && !is(v, type)) {
                    throw invalid(requestId, kind.name() + ": " + f + " is " + v.getClass().getSimpleName() + ", not " + type);
                }
            });
        }
    }

    private static boolean is(Object v, String type) {
        return switch (type) {
            case "number" -> v instanceof Number;
            case "text" -> v instanceof String;
            case "bool" -> v instanceof Boolean;
            case "list" -> v instanceof Collection<?>;
            default -> v instanceof Map<?, ?>;
        };
    }

    private static ComputeException invalid(String requestId, String message) {
        return new ComputeException(ComputeException.Code.INVALID, "the computation returned an invalid result: " + message, requestId, null);
    }

    /** The kind's document of a result, with its stamp. */
    static Map<String, Object> document(String kind, EngineRequest req, EngineResult r, Map<String, String> inputs, Watermark wm, String engine, Instant at) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("ID", req.id());
        if (r.rows().size() == 1) {
            r.rows().get(0).forEach((k, v) -> {
                if (!k.equals("RESULTS") && !k.equals("TABLES") && !k.equals("STAMP") && !k.equals(Answer.FIELD)) {
                    doc.put(k, v);
                }
            });
        }
        doc.put("RESULTS", r.rows());
        if (!r.tables().isEmpty()) {
            doc.put("TABLES", r.tables());
        }
        Map<String, Object> stamp = new LinkedHashMap<>();
        stamp.put("connector", req.connector());
        stamp.put("kind", kind);
        stamp.put("params", printable(req.params()));
        stamp.put("businessDate", req.asOf().businessDate() == null ? null : req.asOf().businessDate().toString());
        stamp.put("knownAt", req.asOf().knownAt() == null ? null : req.asOf().knownAt().toString());
        stamp.put("pin", req.pin());
        stamp.put("inputs", inputs);
        if (!wm.lanes().isEmpty()) {
            stamp.put("watermark", wm.lanes());
        }
        if (req.trigger() != null) {
            stamp.put("trigger", Map.of("type", req.trigger().type(), "eventId", req.trigger().id()));
        }
        stamp.put("requestId", req.requestId());
        stamp.put("computedAt", at.toString());
        stamp.put("engine", engine);
        Map<String, Object> ran = new LinkedHashMap<>();
        for (String k : List.of("worker", "node", "chunks", "workers", "nodes", "queuedMs", "runMs")) {
            if (r.meta().get(k) != null) {
                ran.put(k, r.meta().get(k));
            }
        }
        if (!ran.isEmpty()) {
            stamp.put("ran", ran);                                   // where it ran: the worker, or the chunks over how many workers and nodes
        }
        stamp.put("signature", req.viewer().viewSignature());
        stamp.put("resultHash", hash(r));
        doc.put("STAMP", stamp);
        return doc;
    }

    private static Map<String, Object> printable(Map<String, Object> params) {
        Map<String, Object> m = new LinkedHashMap<>();
        params.forEach((k, v) -> m.put(k, ParamSpec.text(v)));
        return m;
    }

    /** The result's hash: the same pin, environment and engine give the same hash (reproducibility). */
    static String hash(EngineResult r) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rows", r.rows());
            m.put("tables", r.tables());
            return "sha256:" + sha(JSON.writeValueAsString(m)).substring(0, 32);
        } catch (JsonProcessingException e) {
            return "sha256:?";
        }
    }

    static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The result back from a document (for an incremental update of a cached result). */
    @SuppressWarnings("unchecked")
    static EngineResult result(Map<String, Object> doc) {
        Object rows = doc.get("RESULTS");
        Object tables = doc.get("TABLES");
        return new EngineResult(rows instanceof List<?> l ? (List<Map<String, Object>>) l : List.of(),
                tables instanceof Map<?, ?> t ? (Map<String, List<Map<String, Object>>>) t : Map.of(), Map.of());
    }
}
