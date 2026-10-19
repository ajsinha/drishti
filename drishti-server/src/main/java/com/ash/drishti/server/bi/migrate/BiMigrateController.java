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
package com.ash.drishti.server.bi.migrate;

import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The migrations of stored BI items over HTTP, bi-admin only (docs/guides/API_GUIDE.md "BI migrations"; {@code drishti.py bi migrate}):
 * {@code POST /bi/migrate/expressions} converts the stored items in the old expression syntax ({@code {"dryRun": true}} lists what would change and changes
 * nothing); {@code GET /bi/migrate/problems} lists the items a migration left as they were.
 */
@RestController
@RequestMapping("/api/v1/bi/migrate")
public class BiMigrateController {

    private final BiService bi;
    private final BiMigration migration;

    public BiMigrateController(BiService bi, BiMigration migration) {
        this.bi = bi;
        this.migration = migration;
    }

    /** Converts the stored 2.3 boards into reports with board pages (R7, 2.4.0); {@code {"dryRun": true}} lists them and changes nothing. */
    @PostMapping("/boards")
    public Map<String, Object> boards(@RequestBody(required = false) Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.ADMIN);
        return migration.boards(p.user(), dryRun(body));
    }

    @PostMapping("/expressions")
    public Map<String, Object> expressions(@RequestBody(required = false) Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.ADMIN);
        return migration.expressions(p.user(), dryRun(body));
    }

    /** The stored {@code rachana: 1} datasets to datasets and models, their reports rebound ({@code {"dryRun": true}} plans and changes nothing). */
    @PostMapping("/models")
    public Map<String, Object> models(@RequestBody(required = false) Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.ADMIN);
        return migration.models(p.user(), dryRun(body));
    }

    /**
     * The converter over files (what {@code drishti.py bi migrate --models DIR} sends): body {@code {files: [{path, yaml}], exposure?}} with paths under the BI
     * folder ({@code datasets/x.dataset.yaml}); the answer says what to write, what to remove, every item and the report. Reads and stores nothing.
     */
    @PostMapping("/models/convert")
    public Map<String, Object> convert(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.BI);
        List<ModelConverter.Source> sources = new ArrayList<>();
        Object files = body == null ? null : body.get("files");
        if (files instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> f && f.get("path") != null && f.get("yaml") != null) {
                    String path = String.valueOf(f.get("path"));
                    String kind = path.replaceAll("^.*\\.([a-z]+)\\.yaml$", "$1");
                    sources.add(new ModelConverter.Source(path, kind, String.valueOf(f.get("yaml"))));
                }
            }
        }
        ModelConverter.Exposure exposure = "closed".equalsIgnoreCase(String.valueOf(body == null ? "" : body.get("exposure"))) ? ModelConverter.Exposure.CLOSED
                : ModelConverter.Exposure.NOTE;
        ModelConverter.Result r = ModelConverter.convert(sources, exposure);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("writes", r.writes());
        m.put("removes", r.removes());
        m.put("items", r.items().stream().map(i -> i.toMap(false)).toList());
        m.put("converted", r.count("converted"));
        m.put("problems", r.count("problem"));
        m.put("report", r.report());
        return m;
    }

    @GetMapping("/problems")
    public Map<String, Object> problems(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        bi.requirePower(p, BiAccess.ADMIN);
        return migration.problemsView();
    }

    static boolean dryRun(Map<String, Object> body) {
        Object v = body == null ? null : body.get("dryRun");
        return v != null && Boolean.parseBoolean(String.valueOf(v));
    }
}
