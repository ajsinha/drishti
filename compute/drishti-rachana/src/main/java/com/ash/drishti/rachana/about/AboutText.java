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
package com.ash.drishti.rachana.about;

import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Template;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Everything the packs say about one kind, merged through {@code extends}: a more specific pack's entry wins, key by key
 * (title, page text, guide, each glossary key, each panel id). Immutable.
 *
 * @param pack the pack the text comes from (the most specific that wrote the page text, else the title)
 * @param packTitle its display title
 * @param kind the kind
 * @param title the kind's plain title, or null
 * @param about the page's template, or null
 * @param guide the pack guide section, or null
 * @param glossary field path to meaning, vocabulary entries already resolved
 * @param panels panel id to template
 * @param vocabulary the shared terms visible to the kind, by field name
 */
public record AboutText(String pack, String packTitle, String kind, String title, Template about, String guide,
        Map<String, GlossaryEntry> glossary, Map<String, Template> panels, Map<String, GlossaryEntry> vocabulary) {

    /** What an expression that failed reads as. */
    public static final String FAILED = "—";

    /**
     * The rendered texts.
     *
     * @param text the page's text, or null
     * @param panels rendered panel text by panel id (only panels asked for)
     * @param errors expressions that failed
     */
    public record Rendered(String text, Map<String, String> panels, int errors) {}

    /**
     * Renders the page text and the text of the panels named, over {@code ctx}. Whatever document {@code ctx} holds is all a
     * template can read, so callers pass the document as the caller may see it. A failing expression reads {@code —}; every
     * text is cut to {@code max} characters.
     */
    public Rendered render(EvalContext ctx, Iterable<String> panelIds, int max) {
        int errors = 0;
        String text = null;
        if (about != null) {
            Template.Lenient l = about.renderLenient(ctx, FAILED);
            text = cap(l.text(), max);
            errors += l.errors();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String id : panelIds) {
            Template t = panels.get(id);
            if (t != null) {
                Template.Lenient l = t.renderLenient(ctx, FAILED);
                out.put(id, cap(l.text(), max));
                errors += l.errors();
            }
        }
        return new Rendered(text, out, errors);
    }

    static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…";
    }
}
