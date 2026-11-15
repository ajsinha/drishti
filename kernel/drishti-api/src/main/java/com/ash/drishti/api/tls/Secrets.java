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
package com.ash.drishti.api.tls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * Reads connector settings that may hold a secret: a value written as {@code ${ENV_NAME}} (or {@code ${ENV_NAME:default}}) is
 * read from the environment, and a secret {@code x} may instead be given as {@code x-file} (the path of a file whose first
 * line is the secret, e.g. a mounted secret). A secret is never written in a document.
 */
public final class Secrets {


    private Secrets() {}

    /** The setting with a {@code ${ENV}} placeholder resolved; {@code null} when absent or blank. */
    public static String get(Map<String, String> settings, String key) {
        return get(settings, key, null);   // the process: -D, the settings files, the environment
    }

    /** As {@link #get(Map, String)}, reading the references through {@code env} (null: the process). */
    public static String get(Map<String, String> settings, String key, Function<String, String> env) {
        String v = settings.get(key);
        return v == null || v.isBlank() ? null : expand(v.strip(), env, key);
    }

    /** The secret under {@code key}: its {@code key-file} twin if given, else the (placeholder-resolved) value. */
    public static String secret(Map<String, String> settings, String key) {
        return secret(settings, key, null);
    }

    /** As {@link #secret(Map, String)}, reading the references through {@code env} (null: the process). */
    public static String secret(Map<String, String> settings, String key, Function<String, String> env) {
        return secret(k -> get(settings, k, env), "", key);
    }

    static String secret(Function<String, String> get, String prefix, String key) {
        String file = get.apply(key + "-file");
        if (file != null) {
            Path p = Path.of(file);
            try {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                int nl = text.indexOf('\n');
                return (nl < 0 ? text : text.substring(0, nl)).stripTrailing();
            } catch (IOException e) {
                throw new TlsException(prefix + key + "-file '" + file + "': cannot read the password file ("
                        + (Files.exists(p) ? e.getMessage() : "file not found") + ")", e);
            }
        }
        return get.apply(key);
    }

    /**
     * Every {@code ${NAME}} or {@code ${NAME:default}} in the value, through the one resolver (com.ash.drishti.settingsfile.Placeholders): a
     * {@code -D}, the settings files, the environment, then or {@code env} when given (an empty value is unset); a reference with no value and no default
     * is refused, naming it.
     */
    static String expand(String value, Function<String, String> env, String key) {
        try {
            return com.ash.drishti.settingsfile.Placeholders.require(value, env);   // env null: the process
        } catch (IllegalArgumentException e) {
            throw new TlsException(key + ": " + e.getMessage());
        }
    }
}
