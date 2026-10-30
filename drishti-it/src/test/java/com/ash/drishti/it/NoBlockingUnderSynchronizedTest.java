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
 *
 * <p>2.9.7 (RESOURCES_AND_SCALE.md section 6.5): the scan also catches what the body <i>calls</i> that is known to read the lake (an engine
 * loading a date: {@code svc.date(}, {@code loader.load(}, {@code tradePaths(}, {@code loadShard(}), and the loader lambdas of synchronous
 * Caffeine caches ({@code Cache<} fields: {@code cache.get(key, k -> ...)} runs the loader inside the map's {@code synchronized} bin), which
 * must not block either: use {@code SingleFlight} or an {@code AsyncCache}.
 */
class NoBlockingUnderSynchronizedTest {

    private static final Pattern BLOCKING = Pattern.compile(
            "\\bFiles\\.|\\bInputStream\\b|\\bOutputStream\\b|\\.read\\(|\\.write\\(|\\bSocket\\b|Thread\\.sleep|\\.await\\(|\\.join\\(\\)"
                    + "|\\.get\\(\\s*\\d+\\s*,|\\bDriverManager\\b|\\.executeQuery\\(|\\.executeUpdate\\(|\\.execute\\(|\\.getConnection\\(|\\.wait\\("
                    // 2.9.7: calls known to read the lake (an engine's date load, a trade's paths, a shard's rows)
                    + "|\\bsvc\\.date\\(|\\bloader\\(\\)\\.load\\(|\\bloader\\.load\\(|\\.tradePaths\\(|\\.loadShard\\(");

    /** A synchronous Caffeine cache field ({@code Cache<K, V> name}, never an {@code AsyncCache}). */
    private static final Pattern SYNC_CACHE = Pattern.compile("(?<![A-Za-z])(?:caffeine\\.cache\\.)?(?:Loading)?Cache<[^;=()]*>\\s+(\\w+)\\s*[=;]");

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

    /** The loader of a synchronous Caffeine cache runs inside the map's {@code synchronized} bin: it must not block (2.9.7). */
    @Test
    void synchronousCacheLoadersNeverBlock() throws IOException {
        Path root = RepositoryFiles.root();
        List<String> found = new ArrayList<>();
        for (Path p : RepositoryFiles.withSuffixes(Set.of(".java"))) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if (!rel.contains("/src/main/")) {
                continue;
            }
            List<String> lines = Files.readAllLines(p);
            String code = String.join("\n", lines.stream().map(NoBlockingUnderSynchronizedTest::withoutComment).toList());
            Matcher f = SYNC_CACHE.matcher(code);
            while (f.find()) {
                found.addAll(loaders(rel, lines, code, f.group(1)));
            }
        }
        assertThat(found).as("a synchronous cache's loader must not block: SingleFlight or an AsyncCache, or mark a justified case with pinning-ok")
                .isEmpty();
    }

    /** The blocking loaders of {@code name.get(key, k -> ...)} in {@code code}. */
    static List<String> loaders(String rel, List<String> lines, String code, String name) {
        List<String> found = new ArrayList<>();
        Matcher g = Pattern.compile("\\b" + Pattern.quote(name) + "\\.get\\(").matcher(code);
        while (g.find()) {
            int depth = 1;
            int end = g.end();
            for (; end < code.length() && depth > 0; end++) {
                char c = code.charAt(end);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
            }
            String args = code.substring(g.end(), Math.max(g.end(), end - 1));
            if (!args.contains("->")) {
                continue;                                               // getIfPresent-style get(key) or a get with a function reference
            }
            int line = (int) code.substring(0, g.start()).chars().filter(c -> c == '\n').count();
            if (line < lines.size() && (lines.get(line).contains("pinning-ok") || (line > 0 && lines.get(line - 1).contains("pinning-ok")))) {
                continue;
            }
            Matcher b = BLOCKING.matcher(args.substring(args.indexOf("->")));
            if (b.find()) {
                found.add(rel + ":" + (line + 1) + " " + name + ".get(...) loader calls " + b.group().trim());
            }
        }
        return found;
    }

    @Test
    void theScanRecognisesBlockingLoaders() {
        String code = "private final Cache<String, Object> files = x;\nObject a = files.get(k, x -> Files.readString(p));\n"
                + "private final AsyncCache<String, Object> later = y;\nObject b = later.get(k, x -> Files.readString(p));\n"
                + "Object c = files.get(k, x -> x + 1);\nObject d = paths.get(k, x -> loader.tradePaths(d, s));";
        Matcher f = SYNC_CACHE.matcher(code);
        List<String> names = new ArrayList<>();
        while (f.find()) {
            names.add(f.group(1));
        }
        assertThat(names).containsExactly("files");
        assertThat(loaders("X.java", List.of(code.split("\n")), code, "files")).hasSize(1);
        assertThat(loaders("X.java", List.of(code.split("\n")), code, "paths")).hasSize(1);
        assertThat(BLOCKING.matcher("synchronized (x) { VarDate usd = svc.date(day); }").find()).isTrue();
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
