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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * SOAK-06: an expected refusal about another node (DRS-5052 while a peer is down) is one short line per change of what it says, without a
 * stack trace, and repeats are counted once a minute; an unexpected server error keeps its stack trace. Before, each of the 26,697 refusals
 * of the soak's six-minute window was a WARN with a stack trace (68 MB).
 */
class RefusalLogTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private ApiExceptionHandler handler;

    @BeforeEach
    void capture() {
        lines.start();
        logger.addAppender(lines);
        handler = new ApiExceptionHandler(null, new RefusalLog(LoggerFactory.getLogger(ApiExceptionHandler.class), now::get, 60_000));
    }

    @AfterEach
    void release() {
        logger.detachAppender(lines);
    }

    private static DrishtiException down(String id, String state) {
        return new DrishtiException(ErrorCode.NET_PEER_FAILED, "no source here holds trade/" + id + ", and the nodes that hold trade cannot be "
                + "reached now: drishti-c (" + state + "); try again in a moment");
    }

    private long bytes() {
        return lines.list.stream().mapToLong(e -> e.getFormattedMessage().length()
                + (e.getThrowableProxy() == null ? 0 : 200L * e.getThrowableProxy().getStackTraceElementProxyArray().length)).sum();
    }

    @Test
    void tenThousandRefusalsWhileANodeIsDownAreAFewShortLines() {
        for (int i = 0; i < 10_000; i++) {
            handler.drishti(down("MX-" + i, "dead"));
            now.addAndGet(10);                                          // 100 s of refusals, 100 a second
        }
        List<ILoggingEvent> got = lines.list;
        // the first refusal, then one count a minute: 2 lines, never 10,000 with stack traces
        assertThat(got).hasSize(2);
        assertThat(got).allSatisfy(e -> {
            assertThat(e.getThrowableProxy()).isNull();
            assertThat(e.getFormattedMessage()).contains("drishti-c (dead)").doesNotContain("\n");
        });
        assertThat(got.get(1).getFormattedMessage()).contains("request refused 6000 times in the last 60 s");
        assertThat(bytes()).isLessThan(1_000);
    }

    @Test
    void aChangeOfStateIsLoggedAtOnce() {
        handler.drishti(down("MX-1", "suspect"));
        handler.drishti(down("MX-2", "suspect"));
        handler.drishti(down("MX-3", "dead"));
        handler.drishti(down("MX-4", "dead"));
        handler.drishti(new DrishtiException(ErrorCode.NET_PEER_FAILED, "the node drishti-d that holds trade failed reading trade/MX-5"));
        assertThat(lines.list).extracting(ILoggingEvent::getFormattedMessage).hasSize(3)
                .anySatisfy(m -> assertThat(m).contains("drishti-c (suspect)"))
                .anySatisfy(m -> assertThat(m).contains("drishti-c (dead)").contains("1 more refusals before it"))
                .anySatisfy(m -> assertThat(m).contains("drishti-d"));
    }

    @Test
    void anUnexpectedServerErrorKeepsItsStackTrace() {
        handler.drishti(new DrishtiException(ErrorCode.SOURCE_FAILED, "trade-endur failed reading trade/1"));
        handler.drishti(new DrishtiException(ErrorCode.SOURCE_FAILED, "trade-endur failed reading trade/2"));
        assertThat(lines.list).hasSize(2).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNotNull());
    }
}
