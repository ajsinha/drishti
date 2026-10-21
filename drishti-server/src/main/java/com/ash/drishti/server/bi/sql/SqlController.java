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
package com.ash.drishti.server.bi.sql;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.DatasetController;
import com.ash.drishti.server.bi.engine.ArrowIpcWriter;
import com.ash.drishti.server.bi.mq.ModelCatalog;
import com.ash.drishti.server.bi.mq.MqException;
import com.ash.drishti.server.bi.mq.MqService;
import com.ash.drishti.server.security.Principal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drishti SQL over HTTP (docs/guides/SQL.md, docs/guides/API_GUIDE.md "Drishti SQL"): {@code POST /bi/sql} answers one read-only statement over a model as the
 * caller ({@code json}, {@code csv} or {@code arrow}; {@code EXPLAIN SELECT ...} answers the plan), {@code POST /bi/sql/validate} checks it without reading
 * data. Free querying needs read on the model (as {@code /bi/mq} does); every statement is audited as {@code bi-sql} with its text.
 */
@RestController
@RequestMapping("/api/v1/bi/sql")
public class SqlController {

    private static final int AUDIT_TEXT = 500;

    private final BiService bi;
    private final ModelCatalog catalog;
    private final SqlService sql;
    private final AuditLog audit;
    private final org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bi.runs.RunSources> runs;

    public SqlController(BiService bi, ModelCatalog catalog, SqlService sql, AuditLog audit,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bi.runs.RunSources> runs) {
        this.runs = runs;
        this.bi = bi;
        this.catalog = catalog;
        this.sql = sql;
        this.audit = audit;
    }

    /** Body {@code {model, sql, format?}}: the answer (or the plan, for {@code EXPLAIN}); a refusal is {@code 422} with the code, {@code fix} and position. */
    @PostMapping
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @org.springframework.web.bind.annotation.RequestHeader(value = "Prefer", required = false) String prefer) {
        bi.requirePower(p, BiAccess.BI);
        enabled();
        String model = text(body, "model");
        String statement = text(body, "sql");
        String format = body.get("format") == null ? "json" : String.valueOf(body.get("format"));
        com.ash.drishti.server.bi.runs.RunSources r = runs == null ? null : runs.getIfAvailable();
        if (r != null && r.async(prefer) && !"csv".equalsIgnoreCase(format) && !statement.strip().toUpperCase(java.util.Locale.ROOT).startsWith("EXPLAIN")) {
            audit.record(p.user(), "bi-sql", model, "run: " + (statement.length() > AUDIT_TEXT ? statement.substring(0, AUDIT_TEXT) + "..." : statement));
            return r.submit(r.sql(p, model, statement, Boolean.TRUE.equals(body.get("interaction")),
                    body.get("supersedes") == null ? null : String.valueOf(body.get("supersedes")), null), prefer, format, p);
        }
        MqService.Bound b = catalog.bind(p, model);
        SqlService.Compiled c;
        try {
            c = sql.compile(b, model, statement);
        } catch (DsqlException e) {
            throw e.toMq();
        }
        audit.record(p.user(), "bi-sql", model, (c.explain() ? "EXPLAIN " : "") + c.shape() + ": " + (statement.length() > AUDIT_TEXT ? statement.substring(0, AUDIT_TEXT) + "..."
                : statement));
        if (c.explain()) {
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(sql.explain(b, c));
        }
        SqlService.Answer a = sql.run(b, c);
        return switch (format.toLowerCase(java.util.Locale.ROOT)) {
            case "json" -> ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(a.body());
            case "csv" -> ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv; charset=utf-8")).header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(csv(a).getBytes(StandardCharsets.UTF_8));
            case "arrow" -> ResponseEntity.ok().contentType(MediaType.parseMediaType(DatasetController.ARROW)).header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Bi-Rows", Integer.toString(a.rows())).body(ArrowIpcWriter.write(a.columns(), a.rows()));
            default -> throw new MqException(MqException.Problem.of("DRS-9150", "format", "format is json, csv or arrow"));
        };
    }

    /** Body {@code {model, sql}}: {@code valid}, the shape, the model queries, the output columns and the plan, or the problems; always {@code 200}. */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        enabled();
        String model = text(body, "model");
        return sql.validate(catalog.bind(p, model), model, text(body, "sql"));
    }

    private void enabled() {
        if (!sql.properties().enabled()) {
            throw new DrishtiException(ErrorCode.BI_OFF, "Drishti SQL is switched off on this server (drishti.bi.sql.enabled)");
        }
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new MqException(MqException.Problem.of("model".equals(key) ? "DRS-9151" : "DRS-9170", key, "model".equals(key)
                    ? "the model the statement reads: name@major, or dataset:<name>" : "the statement: SELECT ... FROM <alias> ..."));
        }
        return String.valueOf(v);
    }

    /** RFC 4180 CSV: a header of the column names, then the rows. */
    @SuppressWarnings("unchecked")
    static String csv(SqlService.Answer a) {
        StringBuilder b = new StringBuilder();
        List<Map<String, Object>> cols = (List<Map<String, Object>>) a.body().get("columns");
        b.append(String.join(",", cols.stream().map(c -> cell(c.get("name"))).toList())).append("\r\n");
        for (List<Object> r : (List<List<Object>>) a.body().get("rows")) {
            b.append(String.join(",", r.stream().map(SqlController::cell).toList())).append("\r\n");
        }
        return b.toString();
    }

    private static String cell(Object v) {
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v);
        return s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
