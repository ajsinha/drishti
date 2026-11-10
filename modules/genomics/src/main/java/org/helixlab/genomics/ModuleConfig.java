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

    /** The VCF reader, a connector extension: the kinds variant and sample. */
    @Bean
    public VcfConnector vcfConnector(GenomicsData data) {
        return new VcfConnector(data);
    }

    /** The kinds computed from them: gene and cohort. */
    @Bean
    public GenomicsKinds genomicsKinds(GenomicsData data) {
        return new GenomicsKinds(data);
    }

    /** The Rachana function genomics_af_class(af). */
    @Bean
    public GenomicsFunctions genomicsFunctions() {
        return new GenomicsFunctions();
    }

    /** Its REST API, under /api/v1/genomics/. */
    @Bean
    public GenomicsController genomicsController(GenomicsData data) {
        return new GenomicsController(data);
    }
}
