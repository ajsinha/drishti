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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guard G9 (i7a): one reader of the modules folder. Outside {@code ModuleBoot} (and {@code ModuleFiles}, its file helper), no main class
 * names {@code cache/modules}, reads {@code drishti.modules.home} or reads {@code active.yaml}: every reader asks the boot result. The one
 * allowed exception while the old store still exists is {@link #EXCEPTIONS}; it is empty when the old store is gone.
 */
class NoModuleFolderReaderTest {

    /** The boot and its file helper: the only readers. */
    static final Set<String> READERS = Set.of("ModuleBoot.java", "ModuleFiles.java");
    /** The one class allowed to read the old store until it is deleted: {@code LegacyModules} (I5); I8 deletes it and empties this set. */
    static final Set<String> EXCEPTIONS = Set.of("LegacyModules.java");

    static final Pattern FORBIDDEN = Pattern.compile("cache/modules|drishti\\.modules\\.home|active\\.yaml");

    @Test
    void onlyTheBootReadsTheModulesFolder() throws IOException {
        assertThat(offenders(RepositoryFiles.root())).isEmpty();
    }

    /** The main classes (outside the two readers) that name what only the boot reads. */
    static List<String> offenders(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path p : RepositoryFiles.withSuffixes(Set.of(".java"))) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            String name = p.getFileName().toString();
            if (!rel.contains("/src/main/") || READERS.contains(name) || EXCEPTIONS.contains(name)) {
                continue;
            }
            var m = FORBIDDEN.matcher(Files.readString(p));
            if (m.find()) {
                out.add(rel + " names " + m.group());
            }
        }
        return out;
    }

    @Test
    void theScanSeesTheNamesItGuards() {
        for (String s : List.of("\"cache/modules\"", "System.getProperty(\"drishti.modules.home\")", "root.resolve(\"active.yaml\")")) {
            assertThat(FORBIDDEN.matcher(s).find()).as(s).isTrue();
        }
        assertThat(FORBIDDEN.matcher("drishti.modules.shipped").find()).isFalse();
    }
}
