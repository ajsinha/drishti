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
import java.util.Locale;
import java.util.Map;
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
 * Why VaR moved (VAR_GUIDE.md section 3.11; OVERSIGHT_LENS.md section 4.1, RO1), as the person (the same entitlements as the scope's VaR:
 * a scope is resolved as the caller on each date). Reads only: every endpoint is a GET.
 * <pre>
 *   GET /api/v1/risk/var/explain?scope=&amp;from=&amp;to=&amp;confidence=     the states, the steps with their drivers, the VaR scenario of each state,
 *        the order used, the order-free view by part, the limit-use line, the sentences and the add-up check
 *        (from, to: business dates, yyyy-MM-dd; none: the business date before the newest, and the newest)
 *   GET /api/v1/risk/var/explain/matrix?scope=&amp;from=&amp;to=            the Matrix view: the scope and each child node, the steps across
 *   GET /api/v1/risk/var/explain.xlsx?scope=&amp;from=&amp;to=              the explanation as a workbook of formulas, checked against the engine
 * </pre>
 * {@code method=shapley} is refused in words: the order-free Shapley variant is planned, not built.
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

    static void method(String method) {
        if (method == null || method.isBlank() || method.strip().equalsIgnoreCase("sequence")) {
            return;
        }
        if (method.strip().toLowerCase(Locale.ROOT).equals("shapley")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "method=shapley is planned, not built: the explanation is the configured sequence "
                    + "(drishti.var.explain.order), whose steps add up exactly");
        }
        throw new DrishtiException(ErrorCode.BAD_REQUEST, "method is sequence (shapley is planned)");
    }

    @GetMapping("/explain")
    public Map<String, Object> explain(@RequestParam(required = false) String scope, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Double confidence,
            @RequestParam(required = false) String method, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        method(method);
        return explain.explain(p, scope(scope), date(from, "from"), date(to, "to"), confidence).answer();
    }

    @GetMapping("/explain/matrix")
    public Map<String, Object> matrix(@RequestParam(required = false) String scope, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Double confidence,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return explain.matrix(p, scope(scope), date(from, "from"), date(to, "to"), confidence);
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
