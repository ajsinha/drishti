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
package com.ash.drishti.server.dist;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Statements (2.6.0, docs/guides/BI_GUIDE.md): the BI Studio's page-by-page preview of a statement, and a statement as a file. Both are made for the caller,
 * with their own masks and row rules; a draft's YAML may be previewed by its author. A subscription delivers the same file to each recipient, made as them.
 */
@RestController
public class StatementController {

    private final StatementService statements;
    private final AuditLog audit;
    private final org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bi.runs.RunSources> runs;

    /** With BI runs: {@code Prefer: respond-async} answers as {@code POST /api/v1/bi/runs} does (docs/architecture/BI_DESIGN.md 14.1); without it, as before. */
    @org.springframework.beans.factory.annotation.Autowired
    public StatementController(StatementService statements, AuditLog audit,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bi.runs.RunSources> runs) {
        this.statements = statements;
        this.audit = audit;
        this.runs = runs;
    }

    public StatementController(StatementService statements, AuditLog audit) {
        this(statements, audit, null);
    }

    private com.ash.drishti.server.bi.runs.RunSources async(String prefer) {
        com.ash.drishti.server.bi.runs.RunSources r = runs == null ? null : runs.getIfAvailable();
        return r != null && r.async(prefer) ? r : null;
    }

    /** {@code Prefer: respond-async}: the preview as a run; 200 today's body inside the wait, else 202 {run}. */
    @PostMapping(path = "/api/v1/bi/reports/statement/preview", headers = "Prefer")
    public ResponseEntity<?> previewAsync(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @org.springframework.web.bind.annotation.RequestHeader("Prefer") String prefer) {
        com.ash.drishti.server.bi.runs.RunSources r = async(prefer);
        if (r == null) {
            return ResponseEntity.ok(preview(body, p));
        }
        return r.submit(r.statement(p, body, "preview", "Statement preview " + body.path("report").asText(""), () -> preview(body, p)), prefer, "json", p);
    }

    /**
     * {@code Prefer: respond-async}: the file as a run. Inside the wait it is the file, as without the header (with {@code Drishti-Run}); past it, 202 {run}
     * and the run's result is {@code {format, filename, contentType, pages, bytes}} (the file in base64).
     */
    @PostMapping(path = "/api/v1/bi/reports/statement/file", headers = "Prefer")
    public ResponseEntity<?> fileAsync(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p,
            @org.springframework.web.bind.annotation.RequestHeader("Prefer") String prefer) {
        com.ash.drishti.server.bi.runs.RunSources r = async(prefer);
        if (r == null) {
            return file(body, p);
        }
        ResponseEntity<?> out = r.submit(r.statement(p, body, "file", "Statement " + body.path("report").asText(""), () -> fileAsRun(body, p)), prefer, "json", p);
        if (out.getStatusCode().value() == 200 && out.getBody() instanceof Map<?, ?> m && m.get("bytes") instanceof String b) {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(String.valueOf(m.get("contentType"))))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(String.valueOf(m.get("filename")), StandardCharsets.UTF_8).build().toString())
                    .header("X-Statement-Pages", String.valueOf(m.get("pages"))).header("Drishti-Run", out.getHeaders().getFirst("Drishti-Run"))
                    .body(java.util.Base64.getDecoder().decode(b));
        }
        return out;
    }

    /** The file as a run's result: {@code {format, filename, contentType, pages, bytes}} (the file in base64); also what a replayed statement run makes. */
    public Map<String, Object> fileAsRun(JsonNode body, Principal p) {
        ResponseEntity<byte[]> f = file(body, p);
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("format", body.path("format").asText("pdf"));
        m.put("filename", f.getHeaders().getContentDisposition().getFilename());
        m.put("contentType", String.valueOf(f.getHeaders().getContentType()));
        m.put("pages", Integer.parseInt(f.getHeaders().getFirst("X-Statement-Pages")));
        m.put("bytes", java.util.Base64.getEncoder().encodeToString(f.getBody()));
        return m;
    }

    /**
     * Body {@code {report?: <id>, yaml?: <draft report>, page?, state?, date?}}: the statement's pages as positioned text, rules and fills in points (what the PDF
     * draws), its page count, paper, period and the tokens its header and footer used.
     */
    @PostMapping("/api/v1/bi/reports/statement/preview")
    public Map<String, Object> preview(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String yaml = body.path("yaml").isTextual() ? body.get("yaml").asText() : null;
        String id = body.path("report").asText("");
        if (yaml == null && id.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "name the report (report: its id) or send its YAML (yaml)");
        }
        return statements.preview(p, yaml == null ? id : null, yaml, body.path("page").asText(""), body.path("state").isObject() ? body.get("state") : null,
                body.path("date").asText(""));
    }

    /**
     * Body as {@link #preview} plus {@code format}: {@code pdf} (the default), {@code xlsx}, or {@code jsonl} (the statement's rows, one a line, as the
     * export's other formats name the columns); the file, made for the caller.
     */
    @PostMapping("/api/v1/bi/reports/statement/file")
    public ResponseEntity<byte[]> file(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String format = body.path("format").asText("pdf").trim().toLowerCase(java.util.Locale.ROOT);
        if (!format.equals("pdf") && !format.equals("xlsx") && !format.equals("jsonl")) {   // one answer for every other format, docx as png (QA2-11)
            throw new DrishtiException(ErrorCode.STATEMENT_INVALID, "a statement is made as pdf, xlsx or jsonl, not '" + format + "'");
        }
        ContentExporter.Format f = ContentExporter.Format.of(format);
        String yaml = body.path("yaml").isTextual() ? body.get("yaml").asText() : null;
        String id = body.path("report").asText("");
        if (yaml == null && id.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "name the report (report: its id) or send its YAML (yaml)");
        }
        StatementService.Run run = statements.run(p, yaml == null ? id : null, yaml, body.path("page").asText(""), body.path("state").isObject() ? body.get("state") : null,
                body.path("date").asText(""));
        byte[] bytes = switch (f) {
            case PDF -> statements.pdf(run);
            case JSONL -> JsonlWriter.write(run.doc());                    // the statement's tables, a row a line
            default -> statements.xlsx(run);
        };
        audit.record(p.user(), "statement", "report/" + (yaml == null ? id : "draft"), f.extension() + " · " + StatementService.pages(run.pages().pages().size()) + " · " + bytes.length + " bytes");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(StatementService.filename(run, p, f), StandardCharsets.UTF_8).build().toString())
                .header("X-Statement-Pages", String.valueOf(run.pages().pages().size()))
                // each table that stops at max-rows, in words, as the preview's cut (URL-encoded UTF-8): a script can tell a cut statement (QA2-204)
                .header("X-Statement-Cut", run.cut().stream().map(c -> java.net.URLEncoder.encode(c, StandardCharsets.UTF_8)).toArray(String[]::new))
                // no data is held for the dates the statement reads, in words, as the preview's hint (URL-encoded UTF-8; QA3B-09)
                .header("X-Statement-Hint", run.hint() == null ? new String[0] : new String[] {java.net.URLEncoder.encode(run.hint(), StandardCharsets.UTF_8)})
                .body(bytes);
    }
}
