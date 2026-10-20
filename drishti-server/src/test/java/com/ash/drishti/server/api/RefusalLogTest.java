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
    void refusalsThatAlternateWhatTheySayAboutANodeAreOneLineEachFromTheFirst() {
        // SOAK2-05: in the 2 s before drishti-c was suspect, pages said "failed" and histories "not answering", one after the other: 38 lines
        for (int i = 0; i < 400; i++) {
            handler.drishti(i % 2 == 0
                    ? new DrishtiException(ErrorCode.NET_PEER_FAILED, "the node drishti-c that holds trade failed reading trade/MX-" + i)
                    : down("MX-" + i, "not answering: the connection failed"));
            now.addAndGet(5);                                           // 2 s
        }
        assertThat(lines.list).extracting(ILoggingEvent::getFormattedMessage).hasSize(2)
                .anySatisfy(m -> assertThat(m).contains("failed reading"))
                .anySatisfy(m -> assertThat(m).contains("drishti-c (not answering"));
        handler.drishti(down("MX-x", "suspect"));                      // then suspect, then dead: a line each
        handler.drishti(down("MX-y", "dead"));
        handler.drishti(down("MX-z", "dead"));
        assertThat(lines.list).hasSize(4);
        now.addAndGet(60_000);                                          // a minute on: the count, not a line per refusal
        handler.drishti(down("MX-w", "dead"));
        handler.drishti(down("MX-v", "dead"));
        assertThat(lines.list).hasSize(5);
        assertThat(lines.list.get(4).getFormattedMessage()).contains("times in the last");
    }

    @Test
    void aNodeGossipSaysIsBackButNotYetRoutableIsRejoiningNotAlive() {
        var alive = new com.ash.drishti.net.member.Member("drishti-c", "http://192.0.2.1:1", 2, 1,
                com.ash.drishti.net.member.MemberState.ALIVE, "", "", com.ash.drishti.net.member.Member.PROTOCOL, List.of(), 0, false);
        assertThat(com.ash.drishti.server.net.NetRouting.label(alive, false)).isEqualTo("rejoining");
        assertThat(com.ash.drishti.server.net.NetRouting.label(alive, true)).isEqualTo("alive");
        assertThat(com.ash.drishti.server.net.NetRouting.label(alive.withState(com.ash.drishti.net.member.MemberState.DEAD, 1), false))
                .isEqualTo("dead");
        assertThat(com.ash.drishti.server.net.NetRouting.label(alive.withDrained(true), false)).isEqualTo("drained");
        handler.drishti(down("MX-1", "rejoining"));
        assertThat(lines.list).singleElement().satisfies(e -> assertThat(e.getFormattedMessage()).contains("drishti-c (rejoining)"));
    }

    @Test
    void anUnexpectedServerErrorKeepsItsStackTrace() {
        handler.drishti(new DrishtiException(ErrorCode.SOURCE_FAILED, "trade-endur failed reading trade/1"));
        handler.drishti(new DrishtiException(ErrorCode.SOURCE_FAILED, "trade-endur failed reading trade/2"));
        assertThat(lines.list).hasSize(2).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNotNull());
    }
}
