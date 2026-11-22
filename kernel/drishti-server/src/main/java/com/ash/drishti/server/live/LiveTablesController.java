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
package com.ash.drishti.server.live;

import com.ash.drishti.kernel.api.security.Principal;
import com.ash.rachana.data.ProtocolError;
import com.ash.rachana.data.ProtocolHost;
import com.ash.rachana.data.Subscription;
import com.ash.rachana.data.TableProvider;
import com.ash.rachana.live.LiveTables;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
 * The Data Protocol mount of Drishti's live tables (LIVE_TABLES 4 and 11 step 1): Rachana's {@link ProtocolHost} over {@link LiveTables}
 * at {@code /api/v1/rdp}, the signed-in {@link Principal} as the caller every provider call is made for. Routes are the protocol's:
 * {@code GET /} and {@code /tables}, {@code GET /tables/{name}} (the schema for this reader: masks, {@code write}, {@code live}),
 * {@code GET /tables/{name}/rows} and {@code POST /tables/{name}/query} (a page; a FORM's search is a query with a filter on its kind's
 * table), {@code POST /tables/{name}/write} (an edit, checked by the guard and handed to the write-back as the person),
 * {@code GET|POST /changes} (the minimal SSE stream; a windowed one starts with {@code subd}) and {@code POST /changes/{sub}/window}
 * (only the stream's own caller). A bare mnemonic's table ({@code kind-<kind>}) is declared from its contract on first use; a search
 * ({@code GET /searches?q=}: a FORM's result, the search page's) is opened as a query on a table ({@link FormResults}).
 */
@RestController
@RequestMapping(LiveTablesController.BASE)
public class LiveTablesController {

    /** The Data Protocol base of this server. */
    public static final String BASE = "/api/v1/rdp";

    private final LiveTables tables;
    private final ProtocolHost host;
    private final ObjectMapper json;
    private final Map<String, Open> streams = new ConcurrentHashMap<>();

    private record Open(Subscription sub, String user) {}

    private final FormResults forms;

    private final com.ash.drishti.engine.command.Mnemonics mnemonics;
    private final com.ash.drishti.engine.source.SourceRouter router;

    public LiveTablesController(LiveTables tables, FormResults forms, com.ash.drishti.engine.command.Mnemonics mnemonics,
            com.ash.drishti.engine.source.SourceRouter router, ObjectMapper json) {
        this.router = router;
        this.tables = tables;
        this.forms = forms;
        this.mnemonics = mnemonics;
        this.host = new ProtocolHost(BASE, tables);
        this.json = json;
    }

    /** The table of a kind (its own declaration of that name, else the kind's default one), declared on first use: {@code {table}}. */
    @GetMapping("/kinds/{kind}")
    public Map<String, Object> kindTable(@PathVariable("kind") String word, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        // a mnemonic (POS) or a kind (position)
        String kind = mnemonics.of(word.toUpperCase(java.util.Locale.ROOT)).map(m -> m.kind()).orElse(word);
        String name = tables.has(kind) ? kind : DrishtiLiveTables.defaultTable(tables, kind).orElseThrow(() -> ProtocolError.of("rdp.table.unknown", 404,
                kind + " has no columns to list", "table", kind));
        tables.schemaDoc(name, principal);                    // a reader who may not read it is refused here
        return Map.of("table", name);
    }

    /** A refusal of the protocol answered as the protocol says: its status and {@code {error: {code, message, ...}}}. */
    @org.springframework.web.bind.annotation.ExceptionHandler(ProtocolError.class)
    public ResponseEntity<Object> refused(ProtocolError e) {
        return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON).body(e.body());
    }

    /**
     * A search as a query on a table (a FORM's result, the search page's), on the person's business date:
     * {@code {table, query, live, page, search}} ({@link FormResults#answer}). The page's next ones and the changes are the protocol's.
     */
    @GetMapping("/searches")
    public ResponseEntity<Object> search(@RequestParam String q, com.ash.drishti.api.AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        return ResponseEntity.ok(forms.answer(q, asOf, principal));
    }

    /** The changes of a table: the minimal SSE form (PROTOCOL 8.2, 16). */
    @RequestMapping(path = "/changes", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object changes(@RequestParam(required = false) String table, @RequestParam(required = false) String after,
            @RequestParam(required = false) Long everyMs, @RequestBody(required = false) Map<String, Object> body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal, HttpServletRequest request) {
        Map<String, Object> b = body == null ? Map.of() : body;
        String name = b.get("table") instanceof String t ? t : table;
        String from = b.get("after") instanceof String a ? a : after != null ? after : request.getHeader("Last-Event-ID");
        long every = b.get("conflate") instanceof Map<?, ?> c && c.get("everyMs") instanceof Number n ? n.longValue() : everyMs == null ? 0 : everyMs;
        List<List<Object>> window = null;
        boolean top = false;
        if (b.get("window") instanceof Map<?, ?> w) {
            window = ProtocolHost.keys(w.get("keys"));
            top = Boolean.TRUE.equals(w.get("top"));
        }
        SseEmitter emitter = new SseEmitter(0L);
        ReentrantLock sending = new ReentrantLock();
        String id = window == null ? null : UUID.randomUUID().toString();
        Subscription sub;
        try {
            sub = host.subscribe(name, new TableProvider.Subscribe(from, b.get("filter"), every, window, top), f -> send(emitter, sending, f), principal);
        } catch (ProtocolError e) {
            return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON).body(e.body());
        }
        Runnable done = () -> {
            sub.close();
            if (id != null) {
                streams.remove(id);
            }
        };
        emitter.onCompletion(done);
        emitter.onTimeout(done);
        emitter.onError(e -> done.run());
        if (!sub.recovered()) {
            send(emitter, sending, host.changes(name, "-:0", principal).get(0));   // the gap frame at the table's position
            emitter.complete();
            return emitter;
        }
        if (id != null) {
            streams.put(id, new Open(sub, principal.user()));
            Map<String, Object> subd = new LinkedHashMap<>();
            subd.put("t", "subd");
            subd.put("sub", id);
            subd.put("table", name);
            send(emitter, sending, subd);
        }
        return emitter;
    }

    private void send(SseEmitter emitter, ReentrantLock sending, Map<String, Object> f) {
        sending.lock();
        try {
            SseEmitter.SseEventBuilder e = SseEmitter.event().name(String.valueOf(f.get("t"))).data(f, MediaType.APPLICATION_JSON);
            if (f.get("epoch") != null) {
                e.id(f.get("epoch") + ":" + f.get("seq"));
            }
            emitter.send(e);
        } catch (IOException | IllegalStateException e) {
            throw new IllegalStateException("the reader has gone", e);
        } finally {
            sending.unlock();
        }
    }

    /** The window of a windowed stream: {@code {keys, top?}}; only its own caller may change it. */
    @PostMapping("/changes/{sub}/window")
    public ResponseEntity<Object> window(@PathVariable String sub, @RequestBody Map<String, Object> body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        Open o = streams.get(sub);
        if (o == null || !Objects.equals(o.user(), principal.user()) || o.sub().closed() || !o.sub().windowed()) {
            return ResponseEntity.status(404).body(ProtocolError.of("rdp.table.unknown", 404, "no windowed stream '" + sub + "' of yours",
                    "operation", "window").body());
        }
        o.sub().window(ProtocolHost.keys(body.get("keys")), Boolean.TRUE.equals(body.get("top")));
        return ResponseEntity.noContent().build();
    }

    /** Every other route of the protocol, answered by the host as the person (a write is handed to the write-back as them). */
    @RequestMapping(path = {"", "/", "/tables", "/tables/**"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> route(HttpServletRequest request, @RequestParam Map<String, String> params,
            @RequestBody(required = false) Object body, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        String rel = request.getRequestURI().substring(request.getContextPath().length() + BASE.length());
        rel = java.net.URLDecoder.decode(rel, java.nio.charset.StandardCharsets.UTF_8);
        Object parsed = body == null ? null : json.convertValue(body, Object.class);
        String path = rel;
        ProtocolHost.Answer a = DrishtiChangeSink.as(principal, () -> host.handle(principal, request.getMethod(), path, params, parsed));
        if (a.status() == 200 && "GET".equals(request.getMethod()) && path.matches("/tables/[^/]+") && a.body() instanceof Map<?, ?> m) {
            // a table's schema carries Drishti's profile (its kind and links) and whether it has a tray: the grid's record panel reads them
            com.ash.rachana.table.LiveTableSpec spec = DrishtiLiveTables.spec(tables, path.substring("/tables/".length()));
            if (spec != null) {
                Map<String, Object> doc = new LinkedHashMap<>();
                m.forEach((k, v) -> doc.put(String.valueOf(k), v));
                doc.put("profile", spec.profile());
                doc.put("tray", spec.tray() && spec.edit() != null);
                java.util.Set<String> off = router.excluded(DrishtiAuthoriser.kind(spec));
                if (!off.isEmpty()) {                         // the trading systems switched off that feed the kind: the page says so
                    doc.put("excludes", new java.util.TreeSet<>(off));
                }
                if (!doc.containsKey("write")) {
                    doc.put("readOnly", DrishtiLiveTables.readOnly(tables, spec.name(), principal));
                }
                return ResponseEntity.ok(doc);
            }
        }
        return ResponseEntity.status(a.status()).body(a.body());
    }
}
