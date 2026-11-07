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
package com.ash.drishti.server.trading;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The trader home's live ticks (docs/architecture/SHARED_SCREENS.md section 4; docs/guides/API_GUIDE.md, "The trader home"), in the trading
 * domain: {@code GET /api/v1/trading/home/live} is a stream of {@code hello} (the state now), {@code tick} whenever a kind the home is built
 * from has new data (at most one per {@code drishti.trading.home.frame}: a slow browser gets the latest state, never a queue) and
 * {@code status} every {@code drishti.trading.home.status} when nothing changed; {@code GET /api/v1/trading/home/state} is the same state
 * once. A tick carries no data: the console asks the trader home's figures again as the person (their books, row rules and masks), and
 * every figure is computed on the server (the VaR engine, the pivot engine), never in the browser. For a person who may open trades.
 */
@RestController
@RequestMapping("/api/v1/trading/home")
public class TradingHomeController {

    private final TradingHomeWatch watch;
    private final TradingHomeProperties props;
    private final Entitlements entitlements;
    private final ExecutorService executor;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, AtomicInteger> perUser = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public TradingHomeController(SourceRouter router, TradingHomeProperties props, Entitlements entitlements, ExecutorService drishtiVirtualExecutor) {
        this(new TradingHomeWatch(props.watch(), k -> router.dataVersion(k, AsOf.LATEST)), props, entitlements, drishtiVirtualExecutor);
    }

    /** With a watch of its own (tests). */
    TradingHomeController(TradingHomeWatch watch, TradingHomeProperties props, Entitlements entitlements, ExecutorService executor) {
        this.watch = watch;
        this.props = props;
        this.entitlements = entitlements;
        this.executor = executor;
    }

    /** The state now: the generation, the kinds watched and their versions. */
    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> state(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        allowed(p);
        TradingHomeWatch.State s = watch.read();
        Map<String, Object> out = s.describe(List.of(), System.currentTimeMillis());
        out.put("versions", s.versions());
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(out);
    }

    /** The stream: hello, then tick on new data, status as the heartbeat. */
    @GetMapping(path = "/live", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter live(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        allowed(p);
        AtomicInteger mine = perUser.computeIfAbsent(p.user(), u -> new AtomicInteger());
        if (mine.incrementAndGet() > props.maxStreamsPerUser()) {
            mine.decrementAndGet();
            throw new DrishtiException(ErrorCode.LIVE_STREAM, "too many trader home streams are open for you (drishti.trading.home.max-streams-per-user "
                    + props.maxStreamsPerUser() + "): close a tab of the home");
        }
        SseEmitter emitter = new SseEmitter(0L);
        executor.execute(() -> pump(emitter, mine));
        return emitter;
    }

    private void pump(SseEmitter emitter, AtomicInteger mine) {
        try {
            TradingHomeWatch.State sent = watch.read();
            emitter.send(SseEmitter.event().name("hello").data(json.writeValueAsString(sent.describe(List.of(), System.currentTimeMillis()))));
            long quiet = 0;
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(props.frame().toMillis());
                TradingHomeWatch.State now = watch.read();
                quiet += props.frame().toMillis();
                if (!now.generation().equals(sent.generation())) {
                    List<String> changed = now.changedSince(sent);
                    sent = now;
                    quiet = 0;
                    emitter.send(SseEmitter.event().name("tick").data(json.writeValueAsString(now.describe(changed, System.currentTimeMillis()))));
                } else if (quiet >= props.status().toMillis()) {
                    quiet = 0;
                    emitter.send(SseEmitter.event().name("status").data(json.writeValueAsString(now.describe(List.of(), System.currentTimeMillis()))));
                }
            }
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);                         // the browser went away
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.complete();
        } catch (RuntimeException e) {
            emitter.completeWithError(e);
        } finally {
            mine.decrementAndGet();
        }
    }

    private void allowed(Principal p) {
        if (entitlements != null) {
            entitlements.requireOpen(p, "trade");                 // the home is about trades: a person who may not open them has no home here
        }
    }
}
