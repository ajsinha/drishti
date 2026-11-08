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
package com.ash.drishti.server.pivot;

import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.bicompute.core.ScaleOptions;
import com.ash.drishti.bicompute.live.LivePivots;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.net.PeerCalls;
import com.ash.drishti.server.security.Principal;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live pivots (PIVOT_GUIDE.md, live pivots; API_REFERENCE.md, the pivot engine), the same subscribe-then-stream shape as BI's standing queries:
 *
 * <ul>
 *   <li>{@code POST /api/v1/pivot/live} {@code {view, exact, rollups}}: the live view, its first answer, its id and the node that holds it
 *       (a console opens the stream on that node); refused in words past a limit ({@code DRS-5721});</li>
 *   <li>{@code GET /api/v1/pivot/live/{id}/events}: server-sent events: {@code answer} (the current answer, first), then {@code patch}
 *       (only the cells that moved), {@code reshape} (the whole answer when members came or went), {@code status} (the heartbeat, and a
 *       check that found nothing moved), {@code error}, {@code ended};</li>
 *   <li>{@code GET /api/v1/pivot/live}: the caller's live views; {@code DELETE /api/v1/pivot/live/{id}}: ends one.</li>
 * </ul>
 *
 * A live view is its person's: only they stream or end it. Starting and ending are audited; every figure is the engine's.
 */
@RestController
public class PivotLiveController {

    private final PivotController pivot;
    private final PivotLiveConfiguration.Live live;
    private final AuditLog audit;
    private final ObjectProvider<PeerCalls> peers;

    public PivotLiveController(PivotController pivot, PivotLiveConfiguration.Live live, AuditLog audit, ObjectProvider<PeerCalls> peers) {
        this.pivot = pivot;
        this.live = live;
        this.audit = audit;
        this.peers = peers;
    }

    @PostMapping("/api/v1/pivot/live")
    public Map<String, Object> start(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        Viewer v = pivot.on(p);
        PivotView view = pivot.view(p, body);
        Map<String, Object> out = new LinkedHashMap<>(live.pivots().subscribe(v, view, ScaleOptions.parse(body)));
        live.topics().follow(view.source(), live.pivots());
        PeerCalls calls = peers.getIfAvailable();
        out.put("node", calls == null ? "" : calls.self());
        if (body.get("view") instanceof String id) {
            out.put("savedView", id);
        }
        audit.record(p.user(), "pivot.live.start", view.source(), out.get("id") + "; rows=" + view.rows() + "; columns=" + view.columns()
                + "; options=" + out.get("options"));
        return out;
    }

    @GetMapping("/api/v1/pivot/live")
    public Map<String, Object> mine(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        pivot.on(p);
        return Map.of("live", live.pivots().list(p.user()), "limits", live.pivots().settings());
    }

    @GetMapping(path = "/api/v1/pivot/live/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SuppressWarnings("unchecked")
    public SseEmitter events(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String id) {
        pivot.on(p);
        SseEmitter emitter = new SseEmitter(0L);
        Consumer<LivePivots.Event>[] sink = new Consumer[1];
        sink[0] = e -> {
            try {
                emitter.send(SseEmitter.event().name(e.name()).data(e.data(), MediaType.APPLICATION_JSON));
                if ("ended".equals(e.name())) {
                    emitter.complete();
                }
            } catch (IOException | IllegalStateException x) {
                throw new java.io.UncheckedIOException(x instanceof IOException io ? io : new IOException(x));
            }
        };
        Runnable gone = () -> live.pivots().detach(p.user(), id, sink[0]);
        emitter.onCompletion(gone);
        emitter.onTimeout(gone);
        emitter.onError(x -> gone.run());
        live.pivots().attach(p.user(), id, sink[0]);     // not hers, or ended: refused before the stream opens (DRS-5704)
        return emitter;
    }

    @DeleteMapping("/api/v1/pivot/live/{id}")
    public ResponseEntity<Void> end(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @PathVariable String id) {
        pivot.on(p);
        if (!live.pivots().end(p.user(), id)) {
            throw new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "no live pivot " + id + " of " + p.user() + " on this server");
        }
        audit.record(p.user(), "pivot.live.end", id, "");
        return ResponseEntity.noContent().build();
    }
}
