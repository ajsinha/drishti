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
package com.ash.drishti.server.bi.expr.web;

import com.ash.drishti.rachana.el.check.Scope;
import com.ash.drishti.rachana.el.check.Signatures;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.mq.ModelCatalog;
import com.ash.drishti.server.bi.mq.MqException;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rachana, BI's expression language, over HTTP (docs/guides/API_REFERENCE.md "Expressions", docs/guides/RACHANA_REFERENCE.md): the function catalogue the expression
 * editor completes from ({@code GET /bi/expr/functions}), checking one expression in its scope against a model as the caller sees it
 * ({@code POST /bi/expr/validate}: problems with code, fix and position, the type, what it uses). Nothing here reads data or stores anything;
 * a text in an older syntax is migrated by {@code drishti.py tables migrate}.
 */
@RestController
@RequestMapping("/api/v1/bi/expr")
public class ExprController {

    private final BiService bi;
    private final ModelCatalog catalog;

    public ExprController(BiService bi, ModelCatalog catalog) {
        this.bi = bi;
        this.catalog = catalog;
    }

    /** The catalogue: every function with its signature, parameters, scopes, status, description, examples and edge cases; {@code ?scope=} filters. */
    @GetMapping("/functions")
    public Map<String, Object> functions(@RequestParam(required = false) String scope, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        Scope s = Scope.of(scope);
        Signatures cat = Signatures.get();                                       // Rachana's one catalogue (docs/architecture/ARCHITECTURE.md §28 2.5)
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("language", "Rachana");
        m.put("version", 3);
        m.put("families", cat.families());
        m.put("functions", cat.all().stream().filter(f -> s == null || f.allowedIn(s)).map(Signatures.Signature::toMap).toList());
        m.put("keywords", List.of("where", "by", "desc", "over", "ignore", "keep", "via", "to", "at", "unit", "period", "hierarchy"));
        m.put("operators", List.of("+", "-", "*", "/", "%", "==", "!=", "<", "<=", ">", ">=", "in", "not in", "&&", "||", "!", "c ? a : b"));
        m.put("literals", List.of("12", "1.5", "1e6", "'text'", "true", "false", "null", "[a, b]", "date('2026-10-09')", "timestamp('2026-10-09T09:30')"));
        m.put("scopes", List.of(Map.of("name", "row", "where", "derive and calculated columns, a dataset's where, MQ and DSQL row filters"),
                Map.of("name", "rule", "where", "security.rows[].where (may use user.*)"),
                Map.of("name", "aggregate", "where", "measures, MQ define, having, report local measures"),
                Map.of("name", "context", "where", "the first argument of calc, share_of, parent_of, as_of (wave B)")));
        return m;
    }

    /**
     * Body {@code {expr, scope, model?, alias?}}: {@code scope} is {@code row}, {@code rule} or {@code aggregate} (default); {@code model} a model reference
     * ({@code trading-risk@1}) or {@code dataset:<name>}; {@code alias} the dataset whose row is in context (row and rule scope). Always {@code 200}: the problems
     * say what is wrong.
     */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        String expr = text(body, "expr");
        String scopeWord = text(body, "scope");
        Scope scope = scopeWord == null ? Scope.AGGREGATE : Scope.of(scopeWord);
        if (scope == null) {
            throw new MqException(MqException.Problem.of("DRS-9127", "scope", "scope is row, rule or aggregate"));
        }
        if (expr == null) {
            throw new MqException(MqException.Problem.of("DRS-9127", "expr", "the expression to check, as text"));
        }
        String draft = text(body, "yaml");
        if (draft != null) {
            return catalog.validateExpressionDraft(p, expr, scope, draft, text(body, "alias"));       // the designer's unsaved model or dataset
        }
        return catalog.validateExpression(p, expr, scope, text(body, "model"), text(body, "alias"));
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }
}
