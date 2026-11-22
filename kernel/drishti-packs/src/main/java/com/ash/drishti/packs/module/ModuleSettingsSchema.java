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
import java.util.List;
import java.util.function.Function;

/**
 * A module's settings as its manifest describes them (the manifest's {@code settings:}, docs/architecture/KERNEL_AND_MODULES.md section 2.3): each
 * is read as {@code drishti.module.<id>.<key>} (what {@code ModuleSettings} returns to the module). A setting that was the server's own key before
 * the module owned it names that key ({@code retired:}): the old key is not read; a site that still sets it stops the start, naming the new key
 * ({@link RetiredNames}).
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
     * @param retired the server key it was before (no longer read; a site that sets it stops the start), or empty
     * @param meaning one line for Admin
     * @param group   the heading it sits under in Admin
     */
    public record Item(String key, String type, String retired, String meaning, String group) {
        public Item {
            type = type == null || type.isBlank() ? "string" : type;
            retired = retired == null ? "" : retired;
            meaning = meaning == null ? "" : meaning;
            group = group == null ? "" : group;
        }
    }

    /** One setting as it stands now. {@code source}: {@code module} (its key is set) or {@code default}. */
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
                items.add(new Item(key, i.path("type").asText(""), i.path("retired").asText(""), i.path("meaning").asText(""), i.path("group").asText("")));
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
            out.add(own != null && !own.isBlank() ? new Resolved(i, key, own, "module") : new Resolved(i, key, "", "default"));
        }
        return out;
    }

    /**
     * The refusal lines of the retired names a site sets: one line per setting whose old key {@code whereSet} finds (it returns where, or null when
     * the site does not set it).
     */
    public List<String> retiredLines(Function<String, String> whereSet) {
        List<String> out = new ArrayList<>();
        for (Item i : items) {
            String where = i.retired().isEmpty() ? null : whereSet.apply(i.retired());
            if (where != null) {
                out.add(RetiredNames.line(i.retired(), where, prefix(module) + i.key() + " (Admin -> Modules -> " + module + " -> Settings)"));
            }
        }
        return out;
    }
}
