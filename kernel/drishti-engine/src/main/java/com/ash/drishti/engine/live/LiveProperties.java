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
package com.ash.drishti.engine.live;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code drishti.live.*}.
 *
 * @param frame ticks arriving within one frame are coalesced; latest wins
 * @param heartbeat SSE comment interval that keeps proxies from closing idle streams
 * @param maxStreams concurrent live view streams per server; further requests are refused
 * @param window rolling window for the latency percentiles shown in the top bar
 */
@ConfigurationProperties("drishti.live")
public record LiveProperties(Duration frame, Duration heartbeat, Integer maxStreams, Duration window) {

    public LiveProperties {
        frame = frame == null ? Duration.ofMillis(50) : frame;
        heartbeat = heartbeat == null ? Duration.ofSeconds(15) : heartbeat;
        maxStreams = maxStreams == null ? 20_000 : maxStreams;
        window = window == null ? Duration.ofSeconds(30) : window;
    }
}
