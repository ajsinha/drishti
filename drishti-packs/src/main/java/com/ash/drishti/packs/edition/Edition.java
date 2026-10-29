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
package com.ash.drishti.packs.edition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The edition a server runs (docs/architecture/EDITIONS.md): a named bundle of settings, {@code config/editions/<name>.yaml}, that turns
 * Drishti into a focused product. {@link #NONE} (no {@code drishti.edition}) is the full Drishti: every test here answers "allowed", so
 * a server without an edition behaves exactly as before editions existed.
 *
 * <p>Read through the environment ({@link Editions#of}), so the site's configuration wins over the file key by key: the file's keys are
 * a property layer ({@code drishti.edition-spec.*}) below application.local.yaml, environment variables and arguments.
 *
 * @param name       the edition's name ({@code risk}); blank for none
 * @param title      the edition's product title ({@code Drishti Risk})
 * @param packs      the packs it enables (empty: the default list)
 * @param allowOnly  true when Admin → Packs, Deploy and the registry may not load any other pack
 * @param commands   the commands (mnemonics, upper case) people may type and see; empty: all
 * @param words      the reserved and console words ({@code BI}, {@code RUPAKA}, {@code FORM}, {@code FUNC}, {@code WHAT}, {@code DATAMAP},
 *                   {@code VAR}, {@code EXP}) switched on or off; a word not named is on
 * @param features   the feature presets ({@code bi: on}, {@code calc: off}); read by each side's own mapping
 * @param adminPages the Admin pages shown (ids); empty: all
 * @param datasets   the BI datasets offered (names, or prefixes ending in {@code *}); empty: all
 * @param models     the BI models offered (names); empty: those whose datasets are all offered
 * @param branding   the product name and tagline the edition sets ({@code product}, {@code tagline}); empty: the site's
 */
public record Edition(String name, String title, List<String> packs, boolean allowOnly, Set<String> commands, Map<String, Boolean> words,
        Map<String, String> features, List<String> adminPages, List<String> datasets, List<String> models, Map<String, String> branding) {

    /** The reserved words and console words an edition may switch (EDITIONS.md section 3). */
    public static final List<String> WORDS = List.of("BI", "RUPAKA", "FORM", "FUNC", "WHAT", "DATAMAP", "VAR", "EXP");

    /** The full Drishti: no edition. */
    public static final Edition NONE = new Edition("", "", List.of(), false, Set.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(), Map.of());

    public Edition {
        name = name == null ? "" : name.strip();
        title = title == null ? "" : title.strip();
        packs = packs == null ? List.of() : packs.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        commands = commands == null ? Set.of() : commands.stream().map(c -> c.strip().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        Map<String, Boolean> w = new LinkedHashMap<>();
        if (words != null) {
            words.forEach((k, v) -> w.put(k.strip().toUpperCase(Locale.ROOT), v));
        }
        words = Map.copyOf(w);
        features = features == null ? Map.of() : Map.copyOf(features);
        adminPages = adminPages == null ? List.of() : List.copyOf(adminPages);
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
        models = models == null ? List.of() : List.copyOf(models);
        branding = branding == null ? Map.of() : Map.copyOf(branding);
    }

    /** True when the server runs an edition. */
    public boolean active() {
        return !name.isEmpty();
    }

    /** False for a pack the edition refuses: only with {@code allow-only} and a pack outside its list. */
    public boolean packAllowed(String pack) {
        return !active() || !allowOnly || packs.isEmpty() || packs.contains(pack);
    }

    /** Refuses a pack the edition does not allow ({@code 403 DRS-1017}): Admin → Packs, Deploy archive and the registry. */
    public void requirePack(String pack) {
        if (!packAllowed(pack)) {
            throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.EDITION_PACK_REFUSED, "the edition '" + name
                    + "' does not include the pack '" + pack + "': its packs are " + packs + " and allow-only (drishti.edition-spec.packs.allow-only)");
        }
    }

    /** False for a command (a mnemonic, any case) the edition hides from people; its kind stays readable by the engines. */
    public boolean commandShown(String code) {
        return !active() || commands.isEmpty() || code == null || commands.contains(code.strip().toUpperCase(Locale.ROOT));
    }

    /** False for a reserved or console word ({@link #WORDS}) the edition switches off. */
    public boolean wordOn(String word) {
        return !active() || word == null || !Boolean.FALSE.equals(words.get(word.strip().toUpperCase(Locale.ROOT)));
    }

    /** The feature preset ({@code on}, {@code off} or a value such as {@code edition}), or {@code fallback} when the edition does not set it. */
    public String feature(String key, String fallback) {
        String v = active() ? features.get(key) : null;
        return v == null || v.isBlank() ? fallback : v.strip().toLowerCase(Locale.ROOT);
    }

    /** False for an Admin page (its id: {@code ai}, {@code bi}, {@code net} ...) the edition hides. */
    public boolean adminPageShown(String page) {
        return !active() || adminPages.isEmpty() || page == null || adminPages.contains(page);
    }

    /** False for a BI dataset outside the edition's list (an entry ending in {@code *} is a prefix). */
    public boolean datasetShown(String id) {
        if (!active() || datasets.isEmpty() || id == null) {
            return true;
        }
        for (String d : datasets) {
            if (d.endsWith("*") ? id.startsWith(d.substring(0, d.length() - 1)) : d.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** True when the edition reduces BI to its own datasets and models. */
    public boolean biReduced() {
        return active() && (!datasets.isEmpty() || !models.isEmpty());
    }

    /** False for a BI model the edition does not offer: one it does not list (when it lists models). */
    public boolean modelShown(String name) {
        return !active() || models.isEmpty() || name == null || models.contains(name.contains("@") ? name.substring(0, name.indexOf('@')) : name);
    }

    /** What {@code /api/v1/about} and {@code /public/about} say of the edition: {@code {name, title}}, or null without one. */
    public Map<String, Object> about() {
        if (!active()) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("title", title.isEmpty() ? name : title);
        m.put("packs", packs);
        m.put("allowOnly", allowOnly);
        return m;
    }
}
