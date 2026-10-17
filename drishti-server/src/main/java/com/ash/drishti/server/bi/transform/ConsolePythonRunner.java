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
package com.ash.drishti.server.bi.transform;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The {@code Py*} nodes of the plan run on the console backend (docs/architecture/BI_DESIGN.md sections 3.5 and 4.3; wave B task 4: it replaces the process
 * runner). The Java planner calls the console's Py-node endpoint over HTTP, the console runs the item in its fork-server sandbox and answers:
 *
 * <pre>
 * POST {runner-url}/internal/py/transform   {manifest, params, inputs: [base64 Arrow IPC], max_rows, max_bytes}
 *   200 body: the result as Parquet (application/vnd.apache.parquet); headers X-Drishti-Rows, X-Drishti-Ms, X-Drishti-Columns (JSON [{name, type}])
 * POST {runner-url}/internal/py/measure     {manifest, params, group_by: [names], columns: [names], rows: [[...]]}
 *   200 {ok: true, by: [names], rows: [[group values..., value]], ms}
 * a refusal: {ok: false, code: DRS-907x, status, detail}
 * </pre>
 *
 * Every call carries {@code X-Drishti-Runner-Token} (the shared secret {@code drishti.bi.python.runner-token}); at most {@code max-concurrent} calls at once
 * (then {@code DRS-9074} after {@code queue-wait}); a call longer than {@code timeout} is {@code DRS-9072}. Thread-safe.
 */
public final class ConsolePythonRunner implements TransformRunner {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TransformProperties props;
    private final URI base;
    private final String token;
    private final HttpClient http;
    private final Semaphore slots;

    public ConsolePythonRunner(TransformProperties props, String url, String token) {
        this.props = props;
        this.base = URI.create(url.endsWith("/") ? url : url + "/");
        this.token = token == null ? "" : token;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.slots = new Semaphore(props.maxConcurrent(), true);
    }

    @Override
    public Result run(Request r) {
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.PYTHON_BUSY, "Python Transforms are switched off (drishti.bi.transform.enabled)");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("manifest", r.manifest());
        body.put("params", r.params());
        List<String> inputs = new ArrayList<>();
        r.frames().forEach(f -> inputs.add(Base64.getEncoder().encodeToString(f)));
        body.put("inputs", inputs);
        body.put("max_rows", r.maxRows());
        body.put("max_bytes", r.maxBytes());
        HttpResponse<byte[]> resp = call("internal/py/transform", body);
        String type = resp.headers().firstValue("Content-Type").orElse("");
        if (type.startsWith("application/json")) {
            throw failure(read(resp.body()));
        }
        try {
            Files.write(r.output(), resp.body());
            List<Column> cols = new ArrayList<>();
            JsonNode c = JSON.readTree(resp.headers().firstValue("X-Drishti-Columns").orElse("[]"));
            c.forEach(x -> cols.add(new Column(x.path("name").asText(), x.path("type").asText("string"))));
            long rows = Long.parseLong(resp.headers().firstValue("X-Drishti-Rows").orElse("0"));
            long ms = Long.parseLong(resp.headers().firstValue("X-Drishti-Ms").orElse("0"));
            return new Result(r.output(), cols, rows, ms);
        } catch (IOException | NumberFormatException e) {
            throw new DrishtiException(ErrorCode.TRANSFORM_FAILED, "the console's Python runner answered something the server cannot read: " + e.getMessage());
        }
    }

    @Override
    public JsonNode measure(Map<String, Object> manifest, Map<String, Object> params, List<String> groupBy, List<String> columns, List<List<Object>> rows) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("manifest", manifest);
        body.put("params", params);
        body.put("group_by", groupBy);
        body.put("columns", columns);
        body.put("rows", rows);
        JsonNode a = read(call("internal/py/measure", body).body());
        if (!a.path("ok").asBoolean(false)) {
            throw failure(a);
        }
        return a;
    }

    private HttpResponse<byte[]> call(String path, Map<String, Object> body) {
        boolean got = false;
        try {
            got = slots.tryAcquire(props.queueWait().toMillis(), TimeUnit.MILLISECONDS);
            if (!got) {
                throw new DrishtiException(ErrorCode.PYTHON_BUSY, "every Python call to the console is busy (drishti.bi.transform.max-concurrent): try again");
            }
            HttpRequest req = HttpRequest.newBuilder(base.resolve(path)).timeout(props.timeout().plusSeconds(5)).header("Content-Type", "application/json")
                    .header("X-Drishti-Runner-Token", token).POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 400 && !resp.headers().firstValue("Content-Type").orElse("").startsWith("application/json")) {
                throw new DrishtiException(ErrorCode.PYTHON_BUSY, "the console's Python runner answered HTTP " + resp.statusCode() + " (" + base.resolve(path) + ")");
            }
            return resp;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new DrishtiException(ErrorCode.PYTHON_LIMIT, "the Python call ran longer than " + props.timeout().toSeconds() + " s (drishti.bi.transform.timeout)");
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.PYTHON_BUSY, "the console's Python runner cannot be reached at " + base + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DrishtiException(ErrorCode.PYTHON_BUSY, "the Python call was interrupted");
        } finally {
            if (got) {
                slots.release();
            }
        }
    }

    private static JsonNode read(byte[] body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new DrishtiException(ErrorCode.PYTHON_BUSY, "the console's Python runner answered something that is not JSON");
        }
    }

    static DrishtiException failure(JsonNode a) {
        String code = a.path("code").asText("DRS-9074");
        ErrorCode ec = java.util.Arrays.stream(ErrorCode.values()).filter(x -> x.code().equals(code)).findFirst().orElse(ErrorCode.PYTHON_FAILED);
        return new DrishtiException(ec, a.path("detail").asText("the Python code failed"));
    }
}
