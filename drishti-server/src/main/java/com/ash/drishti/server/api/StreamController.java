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

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.live.Frame;
import com.ash.drishti.engine.live.LiveMetrics;
import com.ash.drishti.engine.live.LiveProperties;
import com.ash.drishti.engine.live.TopicHub;
import com.ash.drishti.engine.live.ViewStream;
import com.ash.drishti.engine.view.PanelData;
import com.ash.drishti.engine.view.ViewModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live views over Server-Sent Events. A stream opens with a {@code view} event (the full ViewModel) and
 * then carries {@code frame} events of patches. Each client has its own writer (a virtual thread) and a
 * latest-wins mailbox, so a slow client never slows the others. A reconnecting client simply receives a
 * fresh {@code view} event, so nothing is lost across reconnects.
 */
@RestController
@RequestMapping("/api/v1")
public class StreamController {

    private final ViewPipeline pipeline;
    private final TopicHub hub;
    private final LiveMetrics metrics;
    private final LiveProperties props;
    private final ExecutorService executor;
    private final AtomicInteger open = new AtomicInteger();

    public StreamController(ViewPipeline pipeline, TopicHub hub, LiveMetrics metrics, LiveProperties props,
            ExecutorService drishtiVirtualExecutor) {
        this.pipeline = pipeline;
        this.hub = hub;
        this.metrics = metrics;
        this.props = props;
        this.executor = drishtiVirtualExecutor;
    }

    @GetMapping(path = "/views/{kind}/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String kind, @PathVariable String id) {
        if (open.get() >= props.maxStreams()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "too many live streams on this server");
        }
        EntityRef ref = EntityRef.of(kind, id);
        ViewModel initial = pipeline.view(ref);
        SseEmitter emitter = new SseEmitter(0L);
        FrameMailbox box = new FrameMailbox();
        ViewStream stream = new ViewStream(ref, initial, chartSources(initial), hub, pipeline, executor, metrics, box::offer);
        open.incrementAndGet();
        Runnable closeAll = () -> {
            stream.close();
            open.decrementAndGet();
        };
        executor.execute(() -> write(emitter, initial, box, closeAll));
        return emitter;
    }

    /** Entities whose changes also change this view: the sources of its charts. */
    static List<EntityRef> chartSources(ViewModel v) {
        List<EntityRef> out = new ArrayList<>();
        for (ViewModel.PanelView p : v.panels()) {
            if (p.data() instanceof PanelData.Chart c && c.source() != null) {
                out.add(EntityRef.of(c.source().kind(), c.source().id()));
            }
        }
        return out;
    }

    private void write(SseEmitter emitter, ViewModel initial, FrameMailbox box, Runnable closeAll) {
        try {
            emitter.send(SseEmitter.event().name("view").id("0").data(initial, MediaType.APPLICATION_JSON));
            long heartbeat = props.heartbeat().toMillis();
            while (!Thread.currentThread().isInterrupted()) {
                Frame f = box.take(heartbeat);
                if (f == null) {
                    emitter.send(SseEmitter.event().comment("hb"));
                } else {
                    emitter.send(SseEmitter.event().name("frame").id(Long.toString(f.seq())).data(f, MediaType.APPLICATION_JSON));
                }
            }
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } finally {
            closeAll.run();
        }
    }

    @GetMapping("/health/live")
    public Map<String, Object> live() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("streams", open.get());
        m.put("topics", hub.topicCount());
        m.put("frames", metrics.frames());
        m.put("p50Ms", metrics.percentile(50));
        m.put("p99Ms", metrics.percentile(99));
        return m;
    }

    int openStreams() {
        return open.get();
    }

}
