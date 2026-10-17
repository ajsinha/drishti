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
package com.ash.drishti.server.bi.mq;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The model layer and its query language, {@code drishti.bi.mq.*} (docs/admin/CONFIGURATION.md). Every bound has a default.
 *
 * @param maxDays the longest date window one query may read ({@code time.window}, a relative window), in days
 * @param groupedLimit the rows a grouped query answers when it gives no {@code limit}
 * @param rowsLimit the rows a {@code shape: rows} extract answers when it gives no {@code limit}
 * @param maxLimit the most rows any query may ask for
 * @param maxSubscriptions standing queries open at once on the server
 * @param maxSubscriptionsPerUser standing queries open at once for one user
 * @param minThrottle the shortest throttle a subscription may ask for
 * @param poll the longest a stream waits before it looks again (wave B: answers are pushed; this is the stream's heartbeat step)
 * @param idleClose a subscription no stream has read for this long is closed
 * @param packLive true: a pack-generated dataset is {@code live} where the kind's connector pushes (decision D6); false keeps every one a snapshot
 * @param maintenanceBudget per model, the time standing-query maintenance may take in each second (default 250ms); beyond it the model's standing queries
 *     are recomputed on the tick and say {@code tick}
 * @param tick how often a standing query in tick mode (windows, time intelligence, Python, or over the maintenance budget) is recomputed
 */
@ConfigurationProperties("drishti.bi.mq")
public record MqProperties(Integer maxDays, Integer groupedLimit, Integer rowsLimit, Integer maxLimit, Integer maxSubscriptions, Integer maxSubscriptionsPerUser,
        Duration minThrottle, Duration poll, Duration idleClose, Boolean packLive, Duration maintenanceBudget, Duration tick) {

    /** The properties before wave B (no maintenance budget or tick: the defaults). */
    public MqProperties(Integer maxDays, Integer groupedLimit, Integer rowsLimit, Integer maxLimit, Integer maxSubscriptions, Integer maxSubscriptionsPerUser,
            Duration minThrottle, Duration poll, Duration idleClose, Boolean packLive) {
        this(maxDays, groupedLimit, rowsLimit, maxLimit, maxSubscriptions, maxSubscriptionsPerUser, minThrottle, poll, idleClose, packLive, null, null);
    }

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public MqProperties {
        maxDays = positive(maxDays, 400);
        groupedLimit = positive(groupedLimit, 500);
        rowsLimit = positive(rowsLimit, 10_000);
        maxLimit = positive(maxLimit, 100_000);
        maxSubscriptions = positive(maxSubscriptions, 500);
        maxSubscriptionsPerUser = positive(maxSubscriptionsPerUser, 20);
        minThrottle = minThrottle == null || minThrottle.isNegative() || minThrottle.isZero() ? Duration.ofMillis(100) : minThrottle;
        poll = poll == null || poll.isNegative() || poll.isZero() ? Duration.ofMillis(100) : poll;
        idleClose = idleClose == null || idleClose.isNegative() || idleClose.isZero() ? Duration.ofMinutes(10) : idleClose;
        packLive = packLive == null || packLive;
        maintenanceBudget = maintenanceBudget == null || maintenanceBudget.isNegative() || maintenanceBudget.isZero() ? Duration.ofMillis(250) : maintenanceBudget;
        tick = tick == null || tick.isNegative() || tick.isZero() ? Duration.ofSeconds(5) : tick;
    }

    private static int positive(Integer v, int dflt) {
        return v == null || v <= 0 ? dflt : v;
    }

    /** Defaults for tests and embedded use. */
    public static MqProperties defaults() {
        return new MqProperties(null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
