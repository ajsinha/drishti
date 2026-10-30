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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every cache is bounded in bytes (2.9.7, RESOURCES_AND_SCALE.md section 5.2, D3): a Caffeine cache in main code bounded by a count
 * ({@code maximumSize}) with no weigher fails the build, unless its entries are of a fixed, small size and it is listed with the reason in
 * {@code count-only-caches.txt} (a count of entries of very different sizes is a defect, principle P2). The list only shrinks: a new
 * count-only cache is weighed in bytes, or listed with its reason in review.
 */
class CachesWeighedInBytesTest {

    private static final Pattern BUILDER = Pattern.compile("Caffeine\\s*\\.\\s*newBuilder\\s*\\(\\s*\\)");

    /** The count-only builders in main code: {@code file#cache} (the field or variable it is built for, else the line's text). */
    static Set<String> countOnly() throws IOException {
        Path root = RepositoryFiles.root();
        Set<String> found = new TreeSet<>();
        for (Path p : RepositoryFiles.withSuffixes(Set.of(".java"))) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (!rel.contains("/src/main/")) {
                continue;
            }
            String code = Files.readString(p, StandardCharsets.UTF_8);
            Matcher m = BUILDER.matcher(code);
            while (m.find()) {
                int end = statementEnd(code, m.end());
                String chain = code.substring(m.start(), end);
                if (chain.contains(".maximumSize(") && !chain.contains(".weigher(")) {
                    found.add(rel + "#" + nameOf(code, m.start()));
                }
            }
        }
        return found;
    }

    /** The end of the builder's statement: the first {@code ;} at depth zero. */
    private static int statementEnd(String code, int from) {
        int depth = 0;
        for (int i = from; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '{') {
                depth++;
            } else if (c == ')' || c == '}') {
                if (--depth < 0) {
                    return i;
                }
            } else if (c == ';' && depth == 0) {
                return i;
            }
        }
        return code.length();
    }

    /** The name the cache is assigned to ({@code x = Caffeine...}), else "line N". */
    private static String nameOf(String code, int at) {
        int lineStart = code.lastIndexOf('\n', at) + 1;
        String before = code.substring(Math.max(0, code.lastIndexOf(';', at) + 1), at);
        Matcher n = Pattern.compile("(\\w+)\\s*=(?!=)[^=;]*$", Pattern.DOTALL).matcher(before.strip());
        if (n.find()) {
            return n.group(1);
        }
        Matcher method = Pattern.compile("(\\w+)\\s*\\([^()]*\\)\\s*\\{[^{}]*$", Pattern.DOTALL).matcher(code.substring(0, at));
        if (method.find()) {
            return method.group(1) + "()";                              // returned by a method: its name
        }
        return "line " + (code.substring(0, lineStart).chars().filter(c -> c == '\n').count() + 1);
    }

    static Set<String> allowed() throws IOException {
        Set<String> out = new TreeSet<>();
        for (String line : Files.readAllLines(RepositoryFiles.root().resolve("drishti-it/src/test/resources/count-only-caches.txt"))) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) {
                out.add(t.split("\\s+", 2)[0]);
            }
        }
        return out;
    }

    @Test
    void noNewCacheIsBoundedByACountAlone() throws IOException {
        Set<String> found = countOnly();
        Set<String> allowed = allowed();
        List<String> fresh = new ArrayList<>(found);
        fresh.removeAll(allowed);
        assertThat(fresh).as("weigh these caches in bytes (Caffeine weigher + maximumWeight), or list them with the reason their entries are "
                + "of a fixed small size in drishti-it/src/test/resources/count-only-caches.txt").isEmpty();
        List<String> gone = new ArrayList<>(allowed);
        gone.removeAll(found);
        assertThat(gone).as("these are weighed now (or gone): take them off count-only-caches.txt").isEmpty();
    }

    @Test
    void theScanFindsACountOnlyBuilder() {
        String code = "class X { Cache<String, byte[]> big = Caffeine.newBuilder().maximumSize(100).build();\n"
                + "Cache<String, byte[]> ok = Caffeine.newBuilder().maximumWeight(1L << 20).weigher((String k, byte[] v) -> v.length).build(); }";
        Matcher m = BUILDER.matcher(code);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            String chain = code.substring(m.start(), statementEnd(code, m.end()));
            if (chain.contains(".maximumSize(") && !chain.contains(".weigher(")) {
                out.add(nameOf(code, m.start()));
            }
        }
        assertThat(out).containsExactly("big");
    }
}
