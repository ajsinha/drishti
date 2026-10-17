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
package com.ash.drishti.server;

import com.ash.drishti.engine.time.BusinessDateProperties;
import com.ash.drishti.engine.time.BusinessDates;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Pins "today" for tests over the Delta connector's laid-out fixture (business dates 2026-09-29 and 2026-09-30): the
 * current business date is Friday 2026-10-02, just after the fixture was made, so a read without a picked date resolves
 * to the fixture's newest day within the connector's look-back window whatever the wall clock says. Without it these
 * tests fail once the real business date is more than the look-back (10 days) after 2026-09-30.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PinnedBusinessDate {

    public static final Instant NOW = Instant.parse("2026-10-02T15:00:00Z");

    @Bean
    @Primary
    BusinessDates pinnedBusinessDates(BusinessDateProperties props) {
        return new BusinessDates(props, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
