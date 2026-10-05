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
package com.ash.drishti.server.collab.mail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mail templates: fixed files {@code <name>.subject}, {@code <name>.txt} and {@code <name>.html}, from the classpath
 * ({@code collab/templates}) or, when {@code drishti.collab.email.templates-dir} has the file, from there. A template sees a closed
 * set of variables, written {@code ${name}}; the text of a variable is inserted once and never read again as a template, so a note
 * that contains {@code ${...}} stays literal. An unknown name is replaced by nothing.
 */
public final class MailTemplates {

    private final Path override;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** @param overrideDir the directory of replacement templates; blank for none */
    public MailTemplates(String overrideDir) {
        this.override = overrideDir == null || overrideDir.isBlank() ? null : Path.of(overrideDir);
    }

    /** The template file's text. */
    public String load(String name, String extension) {
        String file = name + "." + extension;
        return cache.computeIfAbsent(file, f -> {
            try {
                if (override != null && Files.isRegularFile(override.resolve(f))) {
                    return Files.readString(override.resolve(f), StandardCharsets.UTF_8);
                }
                try (InputStream in = MailTemplates.class.getResourceAsStream("/collab/templates/" + f)) {
                    if (in == null) {
                        throw new IllegalStateException("no mail template '" + f + "'");
                    }
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** One pass over the template: each {@code ${name}} becomes its value (already escaped by the caller for the format). */
    public static String fill(String template, Map<String, String> vars) {
        StringBuilder out = new StringBuilder(template.length() + 256);
        int at = 0;
        while (at < template.length()) {
            int open = template.indexOf("${", at);
            int close = open < 0 ? -1 : template.indexOf('}', open);
            if (open < 0 || close < 0) {
                out.append(template, at, template.length());
                break;
            }
            out.append(template, at, open).append(vars.getOrDefault(template.substring(open + 2, close).trim(), ""));
            at = close + 1;
        }
        return out.toString();
    }
}
