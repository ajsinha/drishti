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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.Form;
import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drafts of a form (SUTRA_NEXT.md 10): what a person typed into a panel's {@code form:} (an edit beside the table, edited cells, a
 * new record in the drawer), kept per person in the content store until 2.8.0 sends it through a connector. Every save is checked
 * again here with the same rules the browser ran: the field types, required, min and max, the rules (an error refuses the draft, a
 * warning is kept with it); a value for a field masked for the person is refused, and so is a change to a field they may not edit.
 * Each change is written to the audit log. Only the owner ever reads a draft.
 */
@RestController
@RequestMapping("/api/v1/drafts")
public class DraftsController {

    static final String NS = "form-drafts";
    static final int MAX_DRAFTS = 200;
    static final int MAX_RECORDS = 500;
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final Pattern PANEL = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");
    private static final Pattern DRAFT = Pattern.compile("^d-[0-9a-f]{12}$");
    private static final Set<String> MODES = Set.of("edit", "cells", "new");

    private final PreferenceStore store;
    private final SutraRegistry sutras;
    private final Entitlements entitlements;
    private final AuditLog audit;
    private final ObjectMapper json = new ObjectMapper();

    /** The Designs, for a page drawn with the person's own Design (absent in a test that makes the controller itself). */
    private com.ash.drishti.identity.design.DesignService designs;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void designs(com.ash.drishti.identity.design.DesignService d) {
        this.designs = d;
    }

    public DraftsController(PreferenceStore store, SutraRegistry sutras, Entitlements entitlements, AuditLog audit) {
        this.store = store;
        this.sutras = sutras;
        this.entitlements = entitlements;
        this.audit = audit;
    }

    /** The person's drafts, newest first, optionally of one page ({@code kind}, {@code id}) and Sutra. */
    @GetMapping
    public ArrayNode list(@RequestParam(required = false) String kind, @RequestParam(required = false) String id,
            @RequestParam(required = false) String sutra, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        List<JsonNode> all = new ArrayList<>();
        for (String k : store.keys(p.user(), NS)) {
            store.get(p.user(), NS, k).ifPresent(d -> {
                if ((kind == null || kind.equals(d.path("kind").asText())) && (id == null || id.equals(d.path("id").asText()))
                        && (sutra == null || sutra.equals(d.path("sutra").asText()))) {
                    all.add(d);
                }
            });
        }
        all.sort((a, b) -> b.path("updatedAt").asText().compareTo(a.path("updatedAt").asText()));
        ArrayNode out = json.createArrayNode();
        all.forEach(out::add);
        return out;
    }

    @GetMapping("/{draft}")
    public JsonNode get(@PathVariable String draft, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return stored(p, draft);
    }

    @PostMapping
    public ResponseEntity<JsonNode> create(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        if (store.keys(p.user(), NS).size() >= MAX_DRAFTS) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "at most " + MAX_DRAFTS + " drafts: discard some first");
        }
        String draft = "d-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return save(p, draft, body, null);
    }

    @PutMapping("/{draft}")
    public ResponseEntity<JsonNode> update(@PathVariable String draft, @RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        JsonNode before = stored(p, draft);
        return save(p, draft, body, before);
    }

    @DeleteMapping("/{draft}")
    public ResponseEntity<Void> delete(@PathVariable String draft, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        JsonNode before = stored(p, draft);
        store.delete(p.user(), NS, draft);
        audit.record(p.user(), "form-draft-discarded", subject(before), draft);
        return ResponseEntity.noContent().build();
    }

    // ---- saving ---------------------------------------------------------------------------------------------------------------

    private ResponseEntity<JsonNode> save(Principal p, String draft, JsonNode body, JsonNode before) {
        if (body == null || !body.isObject()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a draft is { kind, id, sutra, panel, mode, records: [{ record, values, was }] }");
        }
        String kind = body.path("kind").asText("");
        String sutra = body.path("sutra").asText("");
        String panelId = body.path("panel").asText("");
        String mode = body.path("mode").asText("");
        if (!NAME.matcher(kind).matches() || !NAME.matcher(sutra).matches() || !PANEL.matcher(panelId).matches() || !MODES.contains(mode)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a draft names the page's kind, its Sutra, the panel and the mode (edit, cells or new)");
        }
        if (before != null && (!kind.equals(before.path("kind").asText()) || !panelId.equals(before.path("panel").asText()))) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a draft stays on its page and panel");
        }
        entitlements.requireOpen(p, kind);
        String design = body.path("design").asText("");
        Form form = form(p, design, sutra, panelId);
        JsonNode records = body.path("records");
        if (!records.isArray() || records.isEmpty() || records.size() > MAX_RECORDS || !mode.equals("cells") && records.size() != 1) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a draft has one record (" + MAX_RECORDS + " for edited cells)");
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<String> roles = entitlements.formRoles(p);
        List<Map<String, Object>> problems = new ArrayList<>();
        List<Map<String, Object>> warnings = new ArrayList<>();
        int changed = 0;
        for (int i = 0; i < records.size(); i++) {
            JsonNode r = records.get(i);
            Map<String, Object> values = plain(r.path("values"));
            Map<String, Object> was = plain(r.path("was"));
            Set<String> checked = new HashSet<>();
            for (String fid : union(values.keySet(), was.keySet())) {
                Form.Field f = form.field(fid).orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, "the form has no field '" + fid + "'"));
                if (entitlements.masked(p, f.recordField())) {
                    audit.record(p.user(), "form-draft-refused", kind + "/" + sutra + "#" + panelId, f.id() + " is masked");
                    throw new DrishtiException(ErrorCode.FORBIDDEN, f.label() + " is hidden for you: a draft cannot carry it");
                }
                boolean differs = mode.equals("new") ? values.containsKey(fid) && !blank(values.get(fid))
                        : values.containsKey(fid) && !same(values.get(fid), was.get(fid));
                if (!differs) {
                    continue;
                }
                if (mode.equals("new") ? !form.enterableBy(f, roles) : !form.editableBy(f, roles)) {
                    audit.record(p.user(), "form-draft-refused", kind + "/" + sutra + "#" + panelId, f.id() + " is locked");
                    throw new DrishtiException(ErrorCode.FORBIDDEN, f.label() + " is " + (f.readonly() ? "read-only" : "locked: you can view, not change"));
                }
                checked.add(fid);
                changed++;
            }
            if (mode.equals("new")) {
                Set<String> reached = reached(form, body.path("step").asText(""));   // a new record is checked up to the step it reached
                form.fields().stream().filter(f -> reached.contains(stepOf(form, f)) && form.enterableBy(f, roles) && !entitlements.masked(p, f.recordField()))
                        .forEach(f -> checked.add(f.id()));
            }
            Map<String, Object> all = new LinkedHashMap<>(was);
            all.putAll(values);
            String record = r.path("record").isNull() || r.path("record").isMissingNode() ? null : r.path("record").asText();
            for (Form.Issue is : form.check(all, checked, today)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("record", record);
                m.put("field", is.field());
                m.put("message", is.message());
                m.put("severity", is.severity());
                (is.error() ? problems : warnings).add(m);
            }
        }
        if (!problems.isEmpty()) {
            ObjectNode err = json.createObjectNode();
            err.put("code", ErrorCode.FORM_INVALID.code());
            err.put("detail", problems.get(0).get("message").toString());
            err.set("issues", json.valueToTree(problems));
            err.set("warnings", json.valueToTree(warnings));
            return ResponseEntity.status(422).body(err);
        }
        ObjectNode d = json.createObjectNode();
        d.put("draft", draft);
        d.put("kind", kind);
        d.put("id", body.path("id").asText(""));
        d.put("sutra", sutra);
        d.put("design", design);
        d.put("panel", panelId);
        d.put("mode", mode);
        d.put("title", body.path("title").asText(form.title() == null ? panelId : form.title()));
        d.put("step", body.path("step").asText(""));
        d.set("records", records);
        d.set("warnings", json.valueToTree(warnings));
        d.put("changes", changed);
        d.put("createdAt", before == null ? Instant.now().toString() : before.path("createdAt").asText());
        d.put("updatedAt", Instant.now().toString());
        store.put(p.user(), NS, draft, d);
        audit.record(p.user(), before == null ? "form-draft-saved" : "form-draft-updated", subject(d),
                draft + ": " + changed + " change" + (changed == 1 ? "" : "s") + (warnings.isEmpty() ? "" : ", " + warnings.size() + " warning" + (warnings.size() == 1 ? "" : "s")));
        return ResponseEntity.status(before == null ? 201 : 200).body(d);
    }

    /** The form of a panel: of the person's own Design when the page is drawn with one ({@code design}), else of the named Sutra. */
    private Form form(Principal who, String design, String sutra, String panelId) {
        com.ash.drishti.rachana.model.Sutra s;
        if (!design.isBlank() && designs != null) {
            var d = designs.get(who.user(), design);
            if (d.sutra == null || d.sutra.isBlank()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the design '" + design + "' has no Sutra yet");
            }
            s = sutras.check(d.sutra);
        } else {
            s = sutras.latest(sutra).orElseThrow(() -> new DrishtiException(ErrorCode.SUTRA_NOT_FOUND, "no Sutra '" + sutra + "'"));
        }
        for (Panel pn : s.panels()) {
            if (pn.id().equals(panelId)) {
                return pn.form().orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, "panel '" + panelId + "' has no form"));
            }
        }
        throw new DrishtiException(ErrorCode.BAD_REQUEST, "Sutra '" + sutra + "' has no panel '" + panelId + "'");
    }

    private JsonNode stored(Principal p, String draft) {
        if (!DRAFT.matcher(draft).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a draft id is d- and 12 hex digits");
        }
        return store.get(p.user(), NS, draft).orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no draft " + draft));
    }

    /** The drawer's steps up to {@code step} (every step when it names none, or the form has none). */
    private static Set<String> reached(Form form, String step) {
        Set<String> out = new HashSet<>();
        out.add("");
        for (Form.Step s : form.steps()) {
            out.add(s.id());
            if (s.id().equals(step)) {
                return out;
            }
        }
        return out;
    }

    private static String stepOf(Form form, Form.Field f) {
        if (form.steps().isEmpty()) {
            return "";
        }
        return f.step() != null && form.steps().stream().anyMatch(s -> s.id().equals(f.step())) ? f.step() : form.steps().get(0).id();
    }

    private static String subject(JsonNode d) {
        return d.path("kind").asText() + "/" + d.path("id").asText() + " " + d.path("sutra").asText() + "#" + d.path("panel").asText();
    }

    private Map<String, Object> plain(JsonNode n) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (n != null && n.isObject()) {
            n.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                if (!v.isValueNode()) {
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, "a value is text, a number, true or false (" + e.getKey() + ")");
                }
                out.put(e.getKey(), v.isNull() ? null : v.isNumber() ? v.decimalValue() : v.isBoolean() ? (Object) v.booleanValue() : v.asText());
            });
        }
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new java.util.LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }

    private static boolean blank(Object v) {
        return v == null || v.toString().isBlank();
    }

    private static boolean same(Object a, Object b) {
        if (blank(a) && blank(b)) {
            return true;
        }
        var na = com.ash.drishti.rachana.model.FormExpr.number(a);
        var nb = com.ash.drishti.rachana.model.FormExpr.number(b);
        if (na != null && nb != null) {
            return na.compareTo(nb) == 0;
        }
        return Objects.equals(a == null ? null : a.toString(), b == null ? null : b.toString());
    }
}
