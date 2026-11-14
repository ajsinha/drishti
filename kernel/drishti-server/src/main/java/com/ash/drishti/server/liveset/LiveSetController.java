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
package com.ash.drishti.server.liveset;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.server.security.Entitlements;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.MediaType;
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
 * The live result sets' API (docs/guides/API_REFERENCE.md, {@code /live}): the declarations a person may open, a page of a set's rows,
 * one record whole (the panel's Raw JSON), the stream of changes (server-sent events: {@code hello}, then {@code rows} or {@code resync}),
 * the page a stream shows, and a blotter's edits. Every answer is made for a reader of the caller ({@link LiveSets#reader}): kept to their
 * row scope and masked as they may see it, a search's set read only by the audience it was filled for; nothing is kept per person beyond
 * the caller's own open streams.
 */
@RestController
@RequestMapping("/api/v1/live")
public class LiveSetController {

    private static final int MAX_PAGE = 5_000;

    private final LiveSets sets;
    private final Entitlements entitlements;
    private final Map<String, Open> streams = new ConcurrentHashMap<>();

    /** One open stream: whose it is, its set and its window. */
    private record Open(String user, LiveSet set, LiveSet.Window window) {}

    public LiveSetController(LiveSets sets, Entitlements entitlements) {
        this.sets = sets;
        this.entitlements = entitlements;
    }

    /** The declarations the caller may open (a kind they may open), and for administrators the ones set aside. */
    @GetMapping
    public Map<String, Object> list(@RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sets", sets.specs().stream().filter(s -> entitlements.mayOpen(principal, s.kind())).map(s -> described(s, principal)).toList());
        if (principal.roles().contains("admin")) {
            out.put("problems", sets.problems());
            out.put("open", sets.describe());
        }
        return out;
    }

    private com.ash.drishti.server.edition.EditionCommands edition = com.ash.drishti.server.edition.EditionCommands.NONE;

    /** The edition's command allow-list (a kind it leaves out is refused as by the search). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEdition(com.ash.drishti.server.edition.EditionCommands edition) {
        this.edition = edition;
    }

    /**
     * A search's matches as a live result set (a FORM's result, the search page's): {@code q} is the search ({@code TRD where CURRENCY =
     * 'EUR' limit 100}), run on the request's business date as the caller may see it. Answers a page as {@code /{id}/rows} does, plus
     * {@code search} (what the search said: columns, labels, matched, scanned, partial, failed, noData, pivot); {@code set.id} streams its
     * changes ({@code /{id}/stream}) while {@code live} is true (the live business date and a kind with a {@code live:} binding).
     * {@code again=true} (the search pressed again) reads a static set afresh.
     */
    @GetMapping("/query")
    public Map<String, Object> query(@RequestParam String q, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "1000") int limit,
            @RequestParam(required = false) String sort, @RequestParam(defaultValue = "false") boolean descending,
            @RequestParam(required = false) String filter, @RequestParam(defaultValue = "false") boolean again, com.ash.drishti.api.AsOf asOf,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        String kind = sets.kindOf(q);
        entitlements.requireOpen(principal, kind);
        edition.requireKind(kind);
        LiveReader reader = sets.reader(principal, kind);
        LiveSet set = sets.query(q, asOf, principal, reader, again);
        Map<String, Object> out = page(set, set.spec(), offset, limit, sort, descending, filter, principal);
        out.put("search", origin(set, reader));
        return out;
    }

    /** What the search that filled the set said, as the reader may see it: which node answered what for administrators only. */
    private static Map<String, Object> origin(LiveSet set, LiveReader reader) {
        Map<String, Object> o = set.origin();
        if (reader.admin() || !o.containsKey("nodes")) {
            return o;
        }
        Map<String, Object> out = new LinkedHashMap<>(o);
        out.remove("nodes");
        return out;
    }

    /**
     * A page of a kind's own set ({@code POS <GO>}): {@code word} is its mnemonic or its name; the set is its module's declaration, else the
     * default made from its contract. Answers as {@code /{id}/rows}, plus {@code capped} when the kind holds more records than a default
     * set reads ({@code drishti.live-sets.default-max-rows}).
     */
    @GetMapping("/kinds/{word}/rows")
    public Map<String, Object> kindRows(@PathVariable String word, @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit, @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "false") boolean descending, @RequestParam(required = false) String filter,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        String kind = sets.kindOf(word);
        entitlements.requireOpen(principal, kind);
        edition.requireKind(kind);
        LiveSetSpec spec = sets.forKind(kind);
        LiveSet set = sets.open(spec, null);
        Map<String, Object> out = page(set, spec, offset, limit, sort, descending, filter, principal);
        out.put("capped", set.capped());
        return out;
    }

    /** A page of a set's rows: {@code offset}, {@code limit} (at most 5,000), {@code sort}, {@code descending}, {@code filter}, {@code query}. */
    @GetMapping("/{id}/rows")
    public Map<String, Object> rows(@PathVariable String id, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "500") int limit,
            @RequestParam(required = false) String sort, @RequestParam(defaultValue = "false") boolean descending,
            @RequestParam(required = false) String filter, @RequestParam(required = false) String query,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        LiveSetSpec spec = spec(id, principal);
        LiveSet set = sets.open(spec, query);
        Map<String, Object> out = page(set, spec, offset, limit, sort, descending, filter, principal);
        if (!set.origin().isEmpty()) {
            out.put("search", origin(set, sets.reader(principal, spec.kind())));
        }
        return out;
    }

    private Map<String, Object> page(LiveSet set, LiveSetSpec spec, int offset, int limit, String sort, boolean descending, String filter,
            Principal principal) {
        LiveSet.Page page = set.page(offset, Math.min(MAX_PAGE, Math.max(1, limit)), sort, descending, filter, sets.reader(principal, spec.kind()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("set", described(spec, principal));
        out.put("live", set.live());
        out.put("total", page.total());
        out.put("seq", page.seq());
        out.put("offset", offset);
        out.put("rows", page.rows());
        return out;
    }

    /** One record whole, as the caller may see it (the panel's Raw JSON view); its version and sequence. */
    @GetMapping("/{id}/rows/{entity}")
    public Map<String, Object> row(@PathVariable String id, @PathVariable String entity, @RequestParam(required = false) String query,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        LiveSetSpec spec = spec(id, principal);
        LiveReader reader = sets.reader(principal, spec.kind());
        Map<String, Object> row = new java.util.LinkedHashMap<>(sets.open(spec, query).row(entity, reader)
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, spec.kind() + " " + entity + " is not in " + id)));
        // Raw JSON is the source's native record, before its connector's map and set (the grid shows the canonical fields)
        sets.nativeRecord(spec.kind(), entity).ifPresent(n -> {
            row.put("canonical", row.get("doc"));
            row.put("doc", reader.redact().apply(n.data()).unwrap());
            row.put("source", n.provenance() == null ? null : n.provenance().source());
        });
        return row;
    }

    /**
     * The changes of a set as they happen: {@code hello} ({@code stream}, {@code total}, {@code seq}, {@code live}), then {@code rows}
     * ({@code upserts}, {@code removes}, {@code total}, {@code seq}) every flush, or {@code resync} when the viewer fell too far behind.
     */
    @GetMapping(path = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id, @RequestParam(required = false) String query,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        LiveSetSpec spec = spec(id, principal);
        LiveSet set = sets.open(spec, query);
        LiveReader reader = sets.reader(principal, spec.kind());
        SseEmitter emitter = new SseEmitter(0L);
        String sid = UUID.randomUUID().toString();
        ReentrantLock sending = new ReentrantLock();
        LiveSet.Window w = set.window(reader, ev -> {
            sending.lock();
            try {
                emitter.send(SseEmitter.event().name(String.valueOf(ev.get("type"))).data(ev, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                throw new IllegalStateException("the viewer has gone", e);
            } finally {
                sending.unlock();
            }
        }, 5_000);
        streams.put(sid, new Open(principal.user(), set, w));
        Runnable done = () -> {
            set.closeWindow(w);
            streams.remove(sid);
        };
        emitter.onCompletion(done);
        emitter.onTimeout(done);
        emitter.onError(e -> done.run());
        try {
            emitter.send(SseEmitter.event().name("hello").data(Map.of("stream", sid, "total", set.total(reader), "live", set.live()), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            done.run();
        }
        return emitter;
    }

    /** The page a stream shows ({@code ids}); {@code all: true} when the viewer holds every row. Only the stream's owner may set it. */
    @PostMapping("/{id}/stream/{stream}/window")
    public Map<String, Object> window(@PathVariable String id, @PathVariable String stream, @RequestBody JsonNode body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        Open o = streams.get(stream);
        if (o == null || !o.user().equals(principal.user()) || !o.set().spec().id().equals(id)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no stream " + stream);
        }
        if (body.path("all").asBoolean(false)) {
            o.window().paged(null);
        } else {
            Set<String> ids = new HashSet<>();
            body.path("ids").forEach(n -> ids.add(n.asText()));
            o.window().paged(ids);
        }
        return Map.of("stream", stream, "paged", !body.path("all").asBoolean(false));
    }

    /** A blotter's edit: {@code {entity, items: [{field, from, to}], basedOn, reason}}; 409 when the record moved since it was seen. */
    @PostMapping("/{id}/edit")
    public Map<String, Object> edit(@PathVariable String id, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal principal) {
        spec(id, principal);
        List<Map<String, Object>> changes = sets.edit(id, principal, body);
        return Map.of("changes", changes);
    }

    /** The declaration as this person sees it: editable only when the site and their rights allow it, and why not. */
    private Map<String, Object> described(LiveSetSpec spec, Principal principal) {
        Map<String, Object> m = spec.describe();
        java.util.Set<String> off = sets.excluded(spec.kind());
        if (!off.isEmpty()) {
            // a page of the kind says which source systems it leaves out: "excludes ENDUR (switched off)"
            m.put("excludes", off);
            m.put("banner", "excludes " + String.join(", ", off) + " (switched off)");
        }
        String no = sets.readOnly(principal, spec);
        m.put("editable", no == null);
        m.put("readOnly", no);
        if (no != null) {
            m.remove("fields");
        }
        return m;
    }

    private LiveSetSpec spec(String id, Principal principal) {
        String aside = sets.aside(id);
        if (aside != null && sets.spec(id).isEmpty()) {
            // set aside at load: refused with the reason (Admin lists the same)
            throw new DrishtiException(ErrorCode.LIVE_SET_INVALID, "the live set " + id + " was set aside at load: " + aside);
        }
        LiveSetSpec spec = sets.spec(id).orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no live set " + id));
        entitlements.requireOpen(principal, spec.kind());
        // a search's set is filled for one audience (roles, masks, row scope, the nodes they reach): no one else reads it
        sets.requireAudience(id, sets.reader(principal, spec.kind()));
        return spec;
    }
}
