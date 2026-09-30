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
package com.ash.drishti.sutra;

import com.ash.drishti.sutra.el.ElCompiler;
import com.ash.drishti.sutra.format.Formats;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans contributed by {@code drishti-sutra}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SutraProperties.class)
public class SutraConfiguration {

    @Bean
    public ElCompiler elCompiler(SutraProperties props) {
        return new ElCompiler(props.expressionCacheSize());
    }

    @Bean
    public Formats formats(SutraProperties props) {
        return Formats.load(props.formatsFile());
    }

    @Bean(destroyMethod = "close")
    public SutraRegistry sutraRegistry(SutraProperties props, ElCompiler elCompiler) {
        return new SutraRegistry(props, elCompiler);
    }

    @Bean
    public SutraMatcher sutraMatcher(SutraRegistry registry, ElCompiler elCompiler, Formats formats) {
        return new SutraMatcher(registry, elCompiler, formats);
    }
}
