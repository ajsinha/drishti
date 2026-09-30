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
package com.ash.drishti.inference;

import com.ash.drishti.rachana.el.ElCompiler;
import com.ash.drishti.rachana.format.Formats;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans contributed by {@code drishti-inference}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InferenceProperties.class)
public class InferenceConfiguration {

    @Bean
    public InferenceEngine inferenceEngine(InferenceProperties props) {
        return new InferenceEngine(Semantics.load(props.semanticsFile(), props.packSemanticsFiles()), Rules.builtIn());
    }

    @Bean
    public LayoutMerger layoutMerger(InferenceEngine engine, ElCompiler elCompiler, Formats formats) {
        return new LayoutMerger(engine, elCompiler, formats);
    }
}
