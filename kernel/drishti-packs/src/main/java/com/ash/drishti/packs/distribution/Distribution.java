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
package com.ash.drishti.packs.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One distribution as read from its file (docs/architecture/DISTRIBUTIONS.md, KERNEL_AND_MODULES.md section 6): who it is, the brand and
 * theme, the modules it names, and what those modules come to today (packs, commands, words, engine features, domain modules), until
 * every module is a {@code .dmod} of its own.
 *
 * @param id       {@code drishti.finance}
 * @param title    the product name ({@code Drishti Finance}): the edition's title, the Help title's start
 * @param file     the file it was read from
 * @param all      true for a distribution of every module ({@code modules: all}): no visibility filter at all
 * @param modules  the module ids it names, in order
 * @param domains  each module's domain module today ({@code market-data -> banking}, {@code economy -> economy} ...)
 * @param packs    the kernel's packs and its modules', in order
 * @param commands every command of those packs (their mnemonics and pack codes)
 * @param wordsOff the command words of the modules it leaves out
 * @param featuresOff the engine features of the modules it leaves out ({@code var}, {@code exposure} ...)
 * @param lenses   the lenses it offers
 * @param brand    the brand ({@code product}, {@code tagline}, {@code owner}, {@code copyright}, {@code notice} ...)
 * @param spec     the file as a tree
 * @param editionSpec the instance filter it comes to, in the edition file's shape (null for {@code all})
 */
public record Distribution(String id, String title, Path file, boolean all, List<String> modules, Map<String, String> domains, List<String> packs,
        Set<String> commands, Set<String> wordsOff, Set<String> featuresOff, List<String> lenses, Map<String, String> brand, JsonNode spec,
        ObjectNode editionSpec) {

    /** The short name: {@code finance} for {@code drishti.finance}. */
    public String shortName() {
        return id.startsWith("drishti.") ? id.substring("drishti.".length()) : id;
    }

    /** What {@code /public/about} and Health say of it. */
    public Map<String, Object> about() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", shortName());
        m.put("title", title);
        m.put("all", all);
        m.put("modules", all ? List.of("all") : modules);
        m.put("file", file == null ? "" : file.toString());
        m.put("theme", spec.path("theme").path("tokens").asText(""));
        m.put("density", spec.path("theme").path("density").asText(""));
        m.put("mode", spec.path("theme").path("mode").asText(""));
        return m;
    }
}
