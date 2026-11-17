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
 * Guard G2 (i7a): the kernel does not know a module. No {@code pom.xml} outside {@code modules/}, {@code testing/drishti-modules-it} and
 * {@code packaging/dist} (the modules' own packaging: it builds their jars and packs them)
 * names an artefact a module's poms define, and no test of the kernel ({@code kernel/**}{@code /src/test}) opens {@code modules/}: it
 * runs on fixtures, and what needs the real modules lives in the modules' own test project.
 */
class KernelNamesNoModuleTest {

    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    /**
     * A test that reads the REPOSITORY's module folders: the repository root (TestRoots.repo(), REPO, ROOT) joined to {@code modules}, or a
     * relative {@code ../modules}.
     * A fixture the test builds under its own folder ({@code tmp.resolve("modules/banking")}) is the kernel's own business and is not matched.
     */
    private static final Pattern OPENS_MODULES = Pattern.compile("(repo\\(\\)|\\b(REPO|ROOT|repo)\\b)[^;\\n]*(?<!\"config\", )\"modules[\"/]|\\.\\./modules");

    @Test
    void noPomOutsideTheModulesNamesAModuleArtefact() throws IOException {
        assertThat(poms()).isEmpty();
    }

    @Test
    void noKernelTestOpensTheModulesFolder() throws IOException {
        assertThat(testsOpeningModules(RepositoryFiles.root())).isEmpty();
    }

    /** The poms outside the modules that name a module's artefact (the artefacts are read from the modules' own poms). */
    static List<String> poms() throws IOException {
        Path root = RepositoryFiles.root();
        Set<String> own = new java.util.HashSet<>();
        List<Path> all = RepositoryFiles.withSuffixes(Set.of("pom.xml"));
        for (Path p : all) {
            if (relative(root, p).startsWith("modules/")) {
                own.add(ownArtifact(Files.readString(p)));
            }
        }
        own.remove("");
        own.remove("drishti-parent");                                       // the shared parent is the kernel's, not a module's
        List<String> out = new ArrayList<>();
        for (Path p : all) {
            String rel = relative(root, p);
            if (rel.startsWith("modules/") || rel.startsWith("testing/drishti-modules-it/") || rel.startsWith("drishti-sdk/")
                    || rel.startsWith("packaging/dist/") || rel.startsWith("docs/")) {
                continue;
            }
            Matcher m = ARTIFACT.matcher(Files.readString(p));
            while (m.find()) {
                if (own.contains(m.group(1))) {
                    out.add(rel + " names " + m.group(1));
                }
            }
        }
        return out;
    }

    /** The test files under {@code kernel/} that name {@code modules/} as a folder to read. */
    static List<String> testsOpeningModules(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path p : RepositoryFiles.withSuffixes(Set.of(".java"))) {
            String rel = relative(root, p);
            if (rel.startsWith("kernel/") && rel.contains("/src/test/") && OPENS_MODULES.matcher(Files.readString(p)).find()) {
                out.add(rel);
            }
        }
        return out;
    }

    /** The artefact a pom defines: the first {@code artifactId} after its {@code parent} block (not a dependency's). */
    static String ownArtifact(String pom) {
        int from = Math.max(0, pom.indexOf("</parent>"));
        Matcher m = ARTIFACT.matcher(pom);
        return m.find(from) ? m.group(1) : "";
    }

    private static String relative(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    @Test
    void theNamesAreFoundWhereTheyAre() {
        assertThat(OPENS_MODULES.matcher("Path.of(TestRoots.repo(), \"modules\")").find()).isTrue();
        assertThat(OPENS_MODULES.matcher("REPO.resolve(\"modules/economy/packs\")").find()).isTrue();
        assertThat(OPENS_MODULES.matcher("\"drishti.modules.home=../../modules\"").find()).isTrue();
        assertThat(OPENS_MODULES.matcher("tmp.resolve(\"modules\")").find()).isFalse();
        assertThat(OPENS_MODULES.matcher("Path.of(TestRoots.repo(), \"config\", \"modules\", \"banking.yaml\")").find()).isFalse();
        assertThat(OPENS_MODULES.matcher("root.resolve(\"modules/banking\")").find()).isFalse();           // a fixture's own root
        assertThat(OPENS_MODULES.matcher("drishti.modules.home").find()).isFalse();
        assertThat(ownArtifact("<parent><artifactId>p</artifactId></parent><artifactId>drishti-x</artifactId><dependency><artifactId>d</artifactId>"))
                .isEqualTo("drishti-x");
    }
}
