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
package com.ash.drishti.server.collab;

import com.ash.drishti.server.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The people picker: users who share a pack with the caller, and mentionable roles. Rate limited (DRS-7003). */
@RestController
@RequestMapping("/api/v1/directory")
public class DirectoryController {

    private final DirectoryService directory;
    private final ShareService shares;

    public DirectoryController(DirectoryService directory, ShareService shares) {
        this.directory = directory;
        this.shares = shares;
    }

    @GetMapping
    public List<DirectoryService.Entry> search(@RequestParam(name = "q") String q, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String kind, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        shares.requireCollaborate(p);
        return directory.search(p, q, limit, kind);
    }
}
