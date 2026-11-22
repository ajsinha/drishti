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
package com.ash.drishti.server.edition;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The lenses offered follow the modules that are on ({@code drishti.lenses=default}), and a lens named that cannot be offered never stops
 * the start: it is left out with a problem in words (the ERROR line and Health's red line).
 */
class LensOfferTest {

    /**
     * As a server of shipped .dmod modules sees it: the kernel declares world, markets, research, cio, publicsector (the registry offers
     * a declared lens while its modules are on); banking's risk, trading and market are known by their lens files only
     * (config/lenses/banking/); markets is marked offered-by-default: false; mysite is a site's own lens file at the top of the folder.
     */
    private static LensOffer.Catalogue catalogue(boolean genomicsOn, boolean bankingOn) {
        List<String> offered = genomicsOn ? List.of("world", "markets", "research", "cio", "publicsector") : List.of("world", "markets", "cio", "publicsector");
        Set<String> declared = Set.of("world", "markets", "research", "cio", "publicsector");
        java.util.Map<String, String> files = new java.util.TreeMap<>(java.util.Map.of("market", "banking", "risk", "banking", "trading", "banking",
                "markets", "capitalmarkets", "world", "economy", "research", "genomics", "mysite", ""));
        return new LensOffer.Catalogue() {
            @Override
            public List<String> offered() {
                return offered;
            }

            @Override
            public boolean declared(String name) {
                return declared.contains(name);
            }

            @Override
            public List<String> files() {
                return List.copyOf(files.keySet());
            }

            @Override
            public boolean fileModuleOn(String name) {
                String m = files.get(name);
                return m == null || m.isEmpty() || !m.equals("banking") && !m.equals("genomics") || m.equals("banking") && bankingOn || m.equals("genomics") && genomicsOn;
            }

            @Override
            public boolean offDefault(String name) {
                return name.equals("markets");
            }
        };
    }

    private static LensOffer.Catalogue catalogue(boolean genomicsOn) {
        return catalogue(genomicsOn, true);
    }

    @Test
    void theDefaultIsEveryLensOfTheModulesThatAreOn() {
        LensOffer on = LensOffer.of(List.of("default"), catalogue(true));
        assertThat(on.names()).containsExactlyInAnyOrder("world", "research", "risk", "trading", "market", "mysite");   // no file: cio; off by default: markets
        assertThat(on.problems()).isEmpty();
        LensOffer off = LensOffer.of(List.of("default"), catalogue(false));
        assertThat(off.names()).containsExactlyInAnyOrder("world", "risk", "trading", "market", "mysite");     // genomics off: no Research
        assertThat(LensOffer.of(List.of("default"), catalogue(true, false)).names()).doesNotContain("risk", "trading", "market");   // banking off
        assertThat(off.problems()).isEmpty();
    }

    @Test
    void aListMayHoldTheDefaultWordAndNamesALensOffByDefault() {
        assertThat(LensOffer.of(List.of("default", "markets"), catalogue(true)).names()).contains("research", "markets");
        assertThat(LensOffer.of(List.of("risk", "mysite"), catalogue(true)).names()).containsExactly("risk", "mysite");   // a site's own lens file
        assertThat(LensOffer.of(List.of(), catalogue(true)).names()).isEmpty();                                          // DRISHTI_LENSES= offers none
    }

    @Test
    void aLensThatCannotBeOfferedIsLeftOutWithAProblemNeverAFailure() {
        LensOffer o = LensOffer.of(List.of("risk", "research", "nosuch"), catalogue(false));
        assertThat(o.names()).containsExactly("risk");
        assertThat(o.problems()).hasSize(2);
        assertThat(LensOffer.of(List.of("risk"), catalogue(true, false)).problems()).singleElement().asString().contains("module it needs is off");
        assertThat(LensOffer.of(List.of("publicsector"), catalogue(true)).names()).containsExactly("publicsector");   // declared, no file: offered
        assertThat(o.problems().get(0)).contains("lens 'research' is not offered", "module it needs is off", "Fix:");
        assertThat(o.problems().get(1)).contains("lens 'nosuch' is not offered", "no lens file", "Fix:");
    }

    /**
     * The lens files are found through a linked folder: a site (and the browser tests' servers) may link config/lenses, or one module's
     * folder in it, to the shipped files; banking's lenses are known by their files only, so a link not followed dropped them from Home.
     */
    @Test
    void lensFilesAreFoundThroughALinkedFolder(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        java.nio.file.Path shipped = java.nio.file.Files.createDirectories(tmp.resolve("shipped/banking"));
        java.nio.file.Files.writeString(shipped.resolve("risk.yaml"), "edition: risk\n");
        java.nio.file.Files.createDirectories(tmp.resolve("shipped/economy"));
        java.nio.file.Files.writeString(tmp.resolve("shipped/economy/world.yaml"), "edition: world\n");
        java.nio.file.Path site = java.nio.file.Files.createDirectories(tmp.resolve("site"));
        java.nio.file.Files.createSymbolicLink(site.resolve("lenses"), tmp.resolve("shipped"));                  // the whole folder linked
        assertThat(EditionConfiguration.lensFiles(site.resolve("lenses"))).containsEntry("risk", "banking").containsEntry("world", "economy");
        java.nio.file.Path mixed = java.nio.file.Files.createDirectories(tmp.resolve("mixed"));
        java.nio.file.Files.createSymbolicLink(mixed.resolve("banking"), shipped);                               // one module's folder linked
        assertThat(EditionConfiguration.lensFiles(mixed)).containsEntry("risk", "banking");
    }
}
