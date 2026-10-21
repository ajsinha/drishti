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
package com.ash.drishti.server.ui;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The render API (docs/architecture/UI_BOARD.md 6.1 and 6.2): {@code POST /api/v1/ui/render} (an unsaved board), {@code GET
 * /api/v1/ui/boards/{name}/render} (a saved one), {@code POST /api/v1/ui/renders/{id}/pins} and {@code DELETE /api/v1/ui/renders/{id}}. The renders are
 * {@code text/event-stream}: {@code render}, {@code layout}, a {@code chip} per chip, {@code frame}s of live chips, {@code done}, and {@code : hb} every
 * 15 s while the session stays open for pin changes. With {@code stream: once} the stream ends at {@code done} (hosts without pin changes, the CLI).
 */
@RestController
@RequestMapping("/api/v1/ui")
public class UiRenderController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RenderService renders;
    private final java.util.concurrent.ScheduledExecutorService heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "drishti-ui-render-hb");
        t.setDaemon(true);
        return t;
    });

    public UiRenderController(RenderService renders) {
        this.renders = renders;
    }

    /** One render's stream. */
    private final class Stream {
        final SseEmitter emitter = new SseEmitter(0L);
        final boolean once;
        volatile String renderId;
        volatile java.util.concurrent.ScheduledFuture<?> hb;

        Stream(boolean once) {
            this.once = once;
            emitter.onCompletion(this::closed);
            emitter.onTimeout(this::closed);
            emitter.onError(e -> closed());
        }

        synchronized void send(String name, Object data) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                closed();
            }
        }

        void end() {
            try {
                emitter.complete();
            } catch (IllegalStateException e) {
                // already complete
            }
        }

        void closed() {
            if (hb != null) {
                hb.cancel(false);
            }
            if (renderId != null) {
                renders.forget(renderId);
            }
        }

        SseEmitter started(RenderService.Started s) {
            renderId = s.render().id();
            if (once) {
                s.done().whenComplete((o, e) -> {
                    renders.forget(renderId);
                    end();
                });
            } else {
                hb = heartbeat.scheduleWithFixedDelay(() -> {
                    synchronized (this) {
                        try {
                            emitter.send(SseEmitter.event().comment("hb"));
                        } catch (IOException | IllegalStateException e) {
                            closed();
                        }
                    }
                }, 15, 15, TimeUnit.SECONDS);
            }
            return emitter;
        }
    }

    @PostMapping("/render")
    public SseEmitter render(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        JsonNode b = body.path("board");
        if (b.isMissingNode() || b.isNull()) {
            throw new DrishtiException(ErrorCode.UI_DOCUMENT_INVALID, "the request needs 'board': the board document (an object, or its YAML as text)");
        }
        String text = b.isTextual() ? b.asText() : b.toString();
        Stream s = new Stream("once".equals(body.path("stream").asText()));
        return s.started(renders.dynamic(p, text, params(body.path("params")), body.path("device").asText("desktop"), s::send, s::end));
    }

    @GetMapping("/boards/{name}/render")
    public SseEmitter renderSaved(@PathVariable String name, @RequestParam(required = false) String owner, @RequestParam(required = false) String workspace,
            @RequestParam(required = false, defaultValue = "desktop") String device, @RequestParam(required = false) String stream,
            @RequestParam Map<String, String> query, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Map<String, Object> params = new LinkedHashMap<>();
        query.forEach((k, v) -> {
            if (k.startsWith("params.")) {
                params.put(k.substring("params.".length()), v);
            }
        });
        BoardStore.Ref ref = workspace != null && !workspace.isBlank() ? new BoardStore.Ref(BoardStore.WORKSPACE, workspace, name)
                : new BoardStore.Ref(BoardStore.USER, owner == null || owner.isBlank() ? p.user() : owner, name);
        Stream s = new Stream("once".equals(stream));
        return s.started(renders.saved(p, ref, params, device, s::send, s::end));
    }

    /** {@code {instance, pin, value}}: answers the pass's counts once the chips downstream are painted (their events go on the render's stream). */
    @PostMapping("/renders/{renderId}/pins")
    public Map<String, Object> pin(@PathVariable String renderId, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Object value = body.has("value") && !body.get("value").isNull() ? JSON.convertValue(body.get("value"), Object.class) : null;
        try {
            BoardRender.Outcome o = renders.pin(p, renderId, body.path("instance").asText(""), body.path("pin").asText(""), value)
                    .get(renders.settings().boardBudget().toMillis() + 5_000, TimeUnit.MILLISECONDS);
            return Map.of("renderId", renderId, "chips", o.chips(), "errors", o.errors(), "ms", o.ms());
        } catch (TimeoutException | ExecutionException e) {
            throw new DrishtiException(ErrorCode.UI_SUPPLIER_TIMEOUT, "the chips downstream were not painted in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DrishtiException(ErrorCode.UI_RENDER_ENDED, "stopped");
        }
    }

    @DeleteMapping("/renders/{renderId}")
    public Map<String, Object> end(@PathVariable String renderId, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        renders.end(p, renderId);
        return Map.of("renderId", renderId, "ended", true);
    }

    private static Map<String, Object> params(JsonNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (n != null && n.isObject()) {
            n.fields().forEachRemaining(e -> m.put(e.getKey(), JSON.convertValue(e.getValue(), Object.class)));
        }
        return m;
    }
}
