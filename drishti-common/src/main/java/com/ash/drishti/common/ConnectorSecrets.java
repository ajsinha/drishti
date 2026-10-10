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
package com.ash.drishti.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a connector setting may hold when it is a credential. A configuration file is never a place for a secret, so a
 * credential setting is either an environment reference ({@code ${DB_PASSWORD}}) or a file reference
 * ({@code file:/run/secrets/db-password}, whose first line is the secret, read when the connector starts).
 */
public final class ConnectorSecrets {

    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*(password|passwd|secret|token|api[-_.]?key|access[-_.]?key|private[-_.]?key|credential).*");
    private static final Pattern ENV_ONLY = Pattern.compile("\\$\\{[A-Za-z_][A-Za-z0-9_]*}(:\\$\\{[A-Za-z_][A-Za-z0-9_]*})*");   // one reference, or KEY:SECRET references
    private static final Pattern URL_PASSWORD = Pattern.compile("(?i)(password|pwd)=(?!\\$\\{)[^&;]+|://[^/@\\s:]+:(?!\\$\\{)[^/@\\s]+@");
    private static final String FILE = "file:";

    private ConnectorSecrets() {}

    /** True for a setting name that holds a credential, whatever the plugin. */
    public static boolean secretKey(String key) {
        // a "-file" twin (tls.keystore-password-file) holds the path of the secret, not the secret
        return !key.endsWith("-file") && SECRET_KEY.matcher(key).matches();
    }

    /** True for an environment reference or a {@code file:} reference. */
    public static boolean reference(String value) {
        return ENV_ONLY.matcher(value).matches() || value.startsWith(FILE) && value.length() > FILE.length();
    }

    /** True when a URL-like value carries a literal password ({@code user:pw@host}, {@code password=pw}). */
    public static boolean urlPassword(String value) {
        return URL_PASSWORD.matcher(value).find();
    }

    /** The settings with every {@code file:} reference on a credential key replaced by the file's first line. */
    public static Map<String, String> resolve(Map<String, String> settings) {
        Map<String, String> out = new LinkedHashMap<>(settings);
        for (Map.Entry<String, String> e : settings.entrySet()) {
            String v = e.getValue();
            if (v != null && v.startsWith(FILE) && secretKey(e.getKey())) {
                try {
                    String text = Files.readString(Path.of(v.substring(FILE.length()).trim()), StandardCharsets.UTF_8);
                    int nl = text.indexOf('\n');
                    out.put(e.getKey(), (nl < 0 ? text : text.substring(0, nl)).strip());
                } catch (IOException | RuntimeException x) {
                    throw new IllegalStateException(e.getKey() + ": cannot read the secret file " + v.substring(FILE.length()) + " (" + x.getMessage() + ")");
                }
            }
        }
        return out;
    }
}
