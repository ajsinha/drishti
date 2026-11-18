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

    /** risk, trading, market, world, research declared; genomics off (research not offered); markets marked offered-by-default: false. */
    private static LensOffer.Catalogue catalogue(boolean genomicsOn) {
        List<String> offered = genomicsOn ? List.of("world", "markets", "research", "cio", "risk", "trading", "market")
                : List.of("world", "markets", "cio", "risk", "trading", "market");
        Set<String> declared = Set.of("world", "markets", "research", "cio", "risk", "trading", "market", "publicsector");
        Set<String> files = Set.of("world", "markets", "research", "risk", "trading", "market", "mysite");
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
            public boolean hasFile(String name) {
                return files.contains(name);
            }

            @Override
            public boolean offDefault(String name) {
                return name.equals("markets");
            }
        };
    }

    @Test
    void theDefaultIsEveryLensOfTheModulesThatAreOn() {
        LensOffer on = LensOffer.of(List.of("default"), catalogue(true));
        assertThat(on.names()).containsExactlyInAnyOrder("world", "research", "risk", "trading", "market");   // no file: cio; off by default: markets
        assertThat(on.problems()).isEmpty();
        LensOffer off = LensOffer.of(List.of("default"), catalogue(false));
        assertThat(off.names()).containsExactlyInAnyOrder("world", "risk", "trading", "market");               // genomics off: no Research
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
        assertThat(o.problems().get(0)).contains("lens 'research' is not offered", "module it needs is off", "Fix:");
        assertThat(o.problems().get(1)).contains("lens 'nosuch' is not offered", "no lens file", "Fix:");
    }
}
