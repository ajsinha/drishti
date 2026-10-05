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
package com.ash.drishti.server.explain;

import com.ash.drishti.engine.explain.ExplainService;
import com.ash.drishti.identity.AccessLog;
import com.ash.drishti.rachana.about.AboutCatalog;
import com.ash.drishti.rachana.about.AboutProperties;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires Ask about this page. Every bean is cheap and makes no network call; the model is reached only when a question is asked. */
@Configuration
@EnableConfigurationProperties(AskProperties.class)
public class AskConfiguration {

    @Bean
    AskProvider askProvider(AskProperties cfg) {
        return AskProviders.create(cfg);
    }

    @Bean
    AskRateLimiter askRateLimiter(AskProperties cfg) {
        return new AskRateLimiter(Clock.systemUTC(), cfg.perUserPerMinute(), cfg.perUserPerDay());
    }

    @Bean
    PackGuides packGuides(AboutCatalog catalog, AboutProperties props) {
        return new PackGuides(catalog, props);
    }

    @Bean
    AskService askService(ExplainService explain, AskProperties cfg, AskProvider provider, AskRateLimiter limiter, PackGuides guides,
            ObjectProvider<AccessLog> accessLog) {
        return new AskService(explain, cfg, provider, limiter, guides, accessLog);
    }
}
