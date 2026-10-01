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
package com.ash.drishti.server.layout;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.rachana.RachanaProperties;
import com.ash.drishti.rachana.SutraLayoutEditor;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.governance.Proposal;
import com.ash.drishti.server.governance.SutraGovernance;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Personal layouts: the caller's arrangement of a Sutra's panels, kept per user and per Sutra (and the kind it lays
 * out) in the preference store (namespace {@code layouts}), never in the Sutra. The console applies a layout when it
 * draws a view for its owner. An author may turn their layout into the next version of the Sutra, proposed for review
 * as a Studio save is ({@code /promotion}).
 */
@RestController
@RequestMapping("/api/v1/me/layouts")
public class LayoutController {

    static final String NS = "layouts";

    private final LayoutProperties props;
    private final Entitlements entitlements;
    private final PreferenceStore store;
    private final SutraRegistry sutras;
    private final SutraGovernance governance;
    private final RachanaProperties rachana;
    private final SutraLayoutEditor editor = new SutraLayoutEditor();

    public LayoutController(LayoutProperties props, Entitlements entitlements, PreferenceStore store, SutraRegistry sutras,
            SutraGovernance governance, RachanaProperties rachana) {
        this.props = props;
        this.entitlements = entitlements;
        this.store = store;
        this.sutras = sutras;
        this.governance = governance;
        this.rachana = rachana;
    }

    /**
     * Whether the caller may customise layouts ({@code allowed}) and promote them ({@code promote}), and every layout
     * they keep, each read against its Sutra as it is now. A layout whose Sutra is gone, or now lays out another kind,
     * is left out.
     */
    @GetMapping
    public Map<String, Object> all(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean allowed = props.enabled() && entitlements.mayLayout(p);
        out.put("enabled", props.enabled());
        out.put("allowed", allowed);
        out.put("promote", allowed && rachana.studioSave() && entitlements.mayAuthor(p));
        out.put("review", governance.enabled());
        List<JsonNode> layouts = new ArrayList<>();
        if (allowed) {
            for (String name : store.keys(p.user(), NS)) {
                store.get(p.user(), NS, name).flatMap(stored -> current(stored)).ifPresent(layouts::add);
            }
        }
        out.put("layouts", layouts);
        return out;
    }

    /** One layout, read against the Sutra as it is now (404 when the caller keeps none for it). */
    @GetMapping("/{sutra}/{kind}")
    public JsonNode get(@PathVariable String sutra, @PathVariable String kind, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        require(p);
        return stored(p, sutra, kind).flatMap(this::current)
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no personal layout for " + sutra + " (" + kind + ")"));
    }

    /**
     * Keeps the caller's layout for a Sutra: {@code {"panels": [{"id": "cashflows", "area": "main", "span": 8, "height": 10},
     * {"id": "built", "hidden": true}, …]}} in display order. Only panel ids the Sutra has; the Sutra must lay out
     * {@code kind}, and the caller must be able to open that kind.
     */
    @PutMapping("/{sutra}/{kind}")
    public JsonNode put(@PathVariable String sutra, @PathVariable String kind, @RequestBody JsonNode body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        require(p);
        Sutra s = sutraFor(sutra, kind);
        entitlements.requireOpen(p, kind);
        ObjectNode doc = LayoutOverlay.validate(body, s, kind);
        store.put(p.user(), NS, s.name(), doc);
        return LayoutOverlay.resolve(doc, s);
    }

    /** Back to the Sutra's layout: forgets the caller's layout for it. */
    @DeleteMapping("/{sutra}/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable String sutra, @PathVariable String kind, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        require(p);
        if (stored(p, sutra, kind).isEmpty() || !store.delete(p.user(), NS, sutra)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no personal layout for " + sutra + " (" + kind + ")");
        }
    }

    /**
     * What promoting the caller's layout would propose: the next version of the Sutra, written from the latest one with
     * the panels in the layout's order, columns and sizes ({@code text}), the latest version's text ({@code base}), and
     * the changes in words. {@code dropHidden=true} also removes the panels the layout hides. Needs the author power.
     */
    @GetMapping("/{sutra}/{kind}/promotion")
    public Map<String, Object> promotion(@PathVariable String sutra, @PathVariable String kind,
            @RequestParam(defaultValue = "false") boolean dropHidden, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        requirePromote(p);
        JsonNode layout = get(sutra, kind, p);
        Sutra s = sutraFor(sutra, kind);
        String base = sutras.source(s.name(), s.version())
                .orElseThrow(() -> new DrishtiException(ErrorCode.SUTRA_NOT_FOUND, s.id()));
        SutraLayoutEditor.Edit edit;
        try {
            edit = editor.apply(base, LayoutOverlay.placements(layout), dropHidden);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "cannot write this layout into " + s.id() + ": " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sutra", s.name());
        out.put("kind", kind);
        out.put("fromVersion", edit.fromVersion());
        out.put("version", edit.version());
        out.put("base", base);
        out.put("text", edit.text());
        out.put("changes", edit.changes());
        out.put("review", governance.enabled());
        out.put("dropHidden", dropHidden);
        return out;
    }

    /**
     * Proposes the promotion ({@code {"note": "…", "dropHidden": false}}): with review on (the default) the next version
     * waits for an approver, as a Studio save does ({@code 202} with the proposal); with review off it goes live.
     */
    @PostMapping("/{sutra}/{kind}/promotion")
    public org.springframework.http.ResponseEntity<Map<String, Object>> promote(@PathVariable String sutra, @PathVariable String kind,
            @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) throws IOException {
        boolean drop = body != null && body.path("dropHidden").asBoolean(false);
        String note = body == null ? "" : body.path("note").asText("").strip();
        Map<String, Object> made = promotion(sutra, kind, drop, p);
        String text = (String) made.get("text");
        String why = note.isEmpty() ? "Promoted from " + p.user() + "'s layout" : note;
        Map<String, Object> out = new LinkedHashMap<>();
        if (governance.enabled()) {
            Proposal pr = governance.propose(text, why.length() > 500 ? why.substring(0, 500) : why, p);
            out.put("proposal", Map.of("id", pr.id(), "name", pr.name(), "version", pr.version(), "status", pr.status()));
            return org.springframework.http.ResponseEntity.accepted().body(out);
        }
        Sutra saved = sutras.save(text);
        out.put("saved", Map.of("name", saved.name(), "version", saved.version()));
        return org.springframework.http.ResponseEntity.ok(out);
    }

    // ----------------------------------------------------------------------------------------------------------

    private Optional<JsonNode> stored(Principal p, String sutra, String kind) {
        return store.get(p.user(), NS, sutra).filter(d -> kind.equals(d.path("kind").asText()));
    }

    /** The stored layout against the latest version of its Sutra, if that Sutra still lays out the stored kind. */
    private Optional<JsonNode> current(JsonNode stored) {
        return sutras.latest(stored.path("sutra").asText(""))
                .filter(s -> s.match().kind().equals(stored.path("kind").asText()))
                .map(s -> LayoutOverlay.resolve(stored, s));
    }

    private Sutra sutraFor(String name, String kind) {
        Sutra s = sutras.latest(name).orElseThrow(() -> new DrishtiException(ErrorCode.SUTRA_NOT_FOUND, "no Sutra '" + name + "'"));
        if (!s.match().kind().equals(kind)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, s.name() + " lays out " + s.match().kind() + " entities, not " + kind);
        }
        return s;
    }

    private void require(Principal p) {
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "personal layouts are switched off on this server (drishti.layouts.enabled)");
        }
        entitlements.requireLayout(p);
    }

    private void requirePromote(Principal p) {
        require(p);
        if (!rachana.studioSave()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "saving Sutras is disabled (drishti.rachana.studio-save)");
        }
        if (!entitlements.mayAuthor(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " is not a Sutra author: promoting a layout needs a role with author");
        }
    }
}
