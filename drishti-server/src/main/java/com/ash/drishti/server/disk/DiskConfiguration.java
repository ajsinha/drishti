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
package com.ash.drishti.server.disk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/** The server's local disk (2.9.7, RESOURCES_AND_SCALE.md section 7): its settings and the housekeeping at start. */
@Configuration
@EnableConfigurationProperties(DiskProperties.class)
public class DiskConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(DiskConfiguration.class);
    private final DiskProperties props;

    public DiskConfiguration(DiskProperties props) {
        this.props = props;
    }

    /** At start: the heap dumps beyond {@code drishti.disk.max-dumps} go (the newest stay). */
    @EventListener(ApplicationReadyEvent.class)
    public void housekeeping() {
        java.nio.file.Path dumps = Dumps.folder(props.dumpsDir());
        int gone = Dumps.prune(dumps, props.maxDumps());
        if (gone > 0) {
            LOG.info("disk: {} heap dumps beyond the newest {} deleted from {}", gone, props.maxDumps(), dumps);
        }
    }
}
