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
 * Statements (2.6.0, docs/guides/STATEMENTS.md): the BI Studio's page-by-page preview of a statement, and a statement as a file. Both are made for the caller,
 * with their own masks and row rules; a draft's YAML may be previewed by its author. A subscription delivers the same file to each recipient, made as them.
 */
@RestController
public class StatementController {

    private final StatementService statements;
    private final AuditLog audit;

    public StatementController(StatementService statements, AuditLog audit) {
        this.statements = statements;
        this.audit = audit;
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

    /** Body as {@link #preview} plus {@code format}: {@code pdf} (the default) or {@code xlsx}; the file, made for the caller. */
    @PostMapping("/api/v1/bi/reports/statement/file")
    public ResponseEntity<byte[]> file(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String format = body.path("format").asText("pdf");
        ContentExporter.Format f = ContentExporter.Format.of(format);
        if (f != ContentExporter.Format.PDF && f != ContentExporter.Format.XLSX) {
            throw new DrishtiException(ErrorCode.STATEMENT_INVALID, "a statement is made as pdf or xlsx");
        }
        String yaml = body.path("yaml").isTextual() ? body.get("yaml").asText() : null;
        String id = body.path("report").asText("");
        if (yaml == null && id.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "name the report (report: its id) or send its YAML (yaml)");
        }
        StatementService.Run run = statements.run(p, yaml == null ? id : null, yaml, body.path("page").asText(""), body.path("state").isObject() ? body.get("state") : null,
                body.path("date").asText(""));
        byte[] bytes = f == ContentExporter.Format.PDF ? statements.pdf(run) : statements.xlsx(run);
        audit.record(p.user(), "statement", "report/" + (yaml == null ? id : "draft"), f.extension() + " · " + run.pages().pages().size() + " pages · " + bytes.length + " bytes");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(StatementService.filename(run, p, f), StandardCharsets.UTF_8).build().toString())
                .header("X-Statement-Pages", String.valueOf(run.pages().pages().size()))
                .body(bytes);
    }
}
