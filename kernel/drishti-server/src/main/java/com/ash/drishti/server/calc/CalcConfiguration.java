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
package com.ash.drishti.server.calc;

import com.ash.drishti.engine.search.ColumnRead;
import com.ash.drishti.engine.search.StructuredSearch;
import com.ash.drishti.engine.source.SourceRouter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Calc's beans: its settings and the whole-column reader behind {@code drishti.columns()}. */
@Configuration
@EnableConfigurationProperties(CalcProperties.class)
public class CalcConfiguration {

    @Bean
    public ColumnRead columnRead(SourceRouter router, StructuredSearch search, CalcProperties props) {
        return new ColumnRead(router, search, props.columnsBudget());
    }
}
