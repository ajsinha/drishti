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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Java 21 pins a virtual thread's carrier thread while it blocks inside {@code synchronized} (fixed in Java 24), so a
 * {@code synchronized} method or block in main code must not do I/O, sleep, wait on a latch or call JDBC: use a
 * {@code ReentrantLock}. A justified case carries the marker {@code pinning-ok} in a comment on the line of the
 * {@code synchronized} keyword or the line above it.
 */
class NoBlockingUnderSynchronizedTest {

    private static final Pattern BLOCKING = Pattern.compile(
            "\\bFiles\\.|\\bInputStream\\b|\\bOutputStream\\b|\\.read\\(|\\.write\\(|\\bSocket\\b|Thread\\.sleep|\\.await\\(|\\.join\\(\\)"
                    + "|\\.get\\(\\s*\\d+\\s*,|\\bDriverManager\\b|\\.executeQuery\\(|\\.executeUpdate\\(|\\.execute\\(|\\.getConnection\\(|\\.wait\\(");

    @Test
    void synchronizedCodeNeverBlocks() throws IOException {
        Path root = RepositoryFiles.root();
        List<String> found = new ArrayList<>();
        for (Path p : RepositoryFiles.withSuffixes(Set.of(".java"))) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (!rel.contains("/src/main/")) {
                continue;
            }
            List<String> lines = Files.readAllLines(p);
            String code = String.join("\n", lines.stream().map(NoBlockingUnderSynchronizedTest::withoutComment).toList());
            Matcher m = Pattern.compile("\\bsynchronized\\b").matcher(code);
            while (m.find()) {
                int line = (int) code.substring(0, m.start()).chars().filter(c -> c == '\n').count();
                if (line < lines.size() && (lines.get(line).contains("pinning-ok") || (line > 0 && lines.get(line - 1).contains("pinning-ok")))) {
                    continue;
                }
                int open = code.indexOf('{', m.end());
                int semi = code.indexOf(';', m.end());
                if (open < 0 || (semi >= 0 && semi < open) || code.substring(m.end(), open).contains("=")) {
                    continue;                                   // a Collections.synchronizedX call or an abstract declaration, not a body
                }
                int depth = 0;
                int end = open;
                for (; end < code.length(); end++) {
                    char c = code.charAt(end);
                    if (c == '{') {
                        depth++;
                    } else if (c == '}' && --depth == 0) {
                        break;
                    }
                }
                Matcher b = BLOCKING.matcher(code.substring(open, Math.min(end + 1, code.length())));
                if (b.find()) {
                    found.add(rel + ":" + (line + 1) + " synchronized body calls " + b.group().trim());
                }
            }
        }
        assertThat(found).as("use a ReentrantLock for blocking work (Java 21 pins virtual threads), or mark a justified case with pinning-ok").isEmpty();
    }

    @Test
    void theScanRecognisesBlockingCalls() {
        assertThat(BLOCKING.matcher("synchronized (x) { Files.readString(p); }").find()).isTrue();
        assertThat(BLOCKING.matcher("synchronized (x) { cache.put(a, b); }").find()).isFalse();
    }

    private static String withoutComment(String line) {
        String t = line.trim();
        if (t.startsWith("*") || t.startsWith("/*")) {
            return "";
        }
        int i = line.indexOf("//");
        return i >= 0 ? line.substring(0, i) : line;
    }
}
