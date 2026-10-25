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
package com.ash.drishti.server.compute;

import com.ash.drishti.compute.ComputeException;
import com.ash.drishti.compute.ComputeRequest;
import com.ash.drishti.compute.ComputeResult;
import com.ash.drishti.compute.RequestClass;
import com.ash.drishti.compute.arrow.ArrowIpc;
import com.ash.drishti.compute.arrow.ArrowTable;
import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A compute request and its result as one node sends them to another (a fan-out chunk run on a peer, docs/architecture/PYTHON_COMPUTE.md
 * section 7.6): JSON, the tables as Arrow IPC bytes in base64. Errors travel as {@code {"error": {"code", "message"}}} so the caller
 * gets the same {@link ComputeException} code the peer's worker gave.
 */
final class ComputeWire {

    private ComputeWire() {}

    static Map<String, Object> request(ComputeRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("pool", r.pool());
        m.put("engine", r.engine());
        m.put("kind", r.kind());
        m.put("params", r.params());
        m.put("viewer", Map.of("user", r.viewer().user(), "roles", r.viewer().roles(), "viewSignature", r.viewer().viewSignature()));
        m.put("cls", r.cls().label());
        m.put("timeoutMs", r.timeout() == null ? null : r.timeout().toMillis());
        m.put("seed", r.seed());
        if (r.chunk() != null) {
            m.put("chunk", Map.of("parent", r.chunk().parent(), "index", r.chunk().index(), "of", r.chunk().of()));
        }
        m.put("inputs", tables(r.inputs()));
        return m;
    }

    @SuppressWarnings("unchecked")
    static ComputeRequest request(Map<String, Object> m) {
        Map<String, Object> v = (Map<String, Object>) m.getOrDefault("viewer", Map.of());
        ComputeRequest.Viewer viewer = new ComputeRequest.Viewer(String.valueOf(v.get("user")), (List<String>) v.getOrDefault("roles", List.of()),
                (String) v.get("viewSignature"));
        Map<String, Object> c = (Map<String, Object>) m.get("chunk");
        ComputeRequest.Chunk chunk = c == null ? null : new ComputeRequest.Chunk(String.valueOf(c.get("parent")), ((Number) c.get("index")).intValue(),
                ((Number) c.get("of")).intValue());
        Object t = m.get("timeoutMs");
        return new ComputeRequest(String.valueOf(m.get("id")), String.valueOf(m.get("pool")), (String) m.get("engine"), (String) m.get("kind"),
                (Map<String, Object>) m.get("params"), untables((Map<String, Object>) m.getOrDefault("inputs", Map.of())), viewer,
                RequestClass.of((String) m.get("cls")), t == null ? null : Duration.ofMillis(((Number) t).longValue()),
                m.get("seed") == null ? 0 : ((Number) m.get("seed")).longValue(), chunk);
    }

    static Map<String, Object> result(ComputeResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requestId", r.requestId());
        m.put("rows", r.rows());
        m.put("tables", tables(r.tables()));
        m.put("meta", r.meta());
        m.put("worker", r.worker());
        m.put("node", r.node());
        m.put("queuedMs", r.queuedMs());
        m.put("runMs", r.runMs());
        return m;
    }

    @SuppressWarnings("unchecked")
    static ComputeResult result(Map<String, Object> m) {
        if (m.get("error") instanceof Map<?, ?> e) {
            ComputeException.Code code;
            try {
                code = ComputeException.Code.valueOf(String.valueOf(e.get("code")).toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException x) {
                code = ComputeException.Code.FAILED;
            }
            throw new ComputeException(code, String.valueOf(e.get("message")), (String) e.get("requestId"), null);
        }
        return new ComputeResult(String.valueOf(m.get("requestId")), (List<Map<String, Object>>) m.get("rows"),
                untables((Map<String, Object>) m.getOrDefault("tables", Map.of())), (Map<String, Object>) m.get("meta"), (String) m.get("worker"),
                (String) m.get("node"), ((Number) m.getOrDefault("queuedMs", 0)).longValue(), ((Number) m.getOrDefault("runMs", 0)).longValue());
    }

    static Map<String, Object> error(ComputeException e) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", e.code().label());
        err.put("message", e.getMessage());
        err.put("requestId", e.requestId());
        return Map.of("error", err);
    }

    private static Map<String, Object> tables(Map<String, ArrowTable> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        in.forEach((k, t) -> out.put(k, Base64.getEncoder().encodeToString(ArrowIpc.toBytes(t))));
        return out;
    }

    private static Map<String, ArrowTable> untables(Map<String, Object> in) {
        Map<String, ArrowTable> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            try {
                out.put(e.getKey(), ArrowIpc.read(Base64.getDecoder().decode(String.valueOf(e.getValue()))));
            } catch (IOException | IllegalArgumentException x) {
                throw new ComputeException(ComputeException.Code.INVALID, "table " + e.getKey() + " is not a valid Arrow stream: " + x.getMessage());
            }
        }
        return out;
    }
}
