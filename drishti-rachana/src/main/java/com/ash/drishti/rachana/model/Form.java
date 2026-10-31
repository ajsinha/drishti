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
package com.ash.drishti.rachana.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A panel's {@code form:} (SUTRA_NEXT.md 10): the typed fields people may edit, the rules they must keep (an error stops the
 * draft, a warning is said and kept), the hints computed as they type, and how the form opens: {@code edit: beside} (a form beside
 * the table for the clicked row), {@code edit: cells} (the table's cells), {@code new: drawer} (a guided drawer in steps). Besides text,
 * numbers, dates, choices and lookups, a field may be {@code money} (an amount in a fixed currency or the currency another field holds),
 * {@code datetime} (a date and a time in a named time zone) or {@code entity} (a linked record of any pack's kind, picked by search: its
 * id is kept, its label shown); a field's {@code default} is a value or, written {@code =notional * 0.01}, an expression. Masks
 * and roles decide what each person may edit: a masked field is never editable or shown, a field with {@code editable-by} is
 * locked for anyone else, a {@code readonly} field says why. Saving keeps a draft only; 2.8.0 writes through connectors.
 *
 * @param title the form's heading (the editor says "Edit {key}" when absent)
 * @param key the field that names the record (needed by the edit modes)
 * @param edit how a record is edited: {@code beside}, {@code cells}, or null (not editable)
 * @param create how a new record starts: {@code drawer}, or null
 * @param newLabel the New button's text
 * @param roles the roles that may edit every field without its own {@code editable-by} (empty: anyone who sees the screen)
 * @param steps the drawer's steps, in order (empty: one step)
 * @param fields the fields, in order
 * @param rules the field and cross-field rules
 * @param hints the values computed as people type
 * @param save where a submitted form goes (WRITE_BACK.md › The design and its reasons, section 18.1), or null: the form keeps drafts only
 */
public record Form(String title, String key, String edit, String create, String newLabel, List<String> roles, List<Step> steps,
        List<Field> fields, List<Rule> rules, List<Hint> hints, Save save) {

    /** A form without {@code save:} (drafts only). */
    public Form(String title, String key, String edit, String create, String newLabel, List<String> roles, List<Step> steps,
            List<Field> fields, List<Rule> rules, List<Hint> hints) {
        this(title, key, edit, create, newLabel, roles, steps, fields, rules, hints, null);
    }

    /**
     * Where a submitted form goes (WRITE_BACK.md › The design and its reasons, sections 5 and 18.1): {@code target} {@code overlay} (a correction kept in Drishti, laid
     * over the source), {@code owned} (Drishti is the record's system of record) or {@code source} (a command of the connector's
     * {@code writes:}: {@code command} for an edit, {@code create} for a new record); {@code kind} is the kind of the records the form
     * edits (the page's kind when absent: a form on a book's trades names {@code trade}); {@code approval} the approvals it needs
     * (0, 1 or 2; the site's policy may raise it, never lower it); {@code reason} true when a reason must come with it.
     */
    public record Save(String kind, String target, String connector, String command, String create, int approval, boolean reason) {
        public static final List<String> TARGETS = List.of("overlay", "owned", "source");
    }

    public static final String KEY = "form";
    public static final List<String> TYPES = List.of("text", "number", "date", "enum", "bool", "textarea", "lookup", "money",
            "datetime", "entity");
    /** The types whose {@code options} read a source. */
    public static final List<String> SOURCED = List.of("lookup", "entity");
    public static final List<String> EDIT_MODES = List.of("beside", "cells");
    public static final List<String> NEW_MODES = List.of("drawer");
    public static final int MAX_FIELDS = 60;
    public static final int MAX_TEXT = 4000;

    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");
    private static final Pattern BIND = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
    private static final Pattern ROLE = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,39}$");
    private static final Set<String> TOP = Set.of("title", "key", "edit", "new", "new-label", "roles", "steps", "fields", "rules", "hints", "save");
    private static final Set<String> SAVE = Set.of("kind", "target", "connector", "command", "create", "approval", "reason");
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final Set<String> FIELD = Set.of("id", "label", "type", "bind", "required", "min", "max", "pattern", "max-length",
            "options", "editable-by", "readonly", "reason", "step", "placeholder", "fmt", "new", "currency", "tz", "default");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern AMOUNT = Pattern.compile("^\\s*(-?[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(k|m|mm|bn|b)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Set<String> LOOKUP = Set.of("source", "value", "label", "carry");
    private static final Set<String> SOURCE = Set.of("pack", "kind", "where", "search", "limit");
    private static final Set<String> RULE = Set.of("rule", "message", "severity", "fields");
    private static final Set<String> HINT = Set.of("label", "value", "of", "fmt", "step");

    /** One step of the drawer. */
    public record Step(String id, String label) {}

    /** One choice of an enum field. */
    public record Option(String value, String label) {}

    /**
     * Where a lookup's (or an entity field's) choices come from: the entities of a kind a condition keeps, as the viewer may see them.
     * {@code value} names the field kept; an entity field may leave it out, and the entity's own id is kept.
     */
    public record Lookup(Wiring.Source source, String value, String label, List<String> carry) {}

    /**
     * One field.
     *
     * @param bind the record's field it edits (upper snake, as the record names it); the field's id when absent
     * @param editableBy the roles that may edit it (empty: the form's {@code roles})
     * @param readonly shown, never edited; {@code reason} says why
     * @param onNew whether the New drawer asks for it (default true)
     * @param currency a money field's currency: a code (USD) or the id of the field holding it
     * @param tz a datetime field's time zone (UTC when absent)
     * @param defaultValue what a new record starts with: a value, or {@code =<expression>} over the other fields
     */
    public record Field(String id, String label, String type, String bind, boolean required, Object min, Object max, String pattern,
            Integer maxLength, List<Option> options, Lookup lookup, List<String> editableBy, boolean readonly, String reason, String step,
            String placeholder, String fmt, boolean onNew, String currency, String tz, Object defaultValue) {

        /** The record's field this one edits. */
        public String recordField() {
            return bind != null ? bind : id;
        }

        /** The default's expression when it is written {@code =...}, else null. */
        public FormExpr defaultExpr() {
            return defaultValue instanceof String s && s.startsWith("=") ? FormExpr.parse(s.substring(1)) : null;
        }

        /** The time zone a datetime field is entered in. */
        public String zone() {
            return tz == null ? "UTC" : tz;
        }
    }

    /** A rule that must hold; {@code severity} error stops the draft, warn is said and kept. */
    public record Rule(FormExpr rule, String message, String severity, List<String> fields) {
        public boolean error() {
            return !"warn".equals(severity);
        }
    }

    /** A value computed as people type: {@code value} (and, with {@code of}, "value of of (n %)"). */
    public record Hint(String label, FormExpr value, FormExpr of, String fmt, String step) {}

    /** What is wrong with a value or a rule, in plain words. */
    public record Issue(String field, String message, String severity) {
        public boolean error() {
            return !"warn".equals(severity);
        }
    }

    // ---- reading ------------------------------------------------------------------------------------------------------------------

    /** The panel option back as a Form; empty when absent or unreadable (the Sutra would not have loaded). */
    public static Optional<Form> fromOption(Object raw) {
        if (!(raw instanceof Map<?, ?>) || !problems(raw).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(read((Map<?, ?>) raw));
    }

    /** What is wrong with a written {@code form:}, in words (empty when it reads). */
    public static List<String> problems(Object raw) {
        List<String> out = new ArrayList<>();
        if (!(raw instanceof Map<?, ?> m)) {
            out.add("'form' is a mapping: { fields: [...], rules: [...], edit: beside|cells, new: drawer }");
            return out;
        }
        unknown(m, TOP, "form", out);
        Object edit = m.get("edit");
        if (edit != null && !EDIT_MODES.contains(edit.toString())) {
            out.add("'form.edit' is beside (a form beside the table) or cells (edit in the table's cells), not '" + edit + "'");
        }
        Object create = m.get("new");
        if (create != null && !NEW_MODES.contains(create.toString())) {
            out.add("'form.new' is drawer (a guided drawer in steps), not '" + create + "'");
        }
        if (edit == null && create == null) {
            out.add("a form says how it opens: edit: beside, edit: cells or new: drawer");
        }
        roles(m.get("roles"), "form.roles", out);
        Set<String> steps = new HashSet<>();
        if (m.get("steps") != null) {
            if (!(m.get("steps") instanceof List<?> l) || l.isEmpty()) {
                out.add("'form.steps' is a list of { id, label }");
            } else {
                for (Object s : l) {
                    if (!(s instanceof Map<?, ?> sm) || !(sm.get("id") instanceof String id) || !ID.matcher(id).matches()) {
                        out.add("a step is { id: <lower_snake>, label: <text> }");
                    } else if (!steps.add(id)) {
                        out.add("step '" + id + "' is named twice");
                    }
                }
            }
        }
        Map<String, String> types = new LinkedHashMap<>();
        if (!(m.get("fields") instanceof List<?> fl) || fl.isEmpty()) {
            out.add("'form.fields' lists the fields: { id, label, type }");
        } else if (fl.size() > MAX_FIELDS) {
            out.add("a form has at most " + MAX_FIELDS + " fields");
        } else {
            for (Object f : fl) {
                field(f, steps, types, out);
            }
            for (Object f : fl) {                               // what reads other fields: known once every field is
                if (!(f instanceof Map<?, ?> fm)) {
                    continue;
                }
                String id = String.valueOf(fm.get("id"));
                if (fm.get("currency") instanceof String c && !CURRENCY.matcher(c).matches() && !types.containsKey(c)) {
                    out.add("field " + id + ": currency is a code (USD) or the id of the field holding it, not '" + c + "'");
                }
                if (fm.get("default") instanceof String d && d.startsWith("=")) {
                    expr(d.substring(1), types.keySet(), "field " + id + ": default", out);
                }
            }
        }
        Object key = m.get("key");
        if (key != null && !types.containsKey(key.toString())) {
            out.add("'form.key' names one of the form's fields, not '" + key + "'");
        }
        if (edit != null && key == null) {
            out.add("a form that edits records names the field that identifies one: key: <field id>");
        }
        if (m.get("rules") != null) {
            if (!(m.get("rules") instanceof List<?> rl)) {
                out.add("'form.rules' is a list of { rule, message, severity }");
            } else {
                for (Object r : rl) {
                    rule(r, types.keySet(), out);
                }
            }
        }
        if (m.get("save") != null) {
            save(m.get("save"), create != null, out);
        }
        if (m.get("hints") != null) {
            if (!(m.get("hints") instanceof List<?> hl)) {
                out.add("'form.hints' is a list of { label, value, of, fmt, step }");
            } else {
                for (Object h : hl) {
                    hint(h, types.keySet(), steps, out);
                }
            }
        }
        return out;
    }

    private static void save(Object raw, boolean hasNew, List<String> out) {
        if (!(raw instanceof Map<?, ?> m)) {
            out.add("'form.save' is a mapping: { target: overlay|owned|source, command, create, approval, reason }");
            return;
        }
        unknown(m, SAVE, "form.save", out);
        Object target = m.get("target");
        if (target == null || !Save.TARGETS.contains(target.toString())) {
            out.add("'form.save.target' is overlay (a correction kept beside the source's value), owned (the server is the record's system of record) or source (a connector's writes: command), not '"
                    + target + "'");
        }
        for (String k : List.of("kind", "connector", "command", "create")) {
            if (m.get(k) != null && !NAME.matcher(m.get(k).toString()).matches()) {
                out.add("'form.save." + k + "' is a name in lower case, digits and -, not '" + m.get(k) + "'");
            }
        }
        if ("source".equals(String.valueOf(target)) && m.get("command") == null) {
            out.add("'form.save.command' names the connector's writes: command that amends a record (target: source)");
        }
        if ("source".equals(String.valueOf(target)) && hasNew && m.get("create") == null) {
            out.add("'form.save.create' names the connector's writes: command that creates a record: this form starts new ones (new: drawer)");
        }
        Object approval = m.get("approval");
        if (approval != null && approvals(approval.toString()) < 0) {
            out.add("'form.save.approval' is none, one or two, not '" + approval + "'");
        }
        Object reason = m.get("reason");
        if (reason != null && !List.of("required", "optional").contains(reason.toString())) {
            out.add("'form.save.reason' is required or optional, not '" + reason + "'");
        }
    }

    /** {@code none}, {@code one} or {@code two} (also {@code four-eyes} for one) as a number; -1 when it is none of them. */
    static int approvals(String v) {
        return switch (v) {
            case "none", "0" -> 0;
            case "one", "1", "four-eyes" -> 1;
            case "two", "2" -> 2;
            default -> -1;
        };
    }

    private static void field(Object f, Set<String> steps, Map<String, String> types, List<String> out) {
        if (!(f instanceof Map<?, ?> m) || !(m.get("id") instanceof String id) || !ID.matcher(id).matches()) {
            out.add("a field is { id: <lower_snake>, label, type }: the id is lower case, letters, digits and _");
            return;
        }
        unknown(m, FIELD, "field " + id, out);
        String type = m.get("type") == null ? "text" : m.get("type").toString();
        if (!TYPES.contains(type)) {
            out.add("field " + id + ": type is one of " + String.join(", ", TYPES) + ", not '" + type + "'");
        }
        if (types.put(id, type) != null) {
            out.add("field '" + id + "' is named twice");
        }
        if (m.get("bind") != null && !BIND.matcher(m.get("bind").toString()).matches()) {
            out.add("field " + id + ": bind names the record's field (COUNTERPARTY), not '" + m.get("bind") + "'");
        }
        for (String b : List.of("required", "readonly", "new")) {
            if (m.containsKey(b) && !(m.get(b) instanceof Boolean)) {
                out.add("field " + id + ": " + b + " is true or false");
            }
        }
        boolean numeric = type.equals("number") || type.equals("money");
        for (String lim : List.of("min", "max")) {
            Object v = m.get(lim);
            if (v == null) {
                continue;
            }
            if (numeric && !(v instanceof Number)) {
                out.add("field " + id + ": " + lim + " is a number");
            } else if (type.equals("date") && !(v.toString().equals("today") || isDate(v.toString()))) {
                out.add("field " + id + ": " + lim + " is a date (2026-12-31) or today");
            } else if (type.equals("datetime") && !(v.toString().equals("today") || dateTime(v.toString()) != null)) {
                out.add("field " + id + ": " + lim + " is a date and time (2026-12-31T17:00) or today");
            } else if (!numeric && !type.equals("date") && !type.equals("datetime")) {
                out.add("field " + id + ": " + lim + " applies to number, money, date and datetime fields");
            }
        }
        if (type.equals("money") && !(m.get("currency") instanceof String)) {
            out.add("field " + id + ": a money field names its currency: currency: USD, or the field holding it (currency: ccy)");
        } else if (!type.equals("money") && m.get("currency") != null) {
            out.add("field " + id + ": currency applies to money fields");
        }
        if (m.get("tz") != null) {
            if (!type.equals("datetime")) {
                out.add("field " + id + ": tz applies to datetime fields");
            } else if (!zone(m.get("tz").toString())) {
                out.add("field " + id + ": tz is a time zone (Europe/London, America/New_York, UTC), not '" + m.get("tz") + "'");
            }
        }
        Object def = m.get("default");
        if (def != null && !(def instanceof String || def instanceof Number || def instanceof Boolean)) {
            out.add("field " + id + ": default is a value, or =<expression> over the other fields (=notional * 0.01)");
        }
        if (m.get("pattern") != null) {
            try {
                Pattern.compile(m.get("pattern").toString());
            } catch (PatternSyntaxException e) {
                out.add("field " + id + ": pattern is not a regular expression (" + e.getDescription() + ")");
            }
        }
        if (m.get("max-length") != null && !(m.get("max-length") instanceof Number n && n.intValue() >= 1 && n.intValue() <= MAX_TEXT)) {
            out.add("field " + id + ": max-length is a whole number, 1 to " + MAX_TEXT);
        }
        roles(m.get("editable-by"), "field " + id + ": editable-by", out);
        if (m.get("step") != null && !steps.contains(m.get("step").toString())) {
            out.add("field " + id + ": step names one of form.steps, not '" + m.get("step") + "'");
        }
        Object opts = m.get("options");
        if (type.equals("enum")) {
            if (!(opts instanceof List<?> l) || l.isEmpty()) {
                out.add("field " + id + ": an enum lists its options: [Pending, Validated] or [{ value, label }]");
            } else {
                for (Object o : l) {
                    if (!(o instanceof String || o instanceof Number || o instanceof Boolean || o instanceof Map<?, ?> om && om.get("value") != null)) {
                        out.add("field " + id + ": an option is a value or { value, label }");
                    }
                }
            }
        } else if (SOURCED.contains(type)) {
            boolean entity = type.equals("entity");
            if (!(opts instanceof Map<?, ?> lm)) {
                out.add(entity ? "field " + id + ": an entity field picks a record of a kind: options: { source: { pack, kind }, label, carry }"
                        : "field " + id + ": a lookup reads its options from a source: options: { source: { kind, where }, value, label, carry }");
            } else {
                unknown(lm, LOOKUP, "field " + id + " options", out);
                if (!(lm.get("source") instanceof Map<?, ?> sm) || !(sm.get("kind") instanceof String)) {
                    out.add("field " + id + ": options.source names a kind: { pack, kind, where }");
                } else {
                    unknown(sm, SOURCE, "field " + id + " options.source", out);
                    if (entity ? sm.get("where") != null && sm.get("search") != null : (sm.get("where") == null) == (sm.get("search") == null)) {
                        out.add("field " + id + ": options.source reads the entities a condition keeps (where) or a search (search): name one");
                    }
                }
                if (entity && lm.get("value") == null) {
                    // the entity's own id is kept
                } else if (!(lm.get("value") instanceof String v) || !BIND.matcher(v).matches()) {
                    out.add("field " + id + ": options.value names the field of each entity that is the value (COUNTERPARTY)");
                }
                if (lm.get("carry") != null && !(lm.get("carry") instanceof List<?>)) {
                    out.add("field " + id + ": options.carry lists the fields the hints read ([LIMIT, USED])");
                }
            }
        } else if (opts != null) {
            out.add("field " + id + ": options apply to enum, lookup and entity fields");
        }
    }

    private static void rule(Object r, Set<String> fields, List<String> out) {
        if (!(r instanceof Map<?, ?> m) || !(m.get("rule") instanceof String text) || !(m.get("message") instanceof String)) {
            out.add("a rule is { rule: <expression that must hold>, message: <what to do, in words>, severity: error|warn }");
            return;
        }
        unknown(m, RULE, "rule '" + text + "'", out);
        if (m.get("severity") != null && !List.of("error", "warn").contains(m.get("severity").toString())) {
            out.add("rule '" + text + "': severity is error (stops the draft) or warn (said, kept), not '" + m.get("severity") + "'");
        }
        expr(text, fields, "rule", out);
        if (m.get("fields") instanceof List<?> l) {
            for (Object f : l) {
                if (!fields.contains(String.valueOf(f))) {
                    out.add("rule '" + text + "': fields names the form's fields, not '" + f + "'");
                }
            }
        } else if (m.get("fields") != null) {
            out.add("rule '" + text + "': fields is a list of the fields the message is shown under");
        }
    }

    private static void hint(Object h, Set<String> fields, Set<String> steps, List<String> out) {
        if (!(h instanceof Map<?, ?> m) || !(m.get("label") instanceof String) || !(m.get("value") instanceof String v)) {
            out.add("a hint is { label, value: <expression>, of: <expression>, fmt, step }");
            return;
        }
        unknown(m, HINT, "hint " + m.get("label"), out);
        expr(v, fields, "hint", out);
        if (m.get("of") != null) {
            expr(m.get("of").toString(), fields, "hint", out);
        }
        if (m.get("step") != null && !steps.contains(m.get("step").toString())) {
            out.add("hint " + m.get("label") + ": step names one of form.steps, not '" + m.get("step") + "'");
        }
    }

    private static void expr(String text, Set<String> fields, String what, List<String> out) {
        try {
            for (String n : FormExpr.parse(text).names()) {
                if (!fields.contains(n)) {
                    out.add(what + " '" + text + "' reads '" + n + "', which is not a field of the form");
                }
            }
        } catch (IllegalArgumentException e) {
            out.add(what + " '" + text + "': " + e.getMessage());
        }
    }

    private static void roles(Object raw, String where, List<String> out) {
        if (raw == null) {
            return;
        }
        if (!(raw instanceof List<?> l) || l.stream().anyMatch(r -> !(r instanceof String s) || !ROLE.matcher(s).matches())) {
            out.add("'" + where + "' is a list of role names ([trader, risk-manager])");
        }
    }

    private static void unknown(Map<?, ?> m, Set<String> known, String where, List<String> out) {
        for (Object k : m.keySet()) {
            if (!known.contains(String.valueOf(k))) {
                out.add("'" + k + "' is not a key of " + where + " (it takes " + String.join(", ", new java.util.TreeSet<>(known)) + ")");
            }
        }
    }

    /** A date and time as written (2026-10-24T14:30, seconds optional), or null. */
    static LocalDateTime dateTime(String s) {
        try {
            return s.length() >= 16 && s.charAt(10) == 'T' ? LocalDateTime.parse(s) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean zone(String z) {
        try {
            ZoneId.of(z);
            return true;
        } catch (java.time.DateTimeException e) {
            return false;
        }
    }

    /** An amount as people type it: 1,000,000, 1.5m, 250k, 2bn (a Number as it is); null when it does not read. */
    public static BigDecimal amount(Object v) {
        if (v instanceof Number) {
            return FormExpr.number(v);
        }
        if (v == null) {
            return null;
        }
        var m = AMOUNT.matcher(v.toString());
        if (!m.matches()) {
            return null;
        }
        BigDecimal x = new BigDecimal(m.group(1).replace(",", ""));
        String u = m.group(2) == null ? "" : m.group(2).toLowerCase(java.util.Locale.ROOT);
        return switch (u) {
            case "k" -> x.scaleByPowerOfTen(3);
            case "m", "mm" -> x.scaleByPowerOfTen(6);
            case "bn", "b" -> x.scaleByPowerOfTen(9);
            default -> x;
        };
    }

    static boolean isDate(String s) {
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static Form read(Map<?, ?> m) {
        List<Step> steps = new ArrayList<>();
        if (m.get("steps") instanceof List<?> l) {
            l.forEach(s -> steps.add(new Step(str(((Map<?, ?>) s).get("id")), str(((Map<?, ?>) s).get("label")))));
        }
        List<Field> fields = new ArrayList<>();
        for (Object o : (List<?>) m.get("fields")) {
            Map<?, ?> f = (Map<?, ?>) o;
            String type = f.get("type") == null ? "text" : f.get("type").toString();
            List<Option> options = new ArrayList<>();
            Lookup lookup = null;
            if (type.equals("enum")) {
                for (Object op : (List<?>) f.get("options")) {
                    options.add(op instanceof Map<?, ?> om ? new Option(str(om.get("value")), om.get("label") == null ? str(om.get("value")) : str(om.get("label")))
                            : new Option(str(op), str(op)));
                }
            } else if (SOURCED.contains(type)) {
                Map<?, ?> lm = (Map<?, ?>) f.get("options");
                Map<?, ?> sm = (Map<?, ?>) lm.get("source");
                int limit = sm.get("limit") instanceof Number n ? Math.max(1, Math.min(Wiring.Source.MAX_LIMIT, n.intValue())) : Wiring.Source.MAX_LIMIT;
                Wiring.Source src = new Wiring.Source(str(sm.get("pack")), str(sm.get("kind")), null, str(sm.get("where")), str(sm.get("search")), limit, null);
                lookup = new Lookup(src, str(lm.get("value")), lm.get("label") == null ? str(lm.get("value")) : str(lm.get("label")), strs(lm.get("carry")));
            }
            Integer maxLength = f.get("max-length") instanceof Number n ? n.intValue() : null;
            fields.add(new Field(str(f.get("id")), f.get("label") == null ? str(f.get("id")) : str(f.get("label")), type, str(f.get("bind")),
                    Boolean.TRUE.equals(f.get("required")), f.get("min"), f.get("max"), str(f.get("pattern")), maxLength, options, lookup,
                    strs(f.get("editable-by")), Boolean.TRUE.equals(f.get("readonly")), str(f.get("reason")), str(f.get("step")),
                    str(f.get("placeholder")), str(f.get("fmt")), !Boolean.FALSE.equals(f.get("new")), str(f.get("currency")), str(f.get("tz")),
                    f.get("default")));
        }
        List<Rule> rules = new ArrayList<>();
        if (m.get("rules") instanceof List<?> l) {
            for (Object o : l) {
                Map<?, ?> r = (Map<?, ?>) o;
                rules.add(new Rule(FormExpr.parse(str(r.get("rule"))), str(r.get("message")), r.get("severity") == null ? "error" : str(r.get("severity")),
                        strs(r.get("fields"))));
            }
        }
        List<Hint> hints = new ArrayList<>();
        if (m.get("hints") instanceof List<?> l) {
            for (Object o : l) {
                Map<?, ?> h = (Map<?, ?>) o;
                hints.add(new Hint(str(h.get("label")), FormExpr.parse(str(h.get("value"))), h.get("of") == null ? null : FormExpr.parse(str(h.get("of"))),
                        str(h.get("fmt")), str(h.get("step"))));
            }
        }
        Save save = null;
        if (m.get("save") instanceof Map<?, ?> sv) {
            save = new Save(str(sv.get("kind")), str(sv.get("target")), str(sv.get("connector")), str(sv.get("command")), str(sv.get("create")),
                    sv.get("approval") == null ? 0 : Math.max(0, approvals(sv.get("approval").toString())), "required".equals(str(sv.get("reason"))));
        }
        return new Form(str(m.get("title")), str(m.get("key")), str(m.get("edit")), str(m.get("new")), str(m.get("new-label")), strs(m.get("roles")),
                steps, fields, rules, hints, save);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static List<String> strs(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    // ---- checking values ----------------------------------------------------------------------------------------------------------

    /**
     * A new record's values with the defaults filled in where nothing was given: a written value as it is, an expression worked out
     * over the values (and the defaults before it). {@code fill} names the fields that may take a default (the ones the person may enter).
     */
    public Map<String, Object> withDefaults(Map<String, Object> values, Set<String> fill, LocalDate today) {
        Map<String, Object> out = new LinkedHashMap<>(values);
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Field f : fields) {
            Object v = out.get(f.id());
            typed.put(f.id(), v == null || v.toString().isBlank() ? null : typed(f, v));
        }
        for (Field f : fields) {
            Object v = out.get(f.id());
            if (f.defaultValue() == null || !fill.contains(f.id()) || v != null && !v.toString().isBlank()) {
                continue;
            }
            FormExpr e = f.defaultExpr();
            Object d = e == null ? f.defaultValue() : e.eval(typed, today);
            if (d == null || d.toString().isBlank()) {
                continue;
            }
            if (d instanceof BigDecimal b) {
                d = b.setScale(Math.min(Math.max(b.scale(), 0), 6), java.math.RoundingMode.HALF_UP).stripTrailingZeros();
            }
            out.put(f.id(), d);
            typed.put(f.id(), typed(f, d));
        }
        return out;
    }

    public Optional<Field> field(String id) {
        return fields.stream().filter(f -> f.id().equals(id)).findFirst();
    }

    /** Whether a person with these roles may edit the field (masks aside): not read-only, and a role it names when it names any. */
    public boolean editableBy(Field f, List<String> held) {
        return !f.readonly() && allowed(f, held);
    }

    /**
     * Whether a person with these roles may give the field a value in a new record (the drawer): a read-only field is one a record
     * keeps once it exists, so a new record may set it when the drawer asks for it ({@code new}, default true).
     */
    public boolean enterableBy(Field f, List<String> held) {
        return f.onNew() && allowed(f, held);
    }

    private boolean allowed(Field f, List<String> held) {
        List<String> who = f.editableBy().isEmpty() ? roles : f.editableBy();
        return who.isEmpty() || held.contains("*") || held.stream().anyMatch(who::contains);
    }

    /**
     * Checks one record's values as the server does (the browser runs the same checks): each value's type, required, min and max,
     * pattern, length and options, then every rule. {@code checked} names the fields whose own checks run (the ones the person may
     * edit); the rules read every value given. Values come as JSON reads them: text, numbers, true or false.
     */
    public List<Issue> check(Map<String, Object> values, Set<String> checked, LocalDate today) {
        List<Issue> out = new ArrayList<>();
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Field f : fields) {
            Object v = values.get(f.id());
            boolean blank = v == null || v.toString().isBlank();
            if (!checked.contains(f.id())) {
                typed.put(f.id(), blank ? null : typed(f, v));
                continue;
            }
            if (blank) {
                if (f.required()) {
                    out.add(new Issue(f.id(), f.label() + " is required", "error"));
                }
                continue;
            }
            Object t = typed(f, v);
            typed.put(f.id(), t);
            String why = valueProblem(f, v, t, today);
            if (why != null) {
                out.add(new Issue(f.id(), why, "error"));
            }
        }
        for (Rule r : rules) {
            if (!r.rule().holds(typed, today)) {
                out.add(new Issue(r.fields().isEmpty() ? null : r.fields().get(0), r.message(), r.severity()));
            }
        }
        return out;
    }

    private static Object typed(Field f, Object v) {
        return switch (f.type()) {
            case "number", "money" -> amount(v);
            case "bool" -> v instanceof Boolean b ? b : Boolean.valueOf("true".equalsIgnoreCase(v.toString()));
            default -> v.toString();
        };
    }

    private String valueProblem(Field f, Object raw, Object t, LocalDate today) {
        String s = raw.toString();
        switch (f.type()) {
            case "number", "money" -> {
                if (!(t instanceof BigDecimal n)) {
                    return f.label() + (f.type().equals("money") ? " is an amount (1,000,000, 1.5m or 250k)" : " is a number");
                }
                BigDecimal min = FormExpr.number(f.min());
                BigDecimal max = FormExpr.number(f.max());
                if (min != null && n.compareTo(min) < 0) {
                    return f.label() + " is at least " + plain(min);
                }
                if (max != null && n.compareTo(max) > 0) {
                    return f.label() + " is at most " + plain(max);
                }
            }
            case "date" -> {
                if (!isDate(s)) {
                    return f.label() + " is a date (2026-12-31)";
                }
                String min = dateLimit(f.min(), today);
                String max = dateLimit(f.max(), today);
                if (min != null && s.compareTo(min) < 0) {
                    return f.label() + " is on or after " + min;
                }
                if (max != null && s.compareTo(max) > 0) {
                    return f.label() + " is on or before " + max;
                }
            }
            case "datetime" -> {
                LocalDateTime at = dateTime(s);
                if (at == null) {
                    return f.label() + " is a date and time (2026-12-31T17:00)";
                }
                String min = dateLimit(f.min(), today);
                String max = dateLimit(f.max(), today);
                if (min != null && at.isBefore(min.length() == 10 ? LocalDate.parse(min).atStartOfDay() : dateTime(min))) {
                    return f.label() + " is on or after " + min.replace('T', ' ') + " " + f.zone();
                }
                if (max != null && at.isAfter(max.length() == 10 ? LocalDate.parse(max).atStartOfDay() : dateTime(max))) {
                    return f.label() + " is on or before " + max.replace('T', ' ') + " " + f.zone();
                }
            }
            case "bool" -> {
                if (!(raw instanceof Boolean) && !s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")) {
                    return f.label() + " is yes or no";
                }
            }
            case "enum" -> {
                if (f.options().stream().noneMatch(o -> o.value().equals(s))) {
                    return f.label() + " is one of " + String.join(", ", f.options().stream().map(Option::label).toList());
                }
            }
            default -> { }
        }
        int max = f.maxLength() != null ? f.maxLength() : MAX_TEXT;
        if (s.length() > max) {
            return f.label() + " is at most " + max + " characters";
        }
        if (f.pattern() != null && !Pattern.compile(f.pattern()).matcher(s).matches()) {
            return f.label() + " is not in the expected form";
        }
        return null;
    }

    private static String dateLimit(Object v, LocalDate today) {
        if (v == null) {
            return null;
        }
        return v.toString().equals("today") ? (today == null ? null : today.toString()) : v.toString();
    }

    private static String plain(BigDecimal d) {
        return String.format(java.util.Locale.ROOT, "%,f", d).replaceAll("\\.?0+$", "");
    }
}
