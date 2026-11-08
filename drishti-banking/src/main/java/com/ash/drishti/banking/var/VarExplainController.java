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
package com.ash.drishti.banking.var;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.security.Principal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Why VaR moved as a workbook (VAR_GUIDE.md section 3.11; docs/architecture/LENSES.md section 4.1, RO1), as the person (the same entitlements as the scope's VaR:
 * a scope is resolved as the caller on each date). Reads only: every endpoint is a GET.
 * <pre>
 *   GET /api/v1/risk/var/explain.xlsx?scope=&amp;from=&amp;to=              the explanation as a workbook of formulas, checked against the engine
 *        (from, to: business dates, yyyy-MM-dd; none: the business date before the newest, and the newest)
 * </pre>
 * The explanation itself is the one Explain's ({@code GET /api/v1/explain?kind=var}, {@link VarNumberExplainer}): its Why it moved tab,
 * the steps, drivers and the Matrix and Sentences views. This is its Workbook.
 */
@RestController
@RequestMapping("/api/v1/risk/var")
public class VarExplainController {

    private final VarExplain explain;

    VarExplainController(VarExplain explain) {
        this.explain = explain;
    }

    static LocalDate date(String text, String name) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, name + " is a business date, yyyy-MM-dd (not " + text + ")");
        }
    }

    static String scope(String scope) {
        if (scope == null || scope.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give a scope: scope=BANK, DESK:RATES, NODE:<id>, PORT:<id> ...");
        }
        return scope.strip();
    }

    @GetMapping("/explain.xlsx")
    public ResponseEntity<StreamingResponseBody> workbook(@RequestParam(required = false) String scope, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Double confidence,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        VarExplain.Result r = explain.explain(p, scope(scope), date(from, "from"), date(to, "to"), confidence);
        VarExplainBook book = new VarExplainBook(r, VarExplainBook.builtIn(), true, p == null ? "" : p.user(),
                java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).withNano(0).toString());
        String name = "var-explain_" + r.canonical().replaceAll("[^A-Za-z0-9_.~-]+", "-").replaceAll("^-+|-+$", "") + "_" + r.from() + "_" + r.to() + ".xlsx";
        StreamingResponseBody body = book::write;
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
