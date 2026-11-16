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
package com.ash.drishti.rachana.format;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorldRegionsTest {

    private final WorldRegions regions = WorldRegions.load();

    @Test
    void aCountryHasOneSubRegionAndOneContinent() {
        assertThat(regions.subRegionOf("fra")).contains("SR-WESTERN-EUROPE");
        assertThat(regions.continentOf("FRA")).contains("CT-EUR");
        assertThat(regions.regionOf("KEN", "continent")).contains("CT-AFR");
        assertThat(regions.regionOf("KEN", "subregion")).contains("SR-EASTERN-AFRICA");
        assertThat(regions.regionOf("KEN", "planet")).isEmpty();
        assertThat(regions.subRegionOf("ZZZ")).isEmpty();
    }

    @Test
    void namesAndMembersComeFromTheSameTable() {
        assertThat(regions.name("CT-AME")).contains("Americas");
        assertThat(regions.name("SR-CARIBBEAN")).contains("Caribbean");
        assertThat(regions.countriesOf("SR-WESTERN-EUROPE")).contains("FRA", "DEU", "NLD");
        assertThat(regions.countriesOf("CT-EUR")).contains("FRA", "POL", "GBR").doesNotContain("KEN");
    }

    @Test
    void everyCountryIsInExactlyOneSubRegion() {
        long total = regions.subRegionCodes().stream().mapToLong(c -> regions.countriesOf(c).size()).sum();
        long distinct = regions.subRegionCodes().stream().flatMap(c -> regions.countriesOf(c).stream()).distinct().count();
        assertThat(distinct).isEqualTo(total).isGreaterThan(240);
        assertThat(regions.continentCodes()).hasSize(6);
    }
}
