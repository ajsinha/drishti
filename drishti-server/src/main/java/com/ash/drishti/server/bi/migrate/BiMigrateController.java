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
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The migrations of stored BI items over HTTP, bi-admin only (docs/guides/API_REFERENCE.md "BI migrations"; {@code drishti.py bi migrate --boards}):
 * {@code POST /bi/migrate/boards} converts the stored 2.3 boards into reports with board pages ({@code {"dryRun": true}} lists them and changes
 * nothing); {@code GET /bi/migrate/problems} lists the items a migration left as they were. Stored datasets and models move into the table layer at
 * start ({@link BiMigration#start}); files by {@code drishti.py tables migrate}.
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
