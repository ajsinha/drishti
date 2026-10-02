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
package com.ash.drishti.server.calc;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.search.ColumnRead;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Calc's server side (PYTHON_CALC.md). The Python runs in the user's browser; here are only: whether the caller may use
 * Calc ({@code GET /calc/settings}), the caller's saved snippets ({@code /me/calc-snippets}), and whole columns of a
 * business date ({@code GET /search/columns/{kind}}), checked and redacted as a search is. Every other read Calc makes
 * is an ordinary read (a view, a raw document, a search, a history series), with the caller's own roles.
 */
@RestController
@RequestMapping("/api/v1")
public class CalcController {

    static final String NS = "calc-snippets";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._()-]{0,79}");

    private final CalcProperties props;
    private final Entitlements entitlements;
    private final PreferenceStore store;
    private final ColumnRead columns;
    private final ObjectMapper json = new ObjectMapper();

    public CalcController(CalcProperties props, Entitlements entitlements, PreferenceStore store, ColumnRead columns) {
        this.props = props;
        this.entitlements = entitlements;
        this.store = store;
        this.columns = columns;
    }

    /** Whether the caller may use Calc, and its limits: the console asks before offering the panel. */
    @GetMapping("/calc/settings")
    public Map<String, Object> settings(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.enabled());
        m.put("allowed", props.enabled() && entitlements.mayCalc(p));
        m.put("maxColumnRows", props.maxColumnRows());
        m.put("maxSnippetChars", props.maxSnippetChars());
        return m;
    }

    /** The caller's saved snippets, by name. */
    @GetMapping("/me/calc-snippets")
    public List<JsonNode> snippets(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        require(p);
        List<JsonNode> out = new ArrayList<>();
        for (String name : store.keys(p.user(), NS)) {
            store.get(p.user(), NS, name).ifPresent(out::add);
        }
        return out;
    }

    /** Saves a snippet: {@code {"code": "...", "description": "...", "kind": "trade"}}; the kind is where it was written. */
    @PutMapping("/me/calc-snippets/{name}")
    public JsonNode save(@PathVariable String name, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        require(p);
        String n = name.strip();
        if (!NAME.matcher(n).matches()) {
            throw bad("a snippet's name is 1-80 letters, digits, spaces and . _ ( ) -, starting with a letter or digit");
        }
        String code = body.path("code").asText("");
        if (code.isBlank()) {
            throw bad("a snippet has code");
        }
        if (code.length() > props.maxSnippetChars()) {
            throw bad("a snippet's code is at most " + props.maxSnippetChars() + " characters");
        }
        String kind = body.path("kind").asText("").strip();
        ObjectNode s = json.createObjectNode().put("name", n).put("description", clip(body.path("description").asText(""), 300))
                .put("kind", kind.length() > 80 ? "" : kind).put("code", code).put("updatedAt", Instant.now().toString());
        store.put(p.user(), NS, n, s);
        return s;
    }

    @DeleteMapping("/me/calc-snippets/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        require(p);
        if (!store.delete(p.user(), NS, name.strip())) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no snippet '" + name + "'");
        }
    }

    /**
     * Whole columns of a kind on the request's business date ({@code GET /search/columns/TRD?paths=mtm,book,risk.dv01}):
     * every entity, as the source that keeps the fields as columns holds them. Without {@code paths}: the fields kept as
     * columns. Needs Calc and the kind; fields the caller's role may not see come back masked, as in a search.
     */
    @GetMapping("/search/columns/{kind}")
    public Map<String, Object> columns(@PathVariable String kind, @RequestParam(required = false) String paths,
            @RequestParam(required = false) Integer limit, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        require(p);
        String k = columns.kindOf(kind);
        entitlements.requireOpen(p, k);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", k);
        if (paths == null || paths.isBlank()) {
            out.put("available", columns.available(k).stream().sorted().toList());
            return out;
        }
        int max = limit == null || limit <= 0 ? props.maxColumnRows() : Math.min(limit, props.maxColumnRows());
        ColumnRead.Columns c = columns.read(k, List.of(paths.split(",")), asOf, data -> entitlements.redact(p, data), max);
        out.put("businessDate", c.businessDate());
        out.put("paths", c.paths());
        out.put("ids", c.ids());
        out.put("values", c.values());
        out.put("rows", c.ids().size());
        out.put("total", c.total());
        out.put("truncated", c.truncated());
        out.put("masked", c.masked());
        out.put("incomplete", c.incomplete());              // null, or why some entities of the day are missing
        return out;
    }

    private void require(Principal p) {
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "Calc is switched off on this server (drishti.calc.enabled)");
        }
        entitlements.requireCalc(p);
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static DrishtiException bad(String message) {
        return new DrishtiException(ErrorCode.BAD_REQUEST, message);
    }
}
