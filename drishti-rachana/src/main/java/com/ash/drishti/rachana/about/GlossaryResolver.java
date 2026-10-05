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

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds what a field of a page means (docs/architecture/CONTEXT_HELP.md, layer 2). A field is named by its document path with
 * dots between names and no array steps ({@code contributions[3].var} is {@code contributions.var}), the rule of
 * {@code drishti.security.redact}. First hit wins:
 *
 * <ol>
 *   <li>the kind's own glossary, by the whole path (most specific pack first, through {@code extends});
 *   <li>a vocabulary entry named like the path's last name (most specific pack first);
 *   <li>what a derived kind says of its own field (supplied by the caller);
 *   <li>the core vocabulary shipped with the server, the lowest priority of all;
 *   <li>nothing.
 * </ol>
 *
 * Stateless over an immutable catalogue snapshot; thread-safe.
 */
public final class GlossaryResolver {

    private static final Pattern STEP = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern PLAIN = Pattern.compile("^\\s*[$@]\\.([A-Za-z_][A-Za-z0-9_]*(?:(?:\\[[^\\]]*\\])*\\.[A-Za-z_][A-Za-z0-9_]*)*(?:\\[[^\\]]*\\])*)\\s*$");

    private final AboutSource catalog;
    private final String locale;

    public GlossaryResolver(AboutSource catalog) {
        this(catalog, AboutCatalog.DEFAULT_LOCALE);
    }

    private GlossaryResolver(AboutSource catalog, String locale) {
        this.catalog = catalog;
        this.locale = locale;
    }

    /** The same resolver answering in {@code locale} (a value {@link AboutCatalog#localeFor} returned); untranslated entries stay English. */
    public GlossaryResolver forLocale(String locale) {
        return new GlossaryResolver(catalog, locale == null ? AboutCatalog.DEFAULT_LOCALE : locale);
    }

    /**
     * @param kind the kind of the page
     * @param key a normalised field path
     * @param derived what the kind's own definition says of the field (a derived kind's formula), or empty
     */
    public Optional<GlossaryEntry> resolve(String kind, String key, Function<String, Optional<GlossaryEntry>> derived) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        AboutText t = catalog.forKind(kind, locale).orElse(null);
        String last = key.substring(key.lastIndexOf('.') + 1);
        if (t != null) {
            GlossaryEntry g = t.glossary().get(key);
            if (g != null) {
                return Optional.of(g);
            }
            GlossaryEntry v = t.vocabulary().get(last);
            if (v != null) {
                return Optional.of(v);
            }
        }
        Optional<GlossaryEntry> d = derived == null ? Optional.empty() : derived.apply(key);
        return d.isPresent() ? d : catalog.core(last);
    }

    /** A document path as a glossary key: array steps and a leading {@code $.} dropped. */
    public static String normalise(String path) {
        if (path == null) {
            return null;
        }
        String s = STEP.matcher(path.strip()).replaceAll("");
        if (s.startsWith("$.") || s.startsWith("@.")) {
            s = s.substring(2);
        } else if (s.equals("$") || s.equals("@")) {
            return null;
        }
        return s.isEmpty() ? null : s;
    }

    /**
     * The glossary key of a column or field bound by {@code bind}: a plain path ({@code $.var99}, or {@code @.var} inside the
     * rows named by {@code rowsExpr}), else null (a computed value names no one field).
     */
    public static String keyOf(String bind, String rowsExpr) {
        if (bind == null) {
            return null;
        }
        Matcher m = PLAIN.matcher(bind);
        if (!m.matches()) {
            return null;
        }
        String name = normalise("$." + m.group(1));
        if (bind.strip().startsWith("@")) {
            String rows = rowsExpr == null ? null : keyOf(rowsExpr, null);
            return rows == null ? name : rows + "." + name;
        }
        return name;
    }
}
