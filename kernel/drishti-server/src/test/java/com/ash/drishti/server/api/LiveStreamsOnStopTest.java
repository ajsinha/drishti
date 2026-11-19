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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * A stream that never ends on its own (a live view of a ticking source) must not hold the server's stop: the stop ends every open
 * server-sent-events stream, runs its completion callbacks (the producer releases its slot and its subscription), and a stream opened
 * while stopping ends at once. Before this, a server with the built-in samples and a connected console lingered after SIGTERM until the
 * graceful shutdown's phase timeout.
 */
class LiveStreamsOnStopTest {

    @RestController
    static class Endless {
        final AtomicBoolean completed = new AtomicBoolean();

        @GetMapping("/endless")
        SseEmitter endless() {
            SseEmitter e = new SseEmitter(0L);         // no timeout: ends only when the client leaves, as every live stream
            e.onCompletion(() -> completed.set(true));
            return e;
        }
    }

    @Configuration
    @EnableWebMvc
    static class Web {
        @Bean
        LiveStreamsOnStop liveStreamsOnStop() {
            return new LiveStreamsOnStop();
        }

        @Bean
        Endless endless() {
            return new Endless();
        }
    }

    @Test
    void theStopEndsEveryOpenStreamAndAnyOpenedAfterwards() throws Exception {
        try (AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext()) {
            ctx.setServletContext(new MockServletContext());
            ctx.register(Web.class);
            ctx.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(ctx).build();
            LiveStreamsOnStop stop = ctx.getBean(LiveStreamsOnStop.class);
            Endless endless = ctx.getBean(Endless.class);

            MvcResult open = mvc.perform(get("/endless")).andExpect(request().asyncStarted()).andReturn();
            assertThat(stop.open()).isEqualTo(1);
            assertThatThrownBy(() -> open.getAsyncResult(100)).as("still open before the stop").isInstanceOf(IllegalStateException.class);

            stop.stop();
            assertThat(open.getAsyncResult(2000)).as("the stop completes the open stream").isNull();
            mvc.perform(asyncDispatch(open));
            assertThat(endless.completed.get()).as("the stream's completion callbacks ran (its producer cleans up)").isTrue();
            assertThat(stop.open()).isZero();

            endless.completed.set(false);
            MvcResult late = mvc.perform(get("/endless")).andReturn();
            mvc.perform(asyncDispatch(late));
            assertThat(endless.completed.get()).as("a stream opened while stopping ends at once").isTrue();
            assertThat(stop.open()).isZero();
        }
    }

    @Test
    void itStopsAfterTheNodeDrainAndBeforeTheGracefulShutdown() {
        assertThat(LiveStreamsOnStop.PHASE).isLessThan(com.ash.drishti.server.node.NodeDrainOnStop.PHASE)   // a higher phase stops first
                .isGreaterThan(WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE);
        assertThat(new LiveStreamsOnStop().getPhase()).isEqualTo(LiveStreamsOnStop.PHASE);
    }
}
