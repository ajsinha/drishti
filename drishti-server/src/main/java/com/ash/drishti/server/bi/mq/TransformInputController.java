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
package com.ash.drishti.server.bi.mq;

import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.DatasetController;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.transform.TransformProperties;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One input of a Python Transform, as the caller (docs/guides/PYTHON_BI.md section 17, BI_PARITY_PLAN.md P1 and P5): what the Transform editor previews and
 * profiles, and what its step-by-step run starts from. {@code POST /api/v1/bi/python/input} with {@code {input, limit?, asOf?}}, where {@code input} is either
 * form a Python dataset's {@code source.inputs} takes (a model query, or a pack kind read through a connector); answers the rows as Arrow
 * ({@code ?format=arrow}, with {@code X-Bi-Rows}, {@code X-Bi-Masked}, {@code X-Bi-Model}) or JSON. The rows are cut to {@code limit} (default 1,000) and never
 * more than {@code drishti.bi.transform.max-input-rows}; they are the caller's: the kind's entitlements, their row rules and masks apply, as in a run.
 */
@RestController
@RequestMapping("/api/v1/bi/python")
public class TransformInputController {

    private static final int DEFAULT_LIMIT = 1_000;

    private final ModelCatalog catalog;
    private final MqService mq;
    private final BiService bi;
    private final TransformProperties props;

    public TransformInputController(ModelCatalog catalog, MqService mq, BiService bi, TransformProperties props) {
        this.catalog = catalog;
        this.mq = mq;
        this.bi = bi;
        this.props = props;
    }

    @PostMapping("/input")
    public ResponseEntity<?> input(@RequestBody JsonNode body, @RequestParam(required = false) String format, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        JsonNode in = body == null ? null : body.path("input");
        if (in == null || !in.isObject()) {
            throw new MqException(MqException.Problem.of("DRS-9108", "input", "send {input: a model query or { kind, connector?, fields?, where? }, limit?, asOf?}"));
        }
        int limit = Math.max(1, Math.min(body.path("limit").canConvertToInt() ? body.path("limit").asInt() : DEFAULT_LIMIT, props.maxInputRows()));
        ModelQuery.Time time = ModelQuery.Time.DEFAULT;
        if (body.path("asOf").isTextual() && !body.path("asOf").asText().isBlank()) {
            try {
                time = new ModelQuery.Time(java.time.LocalDate.parse(body.path("asOf").asText()), null, null, null, false);
            } catch (java.time.format.DateTimeParseException e) {
                throw new MqException(MqException.Problem.of("DRS-9108", "asOf", "asOf is a date, such as 2026-10-08"));
            }
        }
        TransformInputs.Asked a = TransformInputs.ask(catalog, p, in, time, limit);
        ModelQuery q = a.query();
        if (q.limit() == null || q.limit() > limit) {
            q = q.with(q.fields(), q.where(), limit);
        }
        MqService.Ran ran = mq.run(a.bound(), q, false, false);
        QueryService.Result r = TransformInputs.bare(ran.shown(), a.alias());
        List<String> masked = r.meta() == null ? List.of() : r.meta().masked();
        // the business date the rows are of: the last date they came from (a connector's newest day), else the date the query was asked at
        String asOf = r.meta() != null && !r.meta().dates().isEmpty() ? String.valueOf(r.meta().dates().get(r.meta().dates().size() - 1)) : String.valueOf(ran.compiled().asOf());
        if ("arrow".equalsIgnoreCase(format)) {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(DatasetController.ARROW))
                    .header("X-Bi-Rows", Integer.toString(r.rows()))
                    .header("X-Bi-Masked", String.join(",", masked))
                    .header("X-Bi-Model", a.ref())
                    .header("X-Bi-As-Of", asOf)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(ArrowIpcWriter.write(r.columns(), r.rows()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> cols = new ArrayList<>();
        r.columns().forEach(c -> cols.add(Map.of("name", c.name(), "type", c.kind().name().toLowerCase(java.util.Locale.ROOT))));
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < r.rows(); i++) {
            List<Object> row = new ArrayList<>();
            for (ArrowIpcWriter.Column c : r.columns()) {
                Object v = c.values()[i];
                row.add(v instanceof java.time.temporal.Temporal ? v.toString() : v);
            }
            rows.add(row);
        }
        out.put("model", a.ref());
        out.put("kind", a.kind());
        out.put("columns", cols);
        out.put("rows", rows);
        out.put("rowCount", r.rows());
        out.put("masked", masked);
        out.put("asOf", asOf);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(out);
    }
}
