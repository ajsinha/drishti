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
package com.ash.drishti.server.bi.board;

import com.ash.drishti.server.security.Principal;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Business intelligence, phase 5 (docs/architecture/RUPAKA.md section 12): boards. The viewer asks for a board once; each tile's data comes from the report
 * endpoints ({@code /api/v1/bi/reports/{id}/query}, {@code /live}), as the person looking.
 */
@RestController
@RequestMapping("/api/v1/bi/boards")
public class BiBoardController {

    private final BiBoardService boards;

    public BiBoardController(BiBoardService boards) {
        this.boards = boards;
    }

    /** Body {@code {yaml}}: checks board YAML without storing it (authors): the structure and what each tile names. */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return boards.validate(p, String.valueOf(body.getOrDefault("yaml", "")));
    }

    /** Body {@code {board}}: the board model written as YAML, the way the designer's YAML tab shows it. */
    @PostMapping("/format")
    public Map<String, Object> format(@RequestBody com.fasterxml.jackson.databind.JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return boards.format(p, body.path("board"));
    }

    /** The reports a tile can name for this author: the published ones and their own drafts (each with {@code draft}). */
    @GetMapping("/reports")
    public java.util.List<Map<String, Object>> reports(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return boards.reportChoices(p);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(boards.get(p, id));
    }
}
