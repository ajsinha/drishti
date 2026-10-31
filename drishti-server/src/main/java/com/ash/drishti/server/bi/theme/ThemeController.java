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
package com.ash.drishti.server.bi.theme;

import com.ash.drishti.server.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Theme items (report schema v2, docs/guides/BI_GUIDE.md › Reports: the YAML, the visuals and how they behave, section 5.2): the list a designer picks from, one theme, and a check of theme YAML. Themes are created,
 * saved, reviewed and published as BI items of kind {@code theme} through the workspace API, like every other item.
 */
@RestController
@RequestMapping("/api/v1/bi/themes")
public class ThemeController {

    private final ThemeService themes;

    public ThemeController(ThemeService themes) {
        this.themes = themes;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return themes.list(p);
    }

    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return themes.validate(p, String.valueOf(body.getOrDefault("yaml", "")));
    }

    @GetMapping("/{ref}")
    public Map<String, Object> get(@PathVariable String ref, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return themes.get(p, ref);
    }
}
