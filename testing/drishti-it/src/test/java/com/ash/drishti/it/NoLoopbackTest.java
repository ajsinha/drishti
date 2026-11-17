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
import org.junit.jupiter.api.Test;

/**
 * Guard G13 (i7a): a module asks the kernel through its typed {@code Kernel}, never over HTTP to its own server. No main class under
 * {@code kernel/} or {@code modules/} builds a URL on {@code 127.0.0.1} or {@code localhost} from {@code local.server.port}.
 */
class NoLoopbackTest {

    @Test
    void noMainClassCallsItsOwnServerOverTheLoopback() throws IOException {
        assertThat(offenders(RepositoryFiles.root())).isEmpty();
    }

    /** True when the source builds a loopback URL from the server's own port. */
    static boolean loopsBack(String source) {
        boolean port = source.contains("local.server.port");
        return port && (source.contains("127.0.0.1") || source.contains("localhost"));
    }

    static List<String> offenders(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path p : RepositoryFiles.withSuffixes(Set.of(".java"))) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            if ((rel.startsWith("kernel/") || rel.startsWith("modules/")) && rel.contains("/src/main/")
                    && loopsBack(Files.readString(p))) {
                out.add(rel);
            }
        }
        return out;
    }

    @Test
    void theScanSeesALoopback() {
        assertThat(loopsBack("URI.create(\"http://127.0.0.1:\" + env.getProperty(\"local.server.port\"))")).isTrue();
        assertThat(loopsBack("http://localhost:8080/x")).isFalse();
    }
}
