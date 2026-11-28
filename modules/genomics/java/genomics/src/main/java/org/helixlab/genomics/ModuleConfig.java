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
package org.helixlab.genomics;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The module's Spring configuration: the kernel connects each bean of an extension type through its registration bridge. */
@Configuration
public class ModuleConfig {

    @Bean
    public GenomicsData genomicsData() {
        return new GenomicsData();
    }

    /** The Rachana function genomics_af_class(af). */
    @Bean
    public GenomicsFunctions genomicsFunctions() {
        return new GenomicsFunctions();
    }

    /** The showcase's tables (the packs genomics-showcase and protein-engineering, read through the kernel's bindings once the module has started). */
    @Bean
    public ShowcaseData showcaseData() {
        return new ShowcaseData();
    }

    /** The genomics board's answers and the protein workbench's. */
    @Bean
    public GenomicsBoard genomicsBoard(ShowcaseData data) {
        return new GenomicsBoard(data);
    }

    @Bean
    public ProteinBench proteinBench(ShowcaseData data, GenomicsBoard board) {
        return new ProteinBench(data, board);
    }

    /** What the genomicist's Home reads: the figures and the module's own home tables (docs/architecture/ROLE_HOME_SUTRAS.md). */
    @Bean
    public GenomicsHome genomicsHome(GenomicsData data, ShowcaseData showcase) {
        return new GenomicsHome(data, showcase);
    }

    @Bean
    public com.ash.drishti.kernel.api.home.MeasureSource genomicsMeasures(GenomicsHome home) {
        return home.measures();
    }

    @Bean
    public com.ash.drishti.kernel.api.home.HomeTable genomicsGwasTop(GenomicsHome home) {
        return home.tables().get(0);
    }

    @Bean
    public com.ash.drishti.kernel.api.home.HomeTable genomicsManhattan(GenomicsHome home) {
        return home.tables().get(1);
    }

    @Bean
    public com.ash.drishti.kernel.api.home.HomeTable genomicsClinvar(GenomicsHome home) {
        return home.tables().get(2);
    }

    @Bean
    public com.ash.drishti.kernel.api.home.HomeTable genomicsLoads(GenomicsHome home) {
        return home.tables().get(3);
    }

    @Bean
    public com.ash.drishti.kernel.api.home.HomeTable genomicsNeeds(GenomicsHome home) {
        return home.tables().get(4);
    }

    /** The showcase's REST API, under /api/v1/genomics/. */
    @Bean
    public ShowcaseController showcaseController(ShowcaseData data, GenomicsBoard board, ProteinBench bench) {
        return new ShowcaseController(data, board, bench);
    }

    /** Its REST API, under /api/v1/genomics/. */
    @Bean
    public GenomicsController genomicsController(GenomicsData data) {
        return new GenomicsController(data);
    }
}
