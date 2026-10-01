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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.pivot.PivotProperties;
import com.ash.drishti.engine.search.SearchPivot;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.rachana.RachanaProperties;
import com.ash.drishti.rachana.SutraLayoutEditor;
import com.ash.drishti.rachana.SutraPivotEditor;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.PivotSpec;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.server.governance.Proposal;
import com.ash.drishti.server.governance.SutraGovernance;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Saved pivots: each user's arrangement of a Pivot tab, kept in the preference store (namespace {@code pivots}) per Sutra
 * and panel ({@code /panel/{sutra}/{panel}}) or per kind for search results ({@code /search/{kind}}), never in the Sutra
 * or the pack. Keeping one needs only what seeing the pivot needs (the kind); there is no separate power, as for a
 * table's sort. An author may turn a panel's saved pivot into the Sutra's default {@code pivot:} arrangement: the next
 * version, written by {@link SutraPivotEditor} and proposed for review as a layout promotion is ({@code /promotion}).
 */
@RestController
@RequestMapping("/api/v1/me/pivots")
public class SavedPivotController {

    static final String NS = "pivots";

    private final PivotProperties props;
    private final Entitlements entitlements;
    private final PreferenceStore store;
    private final SutraRegistry sutras;
    private final SutraGovernance governance;
    private final RachanaProperties rachana;
    private final SearchPivot search;
    private final SutraPivotEditor editor = new SutraPivotEditor();
    private final ObjectMapper json = new ObjectMapper();

    public SavedPivotController(PivotProperties props, Entitlements entitlements, PreferenceStore store, SutraRegistry sutras,
            SutraGovernance governance, RachanaProperties rachana, SearchPivot search) {
        this.props = props;
        this.entitlements = entitlements;
        this.store = store;
        this.sutras = sutras;
        this.governance = governance;
        this.rachana = rachana;
        this.search = search;
    }

    /** Every pivot the caller keeps (each still valid against its Sutra or pack), and whether they may promote one. */
    @GetMapping
    public Map<String, Object> all(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", props.enabled());
        out.put("promote", props.enabled() && rachana.studioSave() && entitlements.mayAuthor(p));
        out.put("review", governance.enabled());
        List<JsonNode> kept = new ArrayList<>();
        if (props.enabled()) {
            for (String key : store.keys(p.user(), NS)) {
                store.get(p.user(), NS, key).filter(d -> current(d, p)).ifPresent(kept::add);
            }
        }
        out.put("pivots", kept);
        return out;
    }

    // ---- a panel's pivot ------------------------------------------------------------------------------------------

    @GetMapping("/panel/{sutra}/{panel}")
    public JsonNode getPanel(@PathVariable String sutra, @PathVariable String panel, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        require();
        return store.get(p.user(), NS, panelKey(sutra, panel)).filter(d -> current(d, p))
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no saved pivot for " + sutra + " / " + panel));
    }

    /** Keeps the caller's arrangement of a panel's pivot: {@code {"rows": [...], "columns": [...], "values": [...], ...}}. */
    @PutMapping("/panel/{sutra}/{panel}")
    public JsonNode putPanel(@PathVariable String sutra, @PathVariable String panel, @RequestBody JsonNode body,
            @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        require();
        Sutra s = sutra(sutra);
        entitlements.requireOpen(p, s.match().kind());
        PivotSpec a = read(body, offered(s, panel));
        ObjectNode doc = doc(a);
        doc.put("scope", "panel").put("sutra", s.name()).put("panel", panel).put("kind", s.match().kind());
        store.put(p.user(), NS, panelKey(s.name(), panel), doc);
        return doc;
    }

    /** Back to the Sutra's arrangement: forgets the caller's. */
    @DeleteMapping("/panel/{sutra}/{panel}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePanel(@PathVariable String sutra, @PathVariable String panel, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        require();
        if (!store.delete(p.user(), NS, panelKey(sutra, panel))) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no saved pivot for " + sutra + " / " + panel);
        }
    }

    // ---- a kind's search results ----------------------------------------------------------------------------------

    @GetMapping("/search/{kind}")
    public JsonNode getSearch(@PathVariable String kind, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        require();
        String k = search.kindOf(kind);
        return store.get(p.user(), NS, searchKey(k)).filter(d -> current(d, p))
                .orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no saved pivot for " + k + " search results"));
    }

    @PutMapping("/search/{kind}")
    public JsonNode putSearch(@PathVariable String kind, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        require();
        String k = search.kindOf(kind);
        entitlements.requireOpen(p, k);
        PivotSpec offered = search.offered(k)
                .orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, k + " search results do not offer a pivot"));
        ObjectNode doc = doc(read(body, offered));
        doc.put("scope", "search").put("kind", k);
        store.put(p.user(), NS, searchKey(k), doc);
        return doc;
    }

    @DeleteMapping("/search/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSearch(@PathVariable String kind, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        require();
        if (!store.delete(p.user(), NS, searchKey(search.kindOf(kind)))) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no saved pivot for " + kind + " search results");
        }
    }

    // ---- promotion -------------------------------------------------------------------------------------------------

    /**
     * What promoting the caller's saved pivot of a panel would propose: the next version of the Sutra with the panel's
     * {@code pivot:} opening as the user arranged it ({@code text}), the latest version ({@code base}) and the changes in
     * words. Needs the author power and Studio saving.
     */
    @GetMapping("/panel/{sutra}/{panel}/promotion")
    public Map<String, Object> promotion(@PathVariable String sutra, @PathVariable String panel, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        requirePromote(p);
        JsonNode saved = getPanel(sutra, panel, p);
        Sutra s = sutra(sutra);
        PivotSpec a = read(saved, offered(s, panel));
        String base = sutras.source(s.name(), s.version()).orElseThrow(() -> new DrishtiException(ErrorCode.SUTRA_NOT_FOUND, s.id()));
        SutraLayoutEditor.Edit edit;
        try {
            edit = editor.apply(base, panel, a);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "cannot write this pivot into " + s.id() + ": " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sutra", s.name());
        out.put("panel", panel);
        out.put("kind", s.match().kind());
        out.put("fromVersion", edit.fromVersion());
        out.put("version", edit.version());
        out.put("base", base);
        out.put("text", edit.text());
        out.put("changes", edit.changes());
        out.put("review", governance.enabled());
        return out;
    }

    /** Proposes it ({@code {"note": "…"}}): with review on, a proposal ({@code 202}); with review off, live at once. */
    @PostMapping("/panel/{sutra}/{panel}/promotion")
    public ResponseEntity<Map<String, Object>> promote(@PathVariable String sutra, @PathVariable String panel,
            @RequestBody(required = false) JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) throws IOException {
        Map<String, Object> made = promotion(sutra, panel, p);
        String note = body == null ? "" : body.path("note").asText("").strip();
        String why = note.isEmpty() ? "Pivot of " + panel + " promoted from " + p.user() + "'s saved pivot" : note;
        Map<String, Object> out = new LinkedHashMap<>();
        if (governance.enabled()) {
            Proposal pr = governance.propose((String) made.get("text"), why.length() > 500 ? why.substring(0, 500) : why, p);
            out.put("proposal", Map.of("id", pr.id(), "name", pr.name(), "version", pr.version(), "status", pr.status()));
            return ResponseEntity.accepted().body(out);
        }
        Sutra saved = sutras.save((String) made.get("text"));
        out.put("saved", Map.of("name", saved.name(), "version", saved.version()));
        return ResponseEntity.ok(out);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** {@code panel <sutra> <panel>}: the preference store's names are letters, digits, space, . _ and -, at most 64. */
    static String panelKey(String sutra, String panel) {
        return key("panel " + sutra + " " + panel);
    }

    static String searchKey(String kind) {
        return key("search " + kind);
    }

    private static String key(String k) {
        if (k.length() > 64 || !k.matches("[A-Za-z0-9 ._-]+")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a pivot is kept by Sutra and panel id, or by kind, in at most 64 letters, digits, . _ and -: '"
                    + k + "' is not");
        }
        return k;
    }

    private Sutra sutra(String name) {
        return sutras.latest(name).orElseThrow(() -> new DrishtiException(ErrorCode.SUTRA_NOT_FOUND, "no Sutra '" + name + "'"));
    }

    private static PivotSpec offered(Sutra s, String panel) {
        Optional<Panel> pn = s.panels().stream().filter(x -> x.id().equals(panel)).findFirst();
        if (pn.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, s.id() + " has no panel '" + panel + "'");
        }
        return pn.get().pivot().orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST,
                "panel '" + panel + "' of " + s.id() + " does not offer a pivot (its Sutra has no pivot: on it)"));
    }

    /** The arrangement keys of a body (or of a stored pivot) read against what is offered. */
    @SuppressWarnings("unchecked")
    private PivotSpec read(JsonNode body, PivotSpec offered) {
        Map<String, Object> all = json.convertValue(body, Map.class);
        Map<String, Object> a = new LinkedHashMap<>();
        for (String k : SearchPivotController.ARRANGEMENT) {
            if (all != null && all.get(k) != null) {
                a.put(k, all.get(k));
            }
        }
        PivotSpec.Parsed parsed = PivotSpec.arrangement(a, offered);
        if (!parsed.problems().isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, String.join("; ", parsed.problems()));
        }
        return parsed.spec();
    }

    private ObjectNode doc(PivotSpec a) {
        ObjectNode doc = json.valueToTree(a.arrangementMap());
        doc.put("updatedAt", Instant.now().toString());
        return doc;
    }

    /** Whether a stored pivot still fits: its Sutra still has the panel with a pivot (or its kind still offers one), and the fields. */
    private boolean current(JsonNode d, Principal p) {
        try {
            if ("search".equals(d.path("scope").asText())) {
                String kind = d.path("kind").asText();
                return entitlements.mayOpen(p, kind) && search.offered(kind).map(o -> PivotSpec.arrangement(arrangementOf(d), o).problems().isEmpty())
                        .orElse(false);
            }
            Optional<Sutra> s = sutras.latest(d.path("sutra").asText(""));
            return s.isPresent() && entitlements.mayOpen(p, s.get().match().kind())
                    && PivotSpec.arrangement(arrangementOf(d), offered(s.get(), d.path("panel").asText())).problems().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> arrangementOf(JsonNode d) {
        Map<String, Object> all = json.convertValue(d, Map.class);
        Map<String, Object> a = new LinkedHashMap<>();
        for (String k : SearchPivotController.ARRANGEMENT) {
            if (all.get(k) != null) {
                a.put(k, all.get(k));
            }
        }
        return a;
    }

    private void require() {
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the Pivot tab is switched off on this server (drishti.pivot.enabled)");
        }
    }

    private void requirePromote(Principal p) {
        require();
        if (!rachana.studioSave()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "saving Sutras is disabled (drishti.rachana.studio-save)");
        }
        if (!entitlements.mayAuthor(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " is not a Sutra author: promoting a pivot needs a role with author");
        }
    }
}
