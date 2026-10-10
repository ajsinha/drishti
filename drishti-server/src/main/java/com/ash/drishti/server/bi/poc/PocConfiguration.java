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
package com.ash.drishti.server.bi.poc;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RUPAKA PHASE 0 PROOF OF CONCEPT: the POC's beans exist only when {@code drishti.bi.poc.enabled=true}. */
@Configuration
@ConditionalOnProperty(prefix = "drishti.bi.poc", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PocProperties.class)
public class PocConfiguration {

    @Bean(destroyMethod = "close")
    public PocQueryService pocQueryService(PocProperties props) {
        return new PocQueryService(props);
    }
}
