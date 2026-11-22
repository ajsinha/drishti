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
package com.ash.drishti.packs.module;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A module's settings as its manifest describes them (the manifest's {@code settings:}, docs/architecture/KERNEL_AND_MODULES.md section 2.3): each
 * is read as {@code drishti.module.<id>.<key>} (what {@code ModuleSettings} returns to the module). A setting that was the server's own key before
 * the module owned it names it ({@code was:}): the old key is still read for one release, the module's key wins over it, and Health says
 * which old keys a site still sets.
 *
 * @param module the module id
 * @param items  the settings, in the manifest's order
 */
public record ModuleSettingsSchema(String module, List<Item> items) {

    /** The prefix of a module's settings. */
    public static String prefix(String module) {
        return "drishti.module." + module + ".";
    }

    /**
     * One setting.
     *
     * @param key     the dotted key below the module's prefix
     * @param type    {@code string}, {@code int}, {@code number}, {@code boolean} or {@code list}
     * @param was     the server key it was before (read for one release), or empty
     * @param meaning one line for Admin
     * @param group   the heading it sits under in Admin
     */
    public record Item(String key, String type, String was, String meaning, String group) {
        public Item {
            type = type == null || type.isBlank() ? "string" : type;
            was = was == null ? "" : was;
            meaning = meaning == null ? "" : meaning;
            group = group == null ? "" : group;
        }
    }

    /** One setting as it stands now. {@code source}: {@code module} (its own key), {@code old-key} (the deprecated key), {@code default} (neither is set). */
    public record Resolved(Item item, String key, String value, String source) {
    }

    public ModuleSettingsSchema {
        items = List.copyOf(items);
    }

    /** The schema in a manifest (its {@code settings.items}); none when the manifest declares no settings. */
    public static ModuleSettingsSchema of(String module, JsonNode manifest) {
        List<Item> items = new ArrayList<>();
        for (JsonNode i : manifest.path("settings").path("items")) {
            String key = i.path("key").asText("").strip();
            if (!key.isEmpty()) {
                items.add(new Item(key, i.path("type").asText(""), i.path("was").asText(""), i.path("meaning").asText(""), i.path("group").asText("")));
            }
        }
        return new ModuleSettingsSchema(module, items);
    }

    /** Each setting as it stands, from {@code lookup} (the environment's property, null when unset). */
    public List<Resolved> resolve(Function<String, String> lookup) {
        List<Resolved> out = new ArrayList<>();
        for (Item i : items) {
            String key = prefix(module) + i.key();
            String own = lookup.apply(key);
            if (own != null && !own.isBlank()) {
                out.add(new Resolved(i, key, own, "module"));
                continue;
            }
            String old = i.was().isEmpty() ? null : lookup.apply(i.was());
            out.add(old != null && !old.isBlank() ? new Resolved(i, key, old, "old-key") : new Resolved(i, key, "", "default"));
        }
        return out;
    }

    /**
     * The values the module's own keys give to the server keys that were: {@code old key -> value}, for the settings a site set under
     * {@code drishti.module.<id>.*}. Layered above the old keys, so the module's key wins and the code reading the old key sees it.
     */
    public Map<String, String> asOldKeys(Function<String, String> lookup) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Item i : items) {
            String own = lookup.apply(prefix(module) + i.key());
            if (own != null && !own.isBlank() && !i.was().isEmpty()) {
                out.put(i.was(), own);
            }
        }
        return out;
    }

    /** The deprecation notes: the old keys a site still sets (the site's own sources only, never the product's defaults). */
    public List<String> deprecated(Function<String, Boolean> siteSets) {
        List<String> out = new ArrayList<>();
        for (Item i : items) {
            if (!i.was().isEmpty() && siteSets.apply(i.was())) {
                out.add(i.was() + " is read for one release: set " + prefix(module) + i.key() + " (Admin -> Modules -> " + module + " -> Settings)");
            }
        }
        return out;
    }
}
