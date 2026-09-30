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
package com.ash.drishti.graph;

import com.ash.drishti.sutra.el.ElCompiler;
import com.ash.drishti.sutra.format.Formats;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans contributed by {@code drishti-graph}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GraphProperties.class)
public class GraphConfiguration {

    @Bean
    public ReferenceCatalog referenceCatalog(GraphProperties props) {
        return new ReferenceCatalog(props);
    }

    @Bean
    public BadgeRenderer badgeRenderer(GraphProperties props, ElCompiler elCompiler, Formats formats) {
        return new BadgeRenderer(props, elCompiler, formats);
    }
}
