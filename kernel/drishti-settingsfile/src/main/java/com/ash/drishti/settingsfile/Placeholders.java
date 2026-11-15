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
package com.ash.drishti.settingsfile;

import java.util.function.Function;

/**
 * The one placeholder resolver of every file Drishti reads (docs/admin/CONFIGURATION.md, "Placeholders"): packs, connector templates
 * and site files, bindings, lakes, data loads, engines, modules, lenses, the UI kit, AI and MCP providers, the store and the network
 * files. {@code ${key}} or {@code ${key:default}} (also {@code ${key:-default}}), nested in the default ({@code ${a:${b:x}}}), resolves in
 * this order:
 * <ol>
 *   <li>an explicit {@code -D} system property;</li>
 *   <li>the settings files ({@code -Ddrishti.settings.file}: their entries are system properties, a {@code -D} still wins);</li>
 *   <li>the environment: the key as written ({@code DRISHTI_DELTA_ROOT}), then its variable form ({@code lake.risk.root} is
 *       {@code LAKE_RISK_ROOT});</li>
 *   <li>the default; none: the reference stays as written ({@link #resolve}) or is an error ({@link #require}).</li>
 * </ol>
 * A reader inside the server passes the server's own view ({@code view}: its environment, which holds the system properties, the
 * settings files' entries and the environment in the same order, then its configuration files); a test passes a map. The view replaces
 * the process lookup; null means the process. A value that is not a placeholder is kept as written. Pure Java, no dependencies.
 */
public final class Placeholders {

    private Placeholders() {
    }

    /** Resolves every placeholder in {@code text} from the process (system properties, then the environment); an unknown one with no default stays. */
    public static String resolve(String text) {
        return resolve(text, null);
    }

    /** As {@link #resolve(String)}, through {@code view} (null: the process: system properties, then the environment). */
    public static String resolve(String text, Function<String, String> more) {
        return text == null ? null : expand(text, more, false, 0, null);
    }

    /** As {@link #resolve(String, Function)}, but a placeholder that nothing names and that has no default becomes {@code missing}. */
    public static String resolveOr(String text, Function<String, String> view, String missing) {
        return text == null ? null : expand(text, view, false, 0, missing);
    }

    /** As {@link #resolve(String, Function)}, but a placeholder that nothing names and that has no default is an error naming it. */
    public static String require(String text, Function<String, String> more) {
        return text == null ? null : expand(text, more, true, 0, null);
    }

    /**
     * One key's value, in the documented order, through {@code view} (null: the process); the key as written, then its variable form;
     * null (or empty) when nothing names it.
     */
    public static String lookup(String key, Function<String, String> view) {
        String v;
        String env = SettingsFile.envName(key);
        if (view == null) {
            v = System.getProperty(key);
            if (blank(v)) v = System.getenv(key);
            if (blank(v) && !env.equals(key)) v = System.getenv(env);
        } else {
            v = view.apply(key);
            if (blank(v) && !env.equals(key)) v = view.apply(env);
        }
        return blank(v) ? null : v;
    }

    /**
     * The process's view of one key, for a reader that takes a lookup function where it once took {@code System::getenv}: a {@code -D},
     * the settings files, the environment (the key, then its variable form); null when nothing names it.
     */
    public static String process(String key) {
        return lookup(key, null);
    }

    private static boolean blank(String v) {
        return v == null || v.isEmpty();
    }

    /** Whether a text holds a placeholder. */
    public static boolean has(String text) {
        return text != null && text.contains("${");
    }

    private static String expand(String text, Function<String, String> more, boolean strict, int depth, String missing) {
        if (depth > 16) {
            throw new IllegalStateException("placeholders nested too deep in \"" + text + "\"");
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf("${", i);
            if (start < 0) {
                out.append(text, i, text.length());
                break;
            }
            int end = closing(text, start + 2);
            if (end < 0) {                                              // no closing brace: the rest is text
                out.append(text, i, text.length());
                break;
            }
            out.append(text, i, start);
            String body = text.substring(start + 2, end);
            int colon = body.indexOf(':');
            String key = (colon < 0 ? body : body.substring(0, colon)).strip();
            String def = colon < 0 ? null : body.substring(colon + 1);
            if (def != null && def.startsWith("-")) def = def.substring(1);     // ${KEY:-default}, the shell's form
            String v = key.isEmpty() ? null : lookup(key, more);
            if (v != null) {
                out.append(Placeholders.has(v) ? expand(v, more, strict, depth + 1, missing) : v);
            } else if (def != null) {
                out.append(expand(def, more, strict, depth + 1, missing));
            } else if (strict) {
                throw new IllegalArgumentException("the setting " + key + " is not set: give it with -D" + key + "=..., in the settings file ("
                        + SettingsFile.PROPERTY + "), or as the environment variable " + SettingsFile.envName(key));
            } else if (missing != null) {
                out.append(missing);
            } else {
                out.append(text, start, end + 1);                       // nothing names it: kept as written
            }
            i = end + 1;
        }
        return out.toString();
    }

    /** The index of the brace that closes a placeholder whose body starts at {@code from}, counting nested ones; -1 when none. */
    private static int closing(String text, int from) {
        int depth = 1;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' && i > 0 && text.charAt(i - 1) == '$') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }
}
