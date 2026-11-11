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
package com.ash.drishti.server.bi;

import com.ash.drishti.server.bi.engine.QueryService;
import com.ash.drishti.server.bi.mq.MqService;
import com.ash.drishti.server.bi.mq.StandingQueries;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The BI metrics of section 6.8, registered with the server's meter registry (Prometheus at {@code /actuator/prometheus}). */
@Configuration
public class BiMetricsConfiguration {

    @Bean
    public BiMetrics biMetrics(ObjectProvider<MeterRegistry> registry, ObjectProvider<QueryService> queries, ObjectProvider<MqService> mq,
            ObjectProvider<StandingQueries> standing) {
        MeterRegistry r = registry.getIfAvailable(SimpleMeterRegistry::new);
        return BiMetrics.attach(r, queries.getIfAvailable(), mq.getIfAvailable(), standing.getIfAvailable());
    }
}
