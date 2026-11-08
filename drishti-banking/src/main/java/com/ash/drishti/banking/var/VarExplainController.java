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
import com.ash.drishti.server.explain.ExplainRequest;
import com.ash.drishti.server.explain.NumberExplainService;
import com.ash.drishti.server.security.Principal;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
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
 * Why VaR moved as a workbook (VAR_GUIDE.md section 3.11; docs/lenses/README.md section 4.1, RO1), as the person (the same entitlements as the scope's VaR:
 * a scope is resolved as the caller on each date). Reads only: every endpoint is a GET.
 * <pre>
 *   GET /api/v1/risk/var/explain.xlsx?scope=&amp;from=&amp;to=              the explanation as a workbook of formulas, checked against the engine
 *        (from, to: business dates, yyyy-MM-dd; none: the business date before the newest, and the newest)
 *   GET /api/v1/risk/var/explain?scope=&amp;from=&amp;to=&amp;confidence=          DEPRECATED, answered for this release: the one Explain's
 *   GET /api/v1/risk/var/explain/matrix?scope=&amp;from=&amp;to=&amp;confidence=   why.answer (and its matrix), as 2.9.11.d answered them
 * </pre>
 * The explanation itself is the one Explain's ({@code GET /api/v1/explain?kind=var}, {@link VarNumberExplainer}): its Why it moved tab,
 * the steps, drivers and the Matrix and Sentences views. This is its Workbook. The old paths ({@code /api/v1/var/explain} and
 * {@code /explain/matrix}, reached through the {@code /api/v1/var} alias, and {@code /api/v1/pivot/mr/explain} in {@link MrCubeController})
 * answer for one release with {@code Deprecation: true} and a {@code Link} to the one Explain.
 */
@RestController
@RequestMapping("/api/v1/risk/var")
public class VarExplainController {

    /** The one Explain's address, the successor of the old explain endpoints. */
    static final String SUCCESSOR = "/api/v1/explain?kind=var&part=why";

    private final VarExplain explain;
    private final ObjectProvider<NumberExplainService> explainer;

    VarExplainController(VarExplain explain, ObjectProvider<NumberExplainService> explainer) {
        this.explain = explain;
        this.explainer = explainer;
    }

    /** DEPRECATED, answered for this release: the one Explain's {@code why.answer}, the engine's own answer as 2.9.11.d gave it. */
    @GetMapping("/explain")
    public Map<String, Object> explainAlias(@RequestParam(required = false) String scope, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Double confidence,
            @RequestParam(required = false) String method, @RequestAttribute(Principal.ATTRIBUTE) Principal p, HttpServletResponse res) {
        method(method);
        return why(explainer.getObject(), p, scope(scope), from, to, confidence, false, res);
    }

    /** DEPRECATED, answered for this release: the one Explain's Matrix view ({@code why.answer.matrix}), as 2.9.11.d gave it. */
    @GetMapping("/explain/matrix")
    public Map<String, Object> matrixAlias(@RequestParam(required = false) String scope, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) Double confidence,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p, HttpServletResponse res) {
        return why(explainer.getObject(), p, scope(scope), from, to, confidence, true, res);
    }

    /**
     * The engine's answer of why VaR moved, read from the one Explain ({@code kind=var, part=why}): {@code why.answer}, or its
     * {@code matrix} for the Matrix view; the answer carries {@code Deprecation: true} and a {@code Link} to the one Explain.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> why(NumberExplainService service, Principal p, String scope, String from, String to, Double confidence,
            boolean matrix, HttpServletResponse res) {
        date(from, "from");
        date(to, "to");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("scope", scope);
        if (from != null && !from.isBlank()) {
            args.put("from", from.strip());
        }
        if (to != null && !to.isBlank()) {
            args.put("to", to.strip());
        }
        if (confidence != null) {
            args.put("confidence", String.valueOf(confidence));
        }
        if (matrix) {
            args.put("view", "matrix");
        }
        Map<String, Object> x = service.explain(p, new ExplainRequest("var", "var", "why", args));
        Map<String, Object> why = (Map<String, Object>) x.get("why");
        Map<String, Object> answer = why == null ? null : (Map<String, Object>) why.get("answer");
        if (answer == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "why VaR moved is not available for " + scope + ": "
                    + (why == null ? "the explainer gave none" : String.valueOf(why.getOrDefault("note", "no answer"))));
        }
        res.setHeader("Deprecation", "true");
        res.setHeader("Link", "<" + SUCCESSOR + (matrix ? "&view=matrix" : "") + ">; rel=\"successor-version\"");
        return matrix ? (Map<String, Object>) answer.get("matrix") : answer;
    }

    /** The old {@code method}: sequence, the only one built (shapley is planned), refused in words otherwise. */
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
