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

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.rachana.model.Form;
import com.ash.drishti.rachana.model.FormExpr;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lane owner's transaction handler (WRITE_BACK.md › The design and its reasons, section 7): for each change taken from a lane, in seq order, it
 * <ol>
 *   <li>re-checks the submitter's rights <i>now</i> (the edit power, the kind, the masks, the field's form and roles, a restatement's
 *       role): the Sutra or the roles may have changed since the change was submitted (section 12);</li>
 *   <li>reads the record as it is now and compares every touched field with what the person saw: a field that moved is
 *       <b>conflicted</b>, never overwritten (section 8; unrelated fields that moved do not conflict), unless the person chose
 *       <i>Apply mine anyway</i> and holds the override role;</li>
 *   <li>checks the form's rules against the record as it is now with the change applied (the server is the authority);</li>
 *   <li>writes, in one transaction with the epoch fence: the overlay or owned rows (or {@code command: pending} for a source target,
 *       with its overlay shown as pending), the record's version, the row's state and the lane's applied seq;</li>
 *   <li>after the commit, tells the record's viewers and the triggers ({@link AfterApply}).</li>
 * </ol>
 * A failure that is not the change's fault (the database is down) is thrown, so the worker retries the same row; a fenced transaction
 * stops the lane. Every outcome is audited.
 */
public final class EditHandler implements Lanes.Applier {

    private static final Logger LOG = LoggerFactory.getLogger(EditHandler.class);
    private static final Duration READ = Duration.ofSeconds(10);

    /** Told after an apply commits (or a change ends refused or conflicted). */
    @FunctionalInterface
    public interface AfterApply {
        void ended(EditLog.Row row, String state, String reason, long version);
    }

    private final EditLog log;
    private final EditRecords records;
    private final EditableFields fields;
    private final Function<String, Principal> principals;
    private final Entitlements entitlements;
    private final SourceRouter router;
    private final Supplier<EditSettings> settings;
    private final Supplier<LocalDate> today;
    private final AuditLog audit;
    private final List<AfterApply> after = new java.util.concurrent.CopyOnWriteArrayList<>();

    public EditHandler(EditLog log, EditRecords records, EditableFields fields, Function<String, Principal> principals, Entitlements entitlements,
            SourceRouter router, Supplier<EditSettings> settings, Supplier<LocalDate> today, AuditLog audit) {
        this.log = log;
        this.records = records;
        this.fields = fields;
        this.principals = principals;
        this.entitlements = entitlements;
        this.router = router;
        this.settings = settings;
        this.today = today;
        this.audit = audit;
    }

    public void onEnd(AfterApply a) {
        after.add(a);
    }

    @Override
    public void apply(EditLog.Row row, String node, long epoch) {
        Change c = row.change();
        if (EditLog.APPLIED.equals(row.state()) || EditLog.REFUSED.equals(row.state()) || EditLog.CONFLICTED.equals(row.state())
                || EditLog.WITHDRAWN.equals(row.state())) {
            log.finish(row, node, epoch, row.state(), row.stateReason(), row.command(), null);   // a replay of a finished row: only the seq moves
            return;
        }
        Principal p = principalOf(c);
        List<EditableFields.Rule> rules = new ArrayList<>();
        String refusal = recheck(c, p, rules);
        if (refusal != null) {
            end(row, node, epoch, EditLog.REFUSED, refusal, 0);
            return;
        }
        DataNode now = current(c);
        List<Map<String, Object>> moved = moved(c, now);
        if (!moved.isEmpty() && !c.override()) {
            end(row, node, epoch, EditLog.CONFLICTED, conflictWords(moved), 0);
            return;
        }
        String invalid = invalid(c, rules, now);
        if (invalid != null) {
            end(row, node, epoch, EditLog.REFUSED, invalid, 0);
            return;
        }
        Instant at = Instant.now();
        boolean source = "source".equals(c.target());
        long[] version = {0};
        log.finish(row, node, epoch, EditLog.APPLIED, moved.isEmpty() ? null : "applied over a newer value (Apply mine anyway): " + conflictWords(moved),
                source ? EditLog.CMD_PENDING : null, cn -> {
                    for (Change.Item it : c.items()) {
                        Object was = now == null ? null : EditPaths.get(now, it.path()).unwrap();
                        if ("owned".equals(c.target())) {
                            records.owned(cn, new EditRecords.Owned(c.kind(), c.entity(), it.path(), it.to(), c.id(), c.by(), at));
                        } else {
                            records.overlay(cn, new EditRecords.Overlay(c.id(), it.path(), c.kind(), c.entity(), it.to(), was, c.businessDate(), null,
                                    EditRecords.ACTIVE, c.by(), at));
                        }
                        pageRow(c, it).ifPresent(path -> {
                            try {
                                records.overlay(cn, new EditRecords.Overlay(c.id(), path, c.origin("pageKind"), c.origin("pageId"), it.to(), was,
                                        c.businessDate(), null, EditRecords.ACTIVE, c.by(), at));
                            } catch (java.sql.SQLException e) {
                                throw new IllegalStateException(e);
                            }
                        });
                    }
                    version[0] = records.bump(cn, c.key(), c.id(), at);
                });
        audit.record(c.by(), "edit-applied", c.key(), c.id() + " lane " + row.lane() + " seq " + row.seq() + ": " + c.target()
                + (source ? " (command " + c.command() + " pending at " + c.connector() + ")" : "") + ", " + itemsWords(c, p) + (c.override() ? ", over a newer value" : ""));
        tell(row, EditLog.APPLIED, null, version[0]);
    }

    /**
     * The submitter as they are now: their roles in the user directory today; for a person the directory does not hold (signed in with
     * a token whose roles the site trusts, {@code registered-users-only: false}), the roles they submitted with.
     */
    Principal principalOf(Change c) {
        Principal now = principals.apply(c.by());
        if (now != null) {
            return now;
        }
        String roles = c.origin("roles");
        return new Principal(c.by(), roles == null || roles.isBlank() ? List.of() : List.of(roles.split(",")));
    }

    /** The page row a form's record is shown on ({@code $.TRADES[TRADE_ID=MX-1].NOTIONAL}), when it is another kind's page. */
    static Optional<String> pageRow(Change c, Change.Item it) {
        String rows = c.origin("rows");
        String key = c.origin("rowKey");
        if (rows == null || key == null || c.origin("pageKind") == null || c.origin("pageId") == null || c.origin("pageKind").equals(c.kind())
                && c.origin("pageId").equals(c.entity())) {
            return Optional.empty();
        }
        String field = EditPaths.field(it.path());
        try {
            return Optional.of(EditPaths.ofRow(rows, key, c.entity(), field)).filter(EditPaths::valid);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private void end(EditLog.Row row, String node, long epoch, String state, String reason, long version) {
        log.finish(row, node, epoch, state, reason, null, null);
        audit.record(row.change().by(), "edit-" + state, row.change().key(), row.changeId() + ": " + reason);
        tell(row, state, reason, version);
    }

    private void tell(EditLog.Row row, String state, String reason, long version) {
        for (AfterApply a : after) {
            try {
                a.ended(row, state, reason, version);
            } catch (RuntimeException e) {
                LOG.warn("write-back: after {} of {}: {}", state, row.changeId(), e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ the checks

    /** Why the change may not be applied for its submitter now, or null. Fills {@code rules} with the field rules it applies. */
    String recheck(Change c, Principal p, List<EditableFields.Rule> rules) {
        EditSettings s = settings.get();
        if (!s.enabled()) {
            return "write-back was switched off before this change was applied";
        }
        if (!entitlements.mayEdit(p)) {
            return p.user() + " no longer holds the edit power";
        }
        if (!entitlements.mayOpen(p, c.kind())) {
            return p.user() + " may no longer open " + c.kind() + " records";
        }
        boolean isNew = "new".equals(c.origin("mode"));
        for (Change.Item it : c.items()) {
            String bind = EditPaths.field(it.path());
            Optional<EditableFields.Rule> r = fields.find(c.kind(), bind, c.origin("sutra"), c.origin("panel"));
            if (r.isEmpty()) {
                return bind + " of " + c.kind() + " is no longer editable: no Sutra's form with save: names it";
            }
            for (String f : fieldsOf(it.path())) {
                if (entitlements.masked(p, f)) {
                    return f + " is hidden for " + p.user() + ": a masked field is never written";
                }
            }
            Form form = r.get().form();
            List<String> held = entitlements.formRoles(p);
            if (isNew ? !form.enterableBy(r.get().field(), held) : !form.editableBy(r.get().field(), held)) {
                return r.get().field().label() + " is " + (r.get().field().readonly() ? "read-only" : "locked for " + p.user());
            }
            if (!Objects.equals(r.get().save().target(), c.target())) {
                return "the form now sends " + bind + " to " + r.get().save().target() + ", not " + c.target() + ": submit it again";
            }
            rules.add(r.get());
        }
        if (c.businessDate().isBefore(today.get()) && !holds(p, s.policy().restateRole())) {
            return "a change to a past business date is a restatement: it needs the " + s.policy().restateRole() + " role";
        }
        if (c.override() && !holds(p, s.policy().overrideRole())) {
            return "Apply mine anyway needs the " + s.policy().overrideRole() + " role";
        }
        return null;
    }

    static boolean holds(Principal p, String role) {
        return p.roles().contains("*") || p.roles().contains(role);
    }

    /** Every field name along a path ({@code LEGS}, {@code NOTIONAL}): a mask on any of them hides the value. */
    static List<String> fieldsOf(String path) {
        List<String> out = new ArrayList<>();
        for (EditPaths.Step s : EditPaths.steps(path)) {
            if (s.field() != null) {
                out.add(s.field());
            }
        }
        return out;
    }

    /** The record as it is now, overlays and owned values laid over it; null when no source holds it. */
    DataNode current(Change c) {
        AsOf asOf = c.businessDate().equals(today.get()) ? AsOf.LATEST : AsOf.of(c.businessDate());
        String rows = c.origin("rows");
        String key = c.origin("rowKey");
        if (rows != null && key != null && c.origin("pageKind") != null && c.origin("pageId") != null) {
            // a form's record shown as a row of a page (a book's trades): the person saw the row, so the row is what is compared
            try {
                EntityDocument page = router.fetch(EntityRef.of(c.origin("pageKind"), c.origin("pageId")), READ, asOf).join();
                String at = (rows.startsWith("$") ? rows : "$." + rows) + "[" + key + "=" + c.entity().replace("]", "") + "]";
                if (page != null && !page.deleted() && EditPaths.valid(at)) {
                    DataNode row = EditPaths.get(page.data(), at);
                    if (!row.isMissing()) {
                        return row;
                    }
                }
            } catch (RuntimeException e) {
                LOG.debug("write-back: the page of {} is not readable ({}); reading the record itself", c.key(), e.getMessage());
            }
        }
        try {
            EntityDocument d = router.fetch(EntityRef.of(c.kind(), c.entity()), READ, asOf).join();
            if (d != null && !d.deleted()) {
                return d.data();
            }
        } catch (RuntimeException e) {
            LOG.debug("write-back: {} is not readable from a source ({}); checking against the overlays and owned values", c.key(), e.getMessage());
        }
        Map<String, Object> mine = new LinkedHashMap<>();
        DataNode out = DataNode.of(mine);
        boolean any = false;
        for (EditRecords.Owned o : records.owned(c.kind(), c.entity())) {
            out = EditPaths.set(out, o.path(), o.value());
            any = true;
        }
        for (EditRecords.Overlay o : records.on(c.kind(), c.entity(), c.businessDate()).values()) {
            out = EditPaths.set(out, o.path(), o.value());
            any = true;
        }
        return any ? out : null;
    }

    /** The touched fields that moved since the person saw them: {path, saw, now, wanted}. */
    static List<Map<String, Object>> moved(Change c, DataNode now) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (now == null || "new".equals(c.origin("mode"))) {
            return out;
        }
        for (Change.Item it : c.items()) {
            DataNode n = EditPaths.get(now, it.path());
            Object current = n.isMissing() ? null : n.unwrap();
            if (!same(current, it.from())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", it.path());
                m.put("saw", it.from());
                m.put("now", current);
                m.put("wanted", it.to());
                out.add(m);
            }
        }
        return out;
    }

    static String conflictWords(List<Map<String, Object>> moved) {
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> m : moved) {
            b.append(b.isEmpty() ? "" : "; ").append(String.valueOf(m.get("path")).replace("$.", "")).append(": you saw ").append(m.get("saw"))
                    .append(", it is now ").append(m.get("now")).append(", you wanted ").append(m.get("wanted"));
        }
        return b.toString();
    }

    /** The form's rules over the record as it is now with the change applied; null when they hold. */
    static String invalid(Change c, List<EditableFields.Rule> rules, DataNode now) {
        if (rules.isEmpty()) {
            return null;
        }
        Form form = rules.get(0).form();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Form.Field f : form.fields()) {
            DataNode n = now == null ? DataNode.missing() : now.get(f.recordField());
            if (!n.isMissing() && !n.isNull() && !(n instanceof DataNode.Obj) && !(n instanceof DataNode.Arr)) {
                values.put(f.id(), n.unwrap());
            }
        }
        Set<String> checked = new HashSet<>();
        for (EditableFields.Rule r : rules) {
            Change.Item it = c.items().stream().filter(i -> EditPaths.field(i.path()).equals(r.field().recordField())).findFirst().orElse(null);
            if (it != null) {
                values.put(r.field().id(), it.to());
                checked.add(r.field().id());
            }
        }
        for (Form.Issue is : form.check(values, checked, LocalDate.now(java.time.ZoneOffset.UTC))) {
            if (is.error()) {
                return is.message();
            }
        }
        return null;
    }

    static boolean same(Object a, Object b) {
        boolean ba = a == null || a.toString().isBlank();
        boolean bb = b == null || b.toString().isBlank();
        if (ba || bb) {
            return ba && bb;
        }
        var na = FormExpr.number(a);
        var nb = FormExpr.number(b);
        if (na != null && nb != null) {
            return na.compareTo(nb) == 0;
        }
        return a.toString().equals(b.toString());
    }

    /** The items in words for the audit: path from → to, masked for whoever reads the audit as the submitter would see them. */
    private String itemsWords(Change c, Principal p) {
        StringBuilder b = new StringBuilder();
        for (Change.Item it : c.items()) {
            boolean hidden = fieldsOf(it.path()).stream().anyMatch(f -> entitlements.masked(p, f));
            b.append(b.isEmpty() ? "" : "; ").append(it.path().replace("$.", "")).append(' ')
                    .append(hidden ? DataNode.MASK : it.from()).append(" -> ").append(hidden ? DataNode.MASK : it.to());
        }
        return b.toString();
    }
}
