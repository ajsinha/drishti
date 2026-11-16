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
package com.ash.drishti.banking.var;

import com.ash.drishti.banking.compute.var.PositionRule;
import com.ash.drishti.banking.compute.var.ScopeExpr;
import com.ash.drishti.banking.compute.var.ScopeResolver;
import com.ash.drishti.banking.compute.var.VarBook;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.collab.InboxHub;
import com.ash.drishti.server.collab.InboxService;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.kernel.api.security.Principal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Managing the system baskets (VAR_GUIDE.md section 5.7): create, edit and retire them in the basket sheet, import and export them as
 * YAML. Every change needs the permission {@code var:system-baskets} (a role that lists it, or an administrator: ACCESS_MODEL.md
 * section 8) and is checked on save against the business date (a node no hierarchy holds, a rule over a column the positions do not
 * have, a position or trade the date does not hold: {@code DRS-5220} with the reason). Each change is one audit entry
 * ({@code var-system-basket-create}, {@code -edit}, {@code -retire}, {@code -import}) and, when the person changing it is not its owner,
 * an inbox notice to the owner (as an administrator's edit of a person's basket, {@link VarBasketDelete#tell}). The server has no reusable
 * four-eyes flow for content-store records (each maker-checker of the server is its own: stress-window proposals, report sign-off,
 * setting proposals), so a change takes effect at once and the audit trail and the owner's notice are its control.
 */
final class VarSystemBasketEdit {

    /** The permission that creates, edits, retires and imports system baskets. */
    static final String PERMISSION = "var:system-baskets";
    static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));
    /** The node words of the sheet's cells a hierarchy basket may be built from. */
    private static final java.util.Set<String> NODE_WORDS = java.util.Set.of("BANK", "DIV", "LE", "DESK", "BOOK", "NODE");

    private final VarService svc;
    private final Entitlements entitlements;
    private final Supplier<InboxService> inbox;
    private final Supplier<InboxHub> hub;

    VarSystemBasketEdit(VarService svc, Entitlements entitlements, Supplier<InboxService> inbox, Supplier<InboxHub> hub) {
        this.svc = svc;
        this.entitlements = entitlements;
        this.inbox = inbox;
        this.hub = hub;
    }

    private VarSystemBaskets store() {
        return svc.saved().system();
    }

    boolean mayManage(Principal p) {
        return entitlements.holds(p, PERMISSION);
    }

    // -------------------------------------------------------------------------------------------------------------------- reading

    /** The system baskets valid on the date (every one with {@code all}), each with its limit on the date, and the entries skipped. */
    Map<String, Object> list(Principal p, LocalDate asked, boolean all) {
        VarDate d = dateOrNull(asked);
        LocalDate on = d == null ? asked : d.date();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VarSystemBaskets.Basket b : (all ? store().all() : store().on(on)).values()) {
            Map<String, Object> m = b.toMap();
            Double limit = d == null ? null : d.portfolioLimits().get(b.id());
            m.put("limit", limit == null || limit.isNaN() ? null : limit);
            m.put("validOnDate", b.validOn(on));
            m.put("scopeText", "PORT:" + b.id());
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("businessDate", on == null ? null : on.toString());
        out.put("mayManage", mayManage(p));
        out.put("permission", PERMISSION);
        out.put("baskets", rows);
        out.put("skipped", store().skipped());
        return out;
    }

    VarSystemBaskets.Basket one(String id) {
        return store().get(id).orElseThrow(() -> new DrishtiException(ErrorCode.VAR_SYSTEM_BASKET_UNKNOWN, "no system basket " + id
                + ": PORT <GO> lists the system baskets"));
    }

    private VarDate dateOrNull(LocalDate asked) {
        try {
            return svc.date(asked);
        } catch (DrishtiException e) {
            return null;                                                // no date loaded: the definitions alone
        }
    }

    // -------------------------------------------------------------------------------------------------------------------- changing

    /**
     * Creates ({@code version} absent) or edits ({@code version}: the version opened, {@code 409} when it changed since) a system basket
     * from {@code body}: its fields, and for a {@code basket} its members, or the sheet's trade and position columns ({@code sheet}); a
     * {@code hierarchy} without {@code node} takes the one node the sheet names.
     */
    Map<String, Object> save(Principal p, Map<String, Object> body, LocalDate asked) {
        entitlements.requirePermission(p, PERMISSION);
        Map<String, Object> in = new LinkedHashMap<>(body);
        in.remove("retiredBy");
        if (in.get("owner") == null || String.valueOf(in.get("owner")).isBlank()) {
            in.put("owner", p.user());
        }
        VarDate d = dateOrNull(asked);
        fromSheet(in, d);
        VarSystemBaskets.Basket b = VarSystemBaskets.read(in);
        String why = d == null ? null : check(b, d);
        if (why != null) {
            throw VarSystemBaskets.invalid(why);
        }
        Integer version = in.get("version") instanceof Number n ? Integer.valueOf(n.intValue())
                : in.get("version") != null && String.valueOf(in.get("version")).matches("\\d{1,9}") ? Integer.valueOf(String.valueOf(in.get("version"))) : null;
        boolean create = version == null;
        VarSystemBaskets.Basket was = create ? null : store().get(b.id()).orElse(null);
        VarSystemBaskets.Basket saved = store().save(b, create, version, p.user());
        String action = create ? "create" : "edit";
        written(p.user(), action, saved, was);
        Map<String, Object> out = saved.toMap();
        out.put("checkedOn", d == null ? null : d.date().toString());
        return out;
    }

    /** Retires a system basket: valid until {@code validTo} (default: the business date before the one asked), kept for earlier dates. */
    Map<String, Object> retire(Principal p, String id, Map<String, Object> body, LocalDate asked) {
        entitlements.requirePermission(p, PERMISSION);
        Object to = body == null ? null : body.get("validTo");
        LocalDate until;
        try {
            until = to != null && !String.valueOf(to).isBlank() ? LocalDate.parse(String.valueOf(to))
                    : (asked != null ? asked : LocalDate.now(java.time.ZoneOffset.UTC)).minusDays(1);
        } catch (java.time.format.DateTimeParseException e) {
            throw VarSystemBaskets.invalid(id + ": validTo is a date YYYY-MM-DD, not '" + to + "'");
        }
        Integer version = body != null && body.get("version") instanceof Number n ? Integer.valueOf(n.intValue()) : null;
        String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason")).strip();
        VarSystemBaskets.Basket r = store().retire(id.toUpperCase(Locale.ROOT), until, version, p.user());
        svc.audit(p.user(), "var-system-basket-retire", r.id(), "valid until " + until + " version=" + r.version()
                + (reason == null || reason.isEmpty() ? "" : " reason=" + reason));
        tell(p.user(), r, "retired by " + p.user() + ": valid until " + until + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")"));
        return r.toMap();
    }

    /** The system baskets as a YAML file (import's form): every one, retired ones too. */
    String export() {
        StringBuilder out = new StringBuilder();
        out.append("# The VaR engine's system baskets (VAR_GUIDE.md section 5.7), exported ").append(java.time.Instant.now()).append(".\n");
        out.append("# Import: drishti.py var baskets import <this file> (a diff first; --apply writes; needs the permission var:system-baskets).\n");
        try {
            out.append(YAML.writeValueAsString(store().export()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return out.toString();
    }

    /** Imports a YAML file: the diff, and with {@code apply} the changes written (each audited, the summary audited once). */
    Map<String, Object> importYaml(Principal p, String text, boolean apply, LocalDate asked) {
        entitlements.requirePermission(p, PERMISSION);
        Object doc;
        try {
            doc = YAML.readValue(text == null ? "" : text, Object.class);
        } catch (java.io.IOException e) {
            throw VarSystemBaskets.invalid("the file is not YAML: " + e.getMessage());
        }
        VarDate d = dateOrNull(asked);
        Map<String, Object> out = store().importDoc(doc, apply, p.user(), d == null ? null : b -> check(b, d),
                (action, b) -> written(p.user(), action + "-import", b, null));
        out.put("checkedOn", d == null ? null : d.date().toString());
        if (apply) {
            svc.audit(p.user(), "var-system-basket-import", "system", "add=" + out.get("add") + " change=" + out.get("change") + " same="
                    + out.get("same") + " invalid=" + out.get("invalid"));
        }
        return out;
    }

    /** One audit entry for a basket written, and the owner told when someone else changed it. */
    private void written(String actor, String action, VarSystemBaskets.Basket b, VarSystemBaskets.Basket was) {
        boolean imported = action.endsWith("-import");
        String a = imported ? action.substring(0, action.length() - "-import".length()) : action;
        svc.audit(actor, "var-system-basket-" + a, b.id(), (imported ? "by import " : "") + "version=" + b.version() + " type=" + b.type()
                + " scope=" + cut(b.scope()) + " owner=" + b.owner() + " valid=" + b.validFrom() + ".." + (b.validTo() == null ? "" : b.validTo())
                + (was != null && !was.owner().equals(b.owner()) ? " owner-was=" + was.owner() : ""));
        tell(actor, b, (a.equals("create") ? "created" : "edited") + " by " + actor + (imported ? " (import)" : "") + ": now version " + b.version());
    }

    private void tell(String actor, VarSystemBaskets.Basket b, String what) {
        if (b.owner() != null && !b.owner().equals(actor)) {
            VarBasketDelete.tell(inbox.get(), hub.get(), b.owner(), actor, "The system basket \"" + b.name() + "\" (PORT:" + b.id() + ") was " + what);
        }
    }

    private static String cut(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    // -------------------------------------------------------------------------------------------------------------------- checks

    /** A sheet in the body becomes a basket's members, or a hierarchy's one node; its other parts are refused in words. */
    private void fromSheet(Map<String, Object> in, VarDate d) {
        if (!(in.get("sheet") instanceof Map<?, ?> s)) {
            return;
        }
        VarBasketSheet sheet = VarBasketSheet.read(s, svc.words().keySet());
        String type = String.valueOf(in.getOrDefault("type", "")).toLowerCase(Locale.ROOT);
        String id = String.valueOf(in.get("id"));
        if (type.equals("basket")) {
            if (sheet.wholeBank()) {
                throw VarSystemBaskets.invalid(id + ": a basket of positions and trades does not start from the whole bank");
            }
            List<Map<String, Object>> members = new ArrayList<>();
            for (VarBasketSheet.Cell c : sheet.cells()) {
                if (c.problem() != null) {
                    throw VarSystemBaskets.invalid(id + ": " + c.text() + " in the " + c.column() + " column: " + c.problem());
                }
                if (c.minus() || !(c.word().equals("POS") || c.word().equals("TRD"))) {
                    throw VarSystemBaskets.invalid(id + ": a system basket of type basket holds positions and trades added (+); " + c.text()
                            + " in the " + c.column() + " column is a node (type hierarchy), a condition (type rule), or a minus");
                }
                members.add(Map.of(c.word().equals("POS") ? "position" : "trade", c.id()));
            }
            in.put("members", members);
        } else if (type.equals("hierarchy") && (in.get("node") == null || String.valueOf(in.get("node")).isBlank())) {
            List<VarBasketSheet.Cell> cells = sheet.cells();
            if (cells.size() != 1 || cells.get(0).minus() || d == null) {
                throw VarSystemBaskets.invalid(id + ": a hierarchy basket names its node (MGMT~DESK-RATES), or the sheet holds that one node");
            }
            VarBasketSheet.Cell c = cells.get(0);
            String node = null;
            if (NODE_WORDS.contains(c.word()) || svc.words().containsKey(c.word())) {
                node = new ScopeResolver(d.book(), svc.definitions(null, d), svc.words()).nodeId(new ScopeExpr.Ref(c.word(), c.id()));
            }
            if (node == null) {
                throw VarSystemBaskets.invalid(id + ": " + c.text() + " is not a node of a hierarchy on " + d.date());
            }
            in.put("node", node);
        }
        in.remove("sheet");
    }

    /** What makes {@code b} wrong on the date, in words (null: nothing). */
    static String check(VarSystemBaskets.Basket b, VarDate d) {
        VarBook book = d.book();
        String on = " on " + d.date();
        switch (b.type()) {
            case "hierarchy" -> {
                return book.node(b.node()) == null ? b.id() + ": the node " + b.node() + " is not a node of a hierarchy" + on : null;
            }
            case "rule" -> {
                for (String f : new PositionRule(b.rule()).fields()) {
                    if (book.positions() != null && !book.positions().has(f)) {
                        return b.id() + ": the rule names " + f + ", not a column of the positions" + on;
                    }
                }
                return null;
            }
            default -> {
                for (VarSystemBaskets.Member m : b.members()) {
                    if (m.position() != null && book.positionIndexAnyCase(m.position()) < 0) {
                        return b.id() + ": the position " + m.position() + " is not held" + on;
                    }
                    if (m.trade() != null && book.tradeOrdinalAnyCase(m.trade()) < 0) {
                        return b.id() + ": the trade " + m.trade() + " has no P&L vector" + on;
                    }
                }
                return null;
            }
        }
    }
}
