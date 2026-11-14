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
package com.ash.drishti.server.feeds;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.security.Entitlements;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The server's view of the data feeds (docs/guides/WORLD_DATA_GUIDE.md): {@code drishti.feeds.config} (the feed files, default
 * {@code ./config/feeds}), {@code drishti.feeds.root} (where {@code tools/drishti.py} is, for Run now; default {@code .}),
 * {@code drishti.feeds.python} (the Python Run now starts; default the console's environment, else python3) and
 * {@code drishti.feeds.sample-seconds} (how often the ledger is read for the gauge {@code drishti.datafeed.failed}; default 60).
 */
@Configuration
public class DataFeedsConfiguration {

    /** The data feeds, their gauge sampled every {@code drishti.feeds.sample-seconds}. */
    @Bean(destroyMethod = "stop")
    public DataFeeds dataFeeds(Environment env, ObjectProvider<MeterRegistry> registry) {
        DataFeeds f = new DataFeeds(Path.of(env.getProperty("drishti.feeds.config", "./config/feeds")),
                Path.of(env.getProperty("drishti.data.dir", "./data")), Path.of(env.getProperty("drishti.feeds.root", ".")),
                env.getProperty("drishti.feeds.python", ""), registry.getIfAvailable());
        f.start(Math.max(5, env.getProperty("drishti.feeds.sample-seconds", Long.class, 60L)));
        return f;
    }

    /** {@code /api/v1/admin/feeds}: administrators only. */
    @Bean
    public DataFeedsController dataFeedsController(DataFeeds feeds, Entitlements entitlements, ObjectProvider<AuditLog> audit) {
        return new DataFeedsController(feeds, entitlements, audit.getIfAvailable());
    }
}
