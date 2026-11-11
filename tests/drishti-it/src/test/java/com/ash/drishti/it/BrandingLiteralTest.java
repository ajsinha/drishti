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
package com.ash.drishti.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Branding from configuration only (docs/admin/CONFIGURATION.md, {@code drishti.branding}): no message the server sends names the product
 * in a string literal. The name comes from {@code drishti.branding.product} ({@code Branding.sqlName()}, {@code Branding.netName()},
 * {@code Branding.name(..)}, or the injected value). Allowed: comments and Javadoc (the licence header is one), log lines (the operator's,
 * not the user's), header names ({@code X-Drishti-User}, {@code Drishti-Run}) and identifiers ({@code DrishtiException}, {@code drishti.*}).
 */
class BrandingLiteralTest {

    private static final Pattern STRING = Pattern.compile("\"(?:[^\"\\\\\\n]|\\\\.)*\"");
    private static final Pattern NAME = Pattern.compile("(?<![\\w-])(Drishti|DRISHTI)(?![\\w-])");
    private static final Pattern LOG = Pattern.compile("\\b(LOG|log|LOGGER|logger)\\s*\\.\\s*(trace|debug|info|warn|error)\\s*\\(|getLogger\\(");
    /** Literals that name the product on purpose, and why. */
    private static final Set<String> ALLOWED = Set.of(
            "\"Drishti lake build\"",    // the Delta commit's engineInfo: who wrote the table, read by tools, not people
            "\"Drishti parallel loader\""); // the same, for the parallel lake loader's commits

    @Test
    void noServerMessageNamesTheProductInALiteral() {
        List<String> found = new ArrayList<>();
        for (Path f : RepositoryFiles.withSuffixes(Set.of(".java"))) {
            String rel = RepositoryFiles.root().relativize(f).toString().replace('\\', '/');
            if (!rel.contains("/src/main/java/")) {
                continue;
            }
            found.addAll(literals(rel, read(f)));
        }
        assertThat(found).as("name the product from drishti.branding (Branding.sqlName(), Branding.netName(), Branding.name(..)), not a literal").isEmpty();
    }

    @Test
    void theGuardSeesALiteralAndLetsHeadersCommentsAndLogsThrough() {
        String src = """
                /* Project Drishti header */
                // Drishti
                 * Drishti in Javadoc
                h.header("X-Drishti-User", "Drishti-Run");
                LOG.info("Drishti started");
                throw new DrishtiException(code, "only Drishti SQL here");
                """;
        assertThat(literals("A.java", src)).singleElement().asString().startsWith("A.java:6:");
    }

    static List<String> literals(String file, String source) {
        List<String> out = new ArrayList<>();
        String[] all = source.split("\n", -1);
        boolean inBlock = false;
        for (int n = 0; n < all.length; n++) {
            String line = all[n];
            String t = line.strip();
            if (inBlock) {
                inBlock = !line.contains("*/");
                continue;
            }
            if (t.startsWith("/*")) {
                inBlock = !line.contains("*/");
                continue;
            }
            if (t.startsWith("*") || t.startsWith("//") || LOG.matcher(line).find()) {
                continue;
            }
            Matcher m = STRING.matcher(line);
            while (m.find()) {
                String lit = m.group();
                if (NAME.matcher(lit).find() && !ALLOWED.contains(lit)) {
                    out.add(file + ":" + (n + 1) + ": " + lit);
                }
            }
        }
        return out;
    }

    private static String read(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
