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
package com.ash.drishti.bicompute.rachana;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.rachana.el.Functions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The one function catalogue page (docs/architecture/ARCHITECTURE.md section 5): {@code docs/guides/FUNCTIONS.md} is what the registries give (Rachana's
 * table and the engine's functions in it), and the registries and the reviewed catalogue (FUNCTION_CATALOGUE.md) name the same
 * functions: every registered function is in the catalogue, and every function the catalogue says Rachana or the engine has built
 * (sections 1 to 4 and 6 to 9) is registered. Regenerate the page with {@code -Ddrishti.functions.write=true}.
 */
class FunctionCataloguePageTest {

    static final Path ROOT = Path.of("../..");
    static final Pattern NAME = Pattern.compile("`([a-z_][a-z0-9_]*)\\(");
    static final Pattern SECTION = Pattern.compile("^## (\\d+)\\.");
    static final Set<Integer> SECTIONS = Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9);

    @Test
    void theCommittedPageIsWhatTheRegistriesGive() throws Exception {
        Path page = ROOT.resolve(FunctionCataloguePage.FILE);
        String expected = FunctionCataloguePage.markdown(FunctionCataloguePage.header(ROOT.resolve(FunctionCataloguePage.CATALOGUE)));
        if (Boolean.getBoolean("drishti.functions.write")) {
            Files.writeString(page, expected, StandardCharsets.UTF_8);
        }
        assertThat(Files.readString(page, StandardCharsets.UTF_8)).as(FunctionCataloguePage.FILE
                + " is generated: run this test with -Ddrishti.functions.write=true").isEqualTo(expected);
    }

    @Test
    void theRegistriesAndTheReviewedCatalogueNameTheSameFunctions() throws Exception {
        List<String> lines = Files.readAllLines(ROOT.resolve(FunctionCataloguePage.CATALOGUE), StandardCharsets.UTF_8);
        Set<String> mentioned = new TreeSet<>();
        Set<String> built = new LinkedHashSet<>();
        int section = 0;
        for (String line : lines) {
            Matcher s = SECTION.matcher(line);
            if (s.find()) {
                section = Integer.parseInt(s.group(1));
                continue;
            }
            if (!line.startsWith("| `")) {
                continue;
            }
            String[] cells = line.split("(?<!\\\\)\\|");
            Matcher m = NAME.matcher(cells[1]);
            Set<String> names = new LinkedHashSet<>();
            while (m.find()) {
                names.add(m.group(1));
            }
            mentioned.addAll(names);
            String status = cells[cells.length - 1].strip();
            boolean ours = status.contains("Rachana") || status.contains("engine") || status.contains("all three")
                    || section == 4 && status.equals("built");
            if (SECTIONS.contains(section) && status.startsWith("built") && ours) {
                built.addAll(names);
            }
        }
        Set<String> registered = new TreeSet<>();
        Functions.catalogue().forEach(e -> registered.add(e.name()));
        Set<String> windows = new TreeSet<>();
        com.ash.drishti.kernel.api.pivot.Windows.FUNCTIONS.forEach(w -> windows.add(w.name()));
        assertThat(windows).as("every share, rank and window of the pivot is in FUNCTION_CATALOGUE.md").allMatch(mentioned::contains);
        registered.addAll(windows);
        assertThat(registered).as("every registered function is in FUNCTION_CATALOGUE.md").allMatch(mentioned::contains);
        assertThat(built).as("every function FUNCTION_CATALOGUE.md says Rachana or the engine has built is registered")
                .allMatch(registered::contains);
        assertThat(built).hasSizeGreaterThan(60);
    }
}
