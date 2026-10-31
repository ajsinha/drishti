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
package com.ash.drishti.server.net;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.net}: only where the cluster's files are and how they are watched (CLUSTER_DEPLOYMENT.md › Drishti*net: many servers, one Drishti, section 14). Everything
 * else is in the files of that folder, managed like connector files, never in {@code application.yaml}.
 *
 * @param dir the folder ({@code ./config/net}); created on the first write; ready-made files in {@code config/net.examples/}
 * @param watch {@code auto} (file events, polling as a fallback), {@code poll} or {@code off}
 * @param poll how often the folder is looked at when polling
 */
@ConfigurationProperties("drishti.net")
public record NetProperties(String dir, String watch, Duration poll) {

    public NetProperties {
        dir = dir == null || dir.isBlank() ? "./config/net" : dir;
        watch = watch == null || watch.isBlank() ? "auto" : watch;
        poll = poll == null ? Duration.ofSeconds(5) : poll;
    }
}
