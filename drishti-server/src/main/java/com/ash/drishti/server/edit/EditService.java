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
package com.ash.drishti.server.edit;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.identity.collab.Ulid;
import com.ash.drishti.rachana.model.Form;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Write-back's front door (WRITE_BACK.md › The design and its reasons, sections 6, 9 and 12; section 18.1 for forms): the UI, the CLI and the API make the same change
 * requests here. A submission is checked as the person (the site switch, the edit power, the kind, masks, the field's form and roles, the
 * target, a reason when required, a restatement's role), then either waits for approval ({@code drishti_edit_request}) or is appended
 * to its lane and acknowledged once committed. A form's draft is submitted by its id: the server reads the draft, turns each record into
 * one change (a field per changed value), and closes the draft when the change is applied. Approvals are four eyes: never your own,
 * by the policy's approver roles. Everything is re-checked when the change is applied ({@link EditHandler}).
 */
public final class EditService {

    static final String DRAFTS = "form-drafts";
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final Pattern ENTITY = Pattern.compile("^[^\\s/\\\\]{1,256}$");
    private static final Pattern DRAFT = Pattern.compile("^d-[0-9a-f]{12}$");
    private static final int MAX_ITEMS = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Appends a change to its lane wherever the lane is owned (this node, or forwarded to its owner); returns {@code {lane, seq}}. */
    @FunctionalInterface
    public interface Appender {
        long[] append(Change c, Principal p);
    }

    /** Where source commands go: why a connector's command cannot be used now (empty when it can). */
    @FunctionalInterface
    public interface Commands {
        Optional<String> unusable(String connector, String command, String kind);
    }

    private final EditRecords records;
    private final EditLog log;
    private final ChangeRequests requests;
    private final EditableFields fields;
    private final Entitlements entitlements;
    private final PreferenceStore drafts;
    private final AuditLog audit;
    private final Supplier<EditSettings> settings;
    private final Supplier<LocalDate> today;
    private volatile Appender appender;
    private volatile Commands commands = (connector, command, kind) -> Optional.of("source commands are not available on this server");
    private volatile java.util.function.Function<String, Optional<String>> connectorOfKind = k -> Optional.empty();
    private final List<java.util.function.Consumer<Map<String, Object>>> notices = new java.util.concurrent.CopyOnWriteArrayList<>();

    public EditService(EditRecords records, EditLog log, ChangeRequests requests, EditableFields fields, Entitlements entitlements, PreferenceStore drafts,
            AuditLog audit, Supplier<EditSettings> settings, Supplier<LocalDate> today) {
        this.records = records;
        this.log = log;
        this.requests = requests;
        this.fields = fields;
        this.entitlements = entitlements;
        this.drafts = drafts;
        this.audit = audit;
        this.settings = settings;
        this.today = today;
    }

    public void appender(Appender a) {
        this.appender = a;
    }

    public void commands(Commands c, java.util.function.Function<String, Optional<String>> connectorOf) {
        this.commands = c;
        this.connectorOfKind = connectorOf;
    }

    /** Told of every submission, approval and decision (the live channel tells the people it concerns). */
    public void onNotice(java.util.function.Consumer<Map<String, Object>> n) {
        notices.add(n);
    }

    public EditSettings settings() {
        return settings.get();
    }

    public EditableFields fields() {
        return fields;
    }

    // ------------------------------------------------------------------------------------------------------------ submit

    /**
     * Submits a change: {@code {draft, reason}} (a form's draft: one change per record), or {@code {kind, entity, items: [{field | path,
     * from, to}], basedOn, reason, sutra, panel, businessDate, id, override}}. Returns one answer per change: its id and state
     * ({@code queued} with the lane and seq once committed, or {@code awaiting} approval with the approvals it needs).
     */
    public List<Map<String, Object>> submit(Principal p, JsonNode body) {
        List<Change> changes = changes(p, body);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Change c : changes) {
            out.add(accept(p, c));
        }
        return out;
    }

    /** The changes a submission makes, checked as the person, without submitting them (the review and confirm sheet uses it too). */
    public List<Change> changes(Principal p, JsonNode body) {
        EditSettings s = settings.get();
        if (!s.enabled()) {
            throw new DrishtiException(ErrorCode.EDIT_OFF, "editing is off on this server (edit.enabled in config/edit/edit.yaml)");
        }
        entitlements.requireEdit(p);
        if (body == null || !body.isObject()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a change is { draft, reason } or { kind, entity, items: [{ field, from, to }], reason }");
        }
        List<Change> out = body.hasNonNull("draft") ? fromDraft(p, body) : List.of(explicit(p, body));
        for (Change c : out) {
            check(p, c);
        }
        return out;
    }

    private Change explicit(Principal p, JsonNode body) {
        String kind = body.path("kind").asText("");
        String entity = body.path("entity").asText("");
        if (!NAME.matcher(kind).matches() || !ENTITY.matcher(entity).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a change names the record's kind and its id");
        }
        entitlements.requireOpen(p, kind);
        JsonNode items = body.path("items");
        if (!items.isArray() || items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a change has 1 to " + MAX_ITEMS + " items: { field (or path), from, to }");
        }
        String sutra = text(body, "sutra");
        String panel = text(body, "panel");
        List<Change.Item> list = new ArrayList<>();
        EditableFields.Rule first = null;
        for (JsonNode it : items) {
            String path = it.hasNonNull("path") ? it.path("path").asText() : EditPaths.ofField(it.path("field").asText(""));
            if (!EditPaths.valid(path)) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "an item names a field (NOTIONAL) or a path ($.NOTIONAL), not '" + path + "'");
            }
            String bind = EditPaths.field(path);
            EditableFields.Rule r = fields.find(kind, bind, sutra, panel).orElseThrow(() -> refused(p, kind + ":" + entity,
                    bind + " of " + kind + " is not editable: no Sutra's form with save: names it"));
            if (first == null) {
                first = r;
            } else if (r.form() != first.form()) {
                throw refused(p, kind + ":" + entity, "the items belong to different forms: submit them separately");
            }
            list.add(new Change.Item(path, value(it.get("from")), value(it.get("to"))));
        }
        Map<String, String> origin = new LinkedHashMap<>();
        origin.put("sutra", first.sutra());
        origin.put("panel", first.panel());
        origin.put("mode", "edit");
        origin.put("via", text(body, "via") == null ? "api" : text(body, "via"));
        return build(p, body, first, kind, entity, list, origin);
    }

    /** A form's draft turned into changes (WRITE_BACK.md 18.1, step 3): one per record, an item per changed field. */
    private List<Change> fromDraft(Principal p, JsonNode body) {
        String id = body.path("draft").asText("");
        if (!DRAFT.matcher(id).matches()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a draft id is d- and 12 hex digits");
        }
        JsonNode d = drafts.get(p.user(), DRAFTS, id).orElseThrow(() -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no draft " + id));
        if (!d.path("design").asText("").isBlank()) {
            throw refused(p, id, "a form of a personal Design keeps drafts only: publish the Design's Sutra to submit its changes");
        }
        String sutra = d.path("sutra").asText();
        String panel = d.path("panel").asText();
        String mode = d.path("mode").asText();
        Map<String, EditableFields.Rule> rules = fields.ofForm(sutra, panel);
        if (rules.isEmpty()) {
            throw refused(p, id, "the form of " + sutra + " # " + panel + " keeps drafts only: it has no save: (RACHANA_REFERENCE.md › Sutra Next: fragments, events, children, sources, layout and forms, section 10)");
        }
        EditableFields.Rule any = rules.values().iterator().next();
        Form form = any.form();
        entitlements.requireOpen(p, any.kind());
        List<Change> out = new ArrayList<>();
        for (JsonNode r : d.path("records")) {
            Map<String, Object> values = plain(r.path("values"));
            Map<String, Object> was = plain(r.path("was"));
            List<Change.Item> items = new ArrayList<>();
            for (Map.Entry<String, Object> e : values.entrySet()) {
                EditableFields.Rule rule = rules.get(e.getKey());
                if (rule == null) {
                    continue;
                }
                boolean differs = "new".equals(mode) ? !blank(e.getValue()) : !EditHandler.same(e.getValue(), was.get(e.getKey()));
                if (differs) {
                    items.add(new Change.Item(rule.path(), "new".equals(mode) ? null : was.get(e.getKey()), e.getValue()));
                }
            }
            if (items.isEmpty()) {
                continue;
            }
            Map<String, Object> all = new LinkedHashMap<>(was);
            all.putAll(values);
            Set<String> checked = new HashSet<>();
            items.forEach(i -> rules.values().stream().filter(x -> x.path().equals(i.path())).forEach(x -> checked.add(x.field().id())));
            List<Map<String, Object>> issues = new ArrayList<>();
            for (Form.Issue is : form.check(all, checked, today.get())) {
                if (is.error()) {
                    issues.add(Map.of("field", is.field() == null ? "" : is.field(), "message", is.message()));
                }
            }
            if (!issues.isEmpty()) {
                throw new DrishtiException(ErrorCode.FORM_INVALID, issues.get(0).get("message").toString());
            }
            String entity = "new".equals(mode) ? newId(any, values) : r.path("record").asText(d.path("id").asText(""));
            if (!ENTITY.matcher(entity).matches()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the draft's record has no id");
            }
            Map<String, String> origin = new LinkedHashMap<>();
            origin.put("sutra", sutra);
            origin.put("panel", panel);
            origin.put("draft", id);
            origin.put("mode", "new".equals(mode) ? "new" : "edit");
            origin.put("via", "form");
            origin.put("pageKind", d.path("kind").asText());
            origin.put("pageId", d.path("id").asText());
            if (any.rows() != null && any.keyField() != null) {
                origin.put("rows", any.rows());
                origin.put("rowKey", any.keyField());
            }
            out.add(build(p, body, any, any.kind(), entity, items, origin));
        }
        if (out.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the draft changes nothing: there is nothing to submit");
        }
        return out;
    }

    /** A new record's id: the form's key field when it was given, else one made up ({@code NEW-} and a ULID) until the source names it. */
    private static String newId(EditableFields.Rule r, Map<String, Object> values) {
        Form f = r.form();
        Object k = f.key() == null ? null : values.get(f.key());
        return blank(k) ? Ulid.next("NEW-") : k.toString().strip();
    }

    private Change build(Principal p, JsonNode body, EditableFields.Rule rule, String kind, String entity, List<Change.Item> items, Map<String, String> origin) {
        Form.Save save = rule.save();
        String target = save.target();
        String connector = null;
        String command = null;
        if ("source".equals(target)) {
            connector = save.connector() != null ? save.connector() : connectorOfKind.apply(kind).orElse(null);
            command = "new".equals(origin.get("mode")) ? save.create() : save.command();
        }
        LocalDate date = body.hasNonNull("businessDate") ? LocalDate.parse(body.path("businessDate").asText()) : today.get();
        String id = text(body, "id");
        if (id == null || !Ulid.valid(id, "chg-") || body.hasNonNull("draft")) {
            id = Ulid.next("chg-");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        if (body.path("params").isObject()) {
            params.putAll(plain(body.path("params")));
        }
        Long basedOn = body.hasNonNull("basedOn") ? body.path("basedOn").asLong() : null;
        origin.put("roles", String.join(",", p.roles()));          // for a person the user directory does not hold: re-checked with these
        int needed = settings.get().policy().needed(kind, target, save.approval());
        return new Change(id, Change.keyOf(kind, entity), kind, entity, date, items, basedOn, target, connector, command, params,
                text(body, "reason"), p.user(), Instant.now(), needed, origin, body.path("override").asBoolean(false));
    }

    /** The submit-time checks (they run again at apply time). */
    private void check(Principal p, Change c) {
        EditSettings s = settings.get();
        boolean isNew = "new".equals(c.origin("mode"));
        List<String> held = entitlements.formRoles(p);
        for (Change.Item it : c.items()) {
            for (String f : EditHandler.fieldsOf(it.path())) {
                if (entitlements.masked(p, f)) {
                    throw refused(p, c.key(), f + " is hidden for you: a change can never carry a masked field");
                }
            }
            EditableFields.Rule r = fields.find(c.kind(), EditPaths.field(it.path()), c.origin("sutra"), c.origin("panel")).orElseThrow();
            if (isNew ? !r.form().enterableBy(r.field(), held) : !r.form().editableBy(r.field(), held)) {
                throw refused(p, c.key(), r.field().label() + " is " + (r.field().readonly() ? "read-only" : "locked: you can view, not change"));
            }
        }
        EditableFields.Rule any = fields.find(c.kind(), EditPaths.field(c.items().get(0).path()), c.origin("sutra"), c.origin("panel")).orElseThrow();
        if (isNew && "overlay".equals(c.target())) {
            throw refused(p, c.key(), "a new record cannot be a correction (overlay): there is no source record to lay it over; use owned or source");
        }
        if ("source".equals(c.target())) {
            if (c.connector() == null || c.command() == null) {
                throw refused(p, c.key(), "the form sends to a source but names no connector or command");
            }
            commands.unusable(c.connector(), c.command(), c.kind()).ifPresent(why -> {
                throw refused(p, c.key(), why);
            });
        }
        if (any.save().reason() && c.reason().isBlank()) {
            throw refused(p, c.key(), "say why: this change needs a reason");
        }
        if (c.businessDate().isBefore(today.get()) && !EditHandler.holds(p, s.policy().restateRole())) {
            throw refused(p, c.key(), "a change to a past business date (" + c.businessDate() + ") is a restatement: it needs the "
                    + s.policy().restateRole() + " role");
        }
        if (c.override() && !EditHandler.holds(p, s.policy().overrideRole())) {
            throw refused(p, c.key(), "Apply mine anyway needs the " + s.policy().overrideRole() + " role");
        }
    }

    private DrishtiException refused(Principal p, String subject, String why) {
        audit.record(p.user(), "edit-refused", subject, why);
        return new DrishtiException(ErrorCode.EDIT_REFUSED, why);
    }

    /** Waits for approval, or goes to its lane at once. */
    private Map<String, Object> accept(Principal p, Change c) {
        if (c.approvals() > 0) {
            requests.add(c, c.approvals());
            audit.record(p.user(), "edit-submitted", c.key(), c.id() + ": awaiting " + c.approvals() + " approval" + (c.approvals() == 1 ? "" : "s"));
            notice("submitted", c, Map.of("state", ChangeRequests.AWAITING, "needed", c.approvals()));
            Map<String, Object> out = answer(c, ChangeRequests.AWAITING);
            out.put("needed", c.approvals());
            return out;
        }
        long[] at = enqueue(p, c);
        audit.record(p.user(), "edit-submitted", c.key(), c.id() + ": queued in lane " + at[0] + " at seq " + at[1]);
        Map<String, Object> out = answer(c, EditLog.QUEUED);
        out.put("lane", at[0]);
        out.put("seq", at[1]);
        return out;
    }

    private static Map<String, Object> answer(Change c, String state) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", c.id());
        out.put("kind", c.kind());
        out.put("entity", c.entity());
        out.put("target", c.target());
        out.put("state", state);
        if (c.origin("draft") != null) {
            out.put("draft", c.origin("draft"));
        }
        return out;
    }

    private long[] enqueue(Principal p, Change c) {
        Appender a = appender;
        if (a == null) {
            throw new DrishtiException(ErrorCode.EDIT_PAUSED, "edits are paused: the edit log is not ready on this server; your draft is kept");
        }
        try {
            return a.append(c, p);
        } catch (EditLog.Busy e) {
            throw new DrishtiException(ErrorCode.EDIT_BUSY, e.getMessage() + "; your draft is kept");
        }
    }

    // ------------------------------------------------------------------------------------------------------------ approval

    /** Approves a change awaiting approval (never your own unless the policy allows it); the last approval needed enqueues it. */
    public Map<String, Object> approve(Principal p, String id, String note) {
        ChangeRequests.Request r = requests.get(id).orElseThrow(() -> new DrishtiException(ErrorCode.EDIT_NOT_FOUND, "no change " + id + " awaits approval"));
        mayDecide(p, r);
        if (r.approvals().stream().anyMatch(a -> a.by().equals(p.user()))) {
            throw new DrishtiException(ErrorCode.EDIT_REFUSED, "you have approved " + id + " already: it needs another approver");
        }
        ChangeRequests.Request now = requests.approve(id, p.user(), note, Instant.now()).orElseThrow();
        audit.record(p.user(), "edit-approved", r.change().key(), id + ": " + now.approvals().size() + " of " + now.needed());
        Map<String, Object> out = answer(r.change(), now.state());
        if (ChangeRequests.APPROVED.equals(now.state()) && ChangeRequests.AWAITING.equals(r.state())) {
            long[] at = enqueue(p, r.change());
            out.put("state", EditLog.QUEUED);
            out.put("lane", at[0]);
            out.put("seq", at[1]);
            notice("approved", r.change(), Map.of("state", EditLog.QUEUED, "by", p.user()));
        } else {
            notice("approval", r.change(), Map.of("state", now.state(), "by", p.user(), "approvals", now.approvals().size(), "needed", now.needed()));
        }
        return out;
    }

    /** Rejects a change awaiting approval, with a reason; the draft it came from is kept, marked. */
    public Map<String, Object> reject(Principal p, String id, String reason) {
        ChangeRequests.Request r = requests.get(id).orElseThrow(() -> new DrishtiException(ErrorCode.EDIT_NOT_FOUND, "no change " + id + " awaits approval"));
        mayDecide(p, r);
        if (reason == null || reason.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "say why it is rejected");
        }
        if (!requests.decide(id, ChangeRequests.REJECTED, p.user(), reason, Instant.now())) {
            throw new DrishtiException(ErrorCode.EDIT_CONFLICT, id + " is no longer awaiting approval");
        }
        audit.record(p.user(), "edit-rejected", r.change().key(), id + ": " + reason);
        markDraft(r.change(), ChangeRequests.REJECTED, reason);
        notice("rejected", r.change(), Map.of("state", ChangeRequests.REJECTED, "by", p.user(), "reason", reason));
        return answer(r.change(), ChangeRequests.REJECTED);
    }

    /** The submitter withdraws a change that still awaits approval. */
    public Map<String, Object> withdraw(Principal p, String id) {
        ChangeRequests.Request r = requests.get(id).orElse(null);
        if (r == null) {
            if (log.get(id).isPresent()) {
                throw new DrishtiException(ErrorCode.EDIT_CONFLICT, id + " is already in its lane: it can no longer be withdrawn");
            }
            throw new DrishtiException(ErrorCode.EDIT_NOT_FOUND, "no change " + id);
        }
        if (!r.by().equals(p.user()) && !entitlements.isAdmin(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "only its submitter (or an administrator) withdraws a change");
        }
        if (!requests.decide(id, ChangeRequests.WITHDRAWN, p.user(), "withdrawn", Instant.now())) {
            throw new DrishtiException(ErrorCode.EDIT_CONFLICT, id + " is no longer awaiting approval");
        }
        audit.record(p.user(), "edit-withdrawn", r.change().key(), id);
        notice("withdrawn", r.change(), Map.of("state", ChangeRequests.WITHDRAWN, "by", p.user()));
        return answer(r.change(), ChangeRequests.WITHDRAWN);
    }

    /** May this person decide on the request: an approver (the policy's roles, else the approve power or an administrator), never the submitter. */
    private void mayDecide(Principal p, ChangeRequests.Request r) {
        if (!ChangeRequests.AWAITING.equals(r.state())) {
            throw new DrishtiException(ErrorCode.EDIT_CONFLICT, r.change().id() + " is " + r.state() + ", not awaiting approval");
        }
        if (!approver(p)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, p.user() + " is not an approver of changes ("
                    + (settings.get().policy().approverRoles().isEmpty() ? "the approve power or an administrator" : "roles " + settings.get().policy().approverRoles()) + ")");
        }
        if (r.by().equals(p.user()) && !settings.get().policy().selfApproval()) {
            audit.record(p.user(), "edit-self-approval-refused", r.change().key(), r.change().id());
            throw new DrishtiException(ErrorCode.EDIT_REFUSED, "you submitted this change: someone else approves it (four eyes)");
        }
        entitlements.requireOpen(p, r.change().kind());
    }

    public boolean approver(Principal p) {
        List<String> roles = settings.get().policy().approverRoles();
        return roles.isEmpty() ? entitlements.mayApprove(p) : p.roles().contains("*") || p.roles().stream().anyMatch(roles::contains);
    }

    // ------------------------------------------------------------------------------------------------------------ after an apply

    /** A change ended (applied, refused, conflicted): its draft is closed or marked, and the people it concerns are told. */
    public void ended(EditLog.Row row, String state, String reason, long version) {
        Change c = row.change();
        if (EditLog.APPLIED.equals(state)) {
            closeDraft(c);
        } else {
            markDraft(c, state, reason);
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("state", state);
        extra.put("lane", row.lane());
        extra.put("seq", row.seq());
        extra.put("version", version);
        if (reason != null) {
            extra.put("reason", reason);
        }
        notice(state, c, extra);
    }

    private void closeDraft(Change c) {
        String d = c.origin("draft");
        if (d != null && drafts.get(c.by(), DRAFTS, d).isPresent() && drafts.delete(c.by(), DRAFTS, d)) {
            audit.record(c.by(), "form-draft-closed", c.key(), d + " by change " + c.id());
        }
    }

    private void markDraft(Change c, String state, String reason) {
        String d = c.origin("draft");
        if (d == null) {
            return;
        }
        drafts.get(c.by(), DRAFTS, d).ifPresent(n -> {
            if (n instanceof ObjectNode o) {
                ObjectNode ch = JSON.createObjectNode();
                ch.put("id", c.id());
                ch.put("state", state);
                ch.put("reason", reason == null ? "" : reason);
                o.set("change", ch);
                drafts.put(c.by(), DRAFTS, d, o);
            }
        });
    }

    private void notice(String what, Change c, Map<String, Object> extra) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", what);
        n.put("id", c.id());
        n.put("kind", c.kind());
        n.put("entity", c.entity());
        n.put("by", c.by());
        n.put("target", c.target());
        n.put("paths", c.paths());
        if (c.origin("pageKind") != null) {
            n.put("pageKind", c.origin("pageKind"));
            n.put("pageId", c.origin("pageId"));
        }
        if (c.origin("draft") != null) {
            n.put("draft", c.origin("draft"));
        }
        n.putAll(extra);
        for (var l : notices) {
            try {
                l.accept(n);
            } catch (RuntimeException e) {
                // a listener that fails does not stop the others
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ reads

    /** A change as this person may see it (values of fields masked for them shown masked), from the log or the approval queue. */
    public Map<String, Object> get(Principal p, String id) {
        Optional<EditLog.Row> row = log.get(id);
        Optional<ChangeRequests.Request> req = requests.get(id);
        Change c = row.map(EditLog.Row::change).or(() -> req.map(ChangeRequests.Request::change))
                .orElseThrow(() -> new DrishtiException(ErrorCode.EDIT_NOT_FOUND, "no change " + id));
        if (!c.by().equals(p.user()) && !entitlements.mayOpen(p, c.kind())) {
            throw new DrishtiException(ErrorCode.EDIT_NOT_FOUND, "no change " + id);
        }
        return view(p, c, row.orElse(null), req.orElse(null));
    }

    /** Changes, newest first: {@code state} (an edit-log state, or {@code awaiting} for the approval queue), kind, entity, {@code mine}. */
    public List<Map<String, Object>> list(Principal p, String state, String kind, String entity, boolean mine, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean queue = state == null || state.isBlank() || ChangeRequests.AWAITING.equals(state) || ChangeRequests.REJECTED.equals(state)
                || ChangeRequests.APPROVED.equals(state);
        if (queue) {
            for (ChangeRequests.Request r : requests.list(state == null || state.isBlank() ? null : state, kind, entity, limit)) {
                if (ChangeRequests.APPROVED.equals(r.state()) && !ChangeRequests.APPROVED.equals(state)) {
                    continue;                                          // an approved change is in its lane: the log row says the rest
                }
                if (mine && !r.by().equals(p.user()) || !r.by().equals(p.user()) && !entitlements.mayOpen(p, r.change().kind())) {
                    continue;
                }
                out.add(view(p, r.change(), null, r));
            }
        }
        if (!ChangeRequests.AWAITING.equals(state) && !ChangeRequests.REJECTED.equals(state) && !ChangeRequests.APPROVED.equals(state)) {
            for (EditLog.Row r : log.list(state, kind, entity, mine ? p.user() : null, limit)) {
                if (r.change().by().equals(p.user()) || entitlements.mayOpen(p, r.change().kind())) {
                    out.add(view(p, r.change(), r, null));
                }
            }
        }
        out.sort((a, b) -> String.valueOf(b.get("at")).compareTo(String.valueOf(a.get("at"))));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** A record's history from the edit log, newest first (section 10). */
    public List<Map<String, Object>> history(Principal p, String kind, String entity, int limit) {
        entitlements.requireOpen(p, kind);
        List<Map<String, Object>> out = new ArrayList<>();
        for (EditLog.Row r : log.history(Change.keyOf(kind, entity), limit)) {
            out.add(view(p, r.change(), r, null));
        }
        for (ChangeRequests.Request r : requests.list(ChangeRequests.AWAITING, kind, entity, limit)) {
            out.add(view(p, r.change(), null, r));
        }
        out.sort((a, b) -> String.valueOf(b.get("at")).compareTo(String.valueOf(a.get("at"))));
        return out;
    }

    /** The record's version: the {@code basedOn} a form opens on. */
    public long version(String kind, String entity) {
        return records.version(Change.keyOf(kind, entity));
    }

    Map<String, Object> view(Principal p, Change c, EditLog.Row row, ChangeRequests.Request req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("kind", c.kind());
        m.put("entity", c.entity());
        m.put("businessDate", c.businessDate().toString());
        m.put("target", c.target());
        if (c.connector() != null) {
            m.put("connector", c.connector());
            m.put("command", c.command());
        }
        m.put("by", c.by());
        m.put("at", c.at().toString());
        m.put("reason", c.reason());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Change.Item it : c.items()) {
            boolean hidden = EditHandler.fieldsOf(it.path()).stream().anyMatch(f -> entitlements.masked(p, f));
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("path", it.path());
            i.put("from", hidden ? com.ash.drishti.api.DataNode.MASK : it.from());
            i.put("to", hidden ? com.ash.drishti.api.DataNode.MASK : it.to());
            items.add(i);
        }
        m.put("items", items);
        Map<String, String> origin = new LinkedHashMap<>(c.origin());
        origin.remove("roles");                                  // kept for the apply-time check, never shown
        m.put("origin", origin);
        if (row != null) {
            m.put("state", row.state());
            m.put("lane", row.lane());
            m.put("seq", row.seq());
            if (row.stateReason() != null) {
                m.put("stateReason", row.stateReason());
            }
            if (row.command() != null) {
                m.put("commandState", row.command());
                m.put("commandAttempts", row.attempts());
                if (row.commandReason() != null) {
                    m.put("commandReason", row.commandReason());
                }
            }
            if (row.appliedAt() != null) {
                m.put("appliedAt", row.appliedAt().toString());
            }
        } else if (req != null) {
            m.put("state", req.state());
            m.put("needed", req.needed());
            m.put("approvals", req.approvals());
            if (req.reason() != null) {
                m.put("stateReason", req.reason());
            }
            m.put("mayDecide", ChangeRequests.AWAITING.equals(req.state()) && approver(p) && (!req.by().equals(p.user()) || settings.get().policy().selfApproval()));
        }
        return m;
    }

    // ------------------------------------------------------------------------------------------------------------ helpers

    private static String text(JsonNode n, String k) {
        return n.hasNonNull(k) && !n.path(k).asText().isBlank() ? n.path(k).asText() : null;
    }

    private static Object value(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (!v.isValueNode()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a value is text, a number, true or false");
        }
        return v.isNumber() ? v.decimalValue() : v.isBoolean() ? (Object) v.booleanValue() : v.asText();
    }

    private static Map<String, Object> plain(JsonNode n) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (n != null && n.isObject()) {
            n.fields().forEachRemaining(e -> out.put(e.getKey(), value(e.getValue())));
        }
        return out;
    }

    private static boolean blank(Object v) {
        return v == null || v.toString().isBlank();
    }
}
