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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.lake.tck.TestRoots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The guard of the table catalogue (owner rule 2026-11-27, docs/admin/TABLE_CATALOGUE.md): a table is known by its symbolic name and the
 * lake file says where it is; no main source builds a table's location from a lake root, a domain or namespace and a table or kind
 * itself. The one join is {@code TableCatalogue} (kernel/drishti-api, resolution): a connector's {@code root}, {@code domain} and
 * {@code table.<kind>} go through it, the official results' {@code risk-results} folder through the sink that names it. The scan flags
 * {@code .resolve(domain)}, {@code "/" + domain}, {@code domain + "/"} and a namespace turned into folders anywhere else, and the folder
 * name {@code risk-results} outside the official-results classes. No module or pack name is joined into a lake path either: a lake path
 * has nothing to do with the module that reads it.
 */
class TableCataloguePathsGuardTest {

    static final Path REPO = Path.of(TestRoots.repo());

    static final Pattern JOIN = Pattern.compile("\\.resolve\\(\\s*(?:domain|namespace|ns)\\s*\\)|\"/\"\\s*\\+\\s*(?:domain|namespace)\\b"
            + "|\\b(?:domain|namespace)\\s*\\+\\s*\"/\"|(?:namespace|ns)\\.replace\\('\\.', '/'\\)");
    /** A lake path from a module or pack name: {@code <lake root>/<module id>}, {@code .resolve(moduleId)}, {@code .resolve(packName)}. */
    static final Pattern FROM_NAMES = Pattern.compile("\\.resolve\\(\\s*(?:moduleId|packName|packId|module\\.id\\(\\)|pack\\.name\\(\\))\\s*\\)");
    static final Pattern OFFICIAL_FOLDER = Pattern.compile("\"risk-results[/\"]");

    /** Sources that may name the pieces, each with its reason. */
    static final List<String> ALLOWED = List.of(
            "kernel/drishti-api/src/main/java/com/ash/drishti/api/resolution/TableCatalogue.java",   // the one join
            "modules/banking/java/banking-service/src/main/java/com/ash/drishti/banking/official/OfficialSink.java",   // the folder's one constant
            "kernel/drishti-packs/src/main/java/com/ash/drishti/packs/OfficialReaders.java",         // recognises the official results' reader
            "kernel/drishti-packs/src/main/java/com/ash/drishti/packs/OfficialResults.java",
            "kernel/drishti-server/src/main/java/com/ash/drishti/server/design/PackFragment.java");   // a zip entry of a pack's Sutra, not a lake path

    @Test
    void noMainSourceJoinsALakeRootADomainAndATable() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : mainSources()) {
            String rel = REPO.relativize(file).toString().replace('\\', '/');
            if (ALLOWED.contains(rel)) {
                continue;
            }
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                String t = l.trim();
                if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) {
                    continue;
                }
                if (JOIN.matcher(l).find() || FROM_NAMES.matcher(l).find() || OFFICIAL_FOLDER.matcher(l).find()) {
                    offenders.add(rel + ":" + (i + 1) + "  " + t);
                }
            }
        }
        assertThat(offenders).as("main code that builds a table location itself: ask TableCatalogue (kernel/drishti-api, resolution) instead")
                .isEmpty();
    }

    private static List<Path> mainSources() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String top : List.of("kernel", "plugins", "modules", "compute", "lake", "engines", "net", "clients")) {
            Path dir = REPO.resolve(top);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(p -> p.toString().endsWith(".java")).filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                        .filter(p -> !p.toString().replace('\\', '/').contains("/target/")).forEach(out::add);
            }
        }
        return out;
    }
}
