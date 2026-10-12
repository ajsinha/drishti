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
package com.ash.drishti.server.embed;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.engine.command.Suggestion;
import com.ash.drishti.identity.EmbedAppStore;
import com.ash.drishti.server.api.CommandController;
import com.ash.drishti.server.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/embed/search?q=&limit=}: the search of {@code <drishti-search>} in a host page, opened by the scope {@code embed:search} and nothing else. It is the
 * console's own type-ahead (kinds the user may not open are left out; fields masked for the user, and every field {@code drishti.security.redact} names for an embedded
 * caller, are neither shown nor matched), cut to the kinds the host application may show, to {@code drishti.embed.search.max-results} results and to queries of at least
 * {@code min-chars} characters. It reads and writes nothing; the per-user search rate ({@code limits.searches-per-user-per-minute}) is checked before the call reaches it.
 */
@RestController
public class EmbedSearchController {

    private final CommandController command;
    private final EmbedAppStore apps;
    private final EmbedProperties props;

    public EmbedSearchController(CommandController command, EmbedAppStore apps, EmbedProperties props) {
        this.command = command;
        this.apps = apps;
        this.props = props;
    }

    @GetMapping("/api/v1/embed/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) Integer limit, AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String text = q.strip();
        int max = Math.max(1, Math.min(limit == null ? props.search().maxResults() : limit, props.search().maxResults()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", text);
        if (text.length() < props.search().minChars()) {
            out.put("results", List.of());
            out.put("note", "type at least " + props.search().minChars() + " characters");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
        }
        EmbedAppStore.App app = p.embedApp() == null ? null : apps.find(p.embedApp()).orElse(null);
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        for (Suggestion s : command.suggest(text, max * 3, asOf, p)) {
            if (results.size() >= max) {
                break;
            }
            if (app != null && s.kind() != null && !s.kind().isEmpty() && !app.allowsKind(s.kind())) {
                continue;                                                     // the host application shows these kinds only
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("type", s.type());
            r.put("kind", s.kind());
            r.put("id", s.id());
            r.put("mnemonic", s.mnemonic());
            r.put("title", s.title());
            r.put("subtitle", s.subtitle());
            results.add(r);
        }
        out.put("results", results);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }
}
