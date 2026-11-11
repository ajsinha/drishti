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
package com.ash.drishti.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * A streamed answer (a workbook download: the VaR Playground, the Why it moved working, the capacity workbook; a BI export) may take
 * minutes: the server's async time limit, {@code spring.mvc.async.request-timeout} ({@code DRISHTI_ASYNC_TIMEOUT}), is 30 minutes by
 * default, not the servlet container's 30 seconds, which cut a large workbook short.
 */
class StreamedDownloadTimeoutTest {

    @Test
    void streamedAnswersMayTakeHalfAnHour() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        Object raw = sources.get(0).getProperty("spring.mvc.async.request-timeout");
        assertThat(raw).as("the async time limit is set").isNotNull();
        String value = raw.toString();
        assertThat(value).startsWith("${DRISHTI_ASYNC_TIMEOUT:");
        String dflt = value.substring(value.indexOf(':') + 1, value.length() - 1);
        assertThat(DurationStyle.detectAndParse(dflt)).isGreaterThanOrEqualTo(Duration.ofMinutes(30));
    }
}
