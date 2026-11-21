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

import com.ash.drishti.api.error.DrishtiException;
import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.kernel.api.security.Principal;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.rachana.model.Form;
import com.ash.rachana.model.FormExpr;
import com.ash.rachana.model.Panel;
import com.ash.rachana.model.Sutra;
import com.ash.drishti.server.security.Entitlements;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The server checks a form's draft as people type (RACHANA_REFERENCE.md section 10, the Rules paragraph): the browser sends what was typed and
 * shows what comes back; it carries no rule. {@code POST /api/v1/forms/check} is read-only and writes nothing, a token reads it as it reads
 * {@code /changes/preview}.
 *
 * <p>Body: {@code {sutra, panel, values, checked:[field...], carried:{field:{...}}, step}}, and optionally {@code design} (the person's own Design, as drafts read it) and {@code auto:[field...]}, the
 * fields whose value is a default the person has not typed over (the server works them out again). The form is found from the Sutra on the
 * server; the client sends no rule. Answer: {@code {issues:[{field, message, severity}], computed:{field:value}, hints:[{label, step, value,
 * of, percent, over, text}], typed:{field:value}}}: {@code issues} are {@link Form#check}; {@code computed} is every {@code =expression}
 * default worked out ({@link Form#withDefaults}); {@code hints} are evaluated and worded here; {@code typed} is each value as the server
 * reads it (a number, true or false, text). The expressions are {@link FormExpr}'s: this class holds none.
 */
@RestController
@RequestMapping("/api/v1")
public class FormCheckController {

    /** What a masked value reads as in a carried record. */
    static final String MASK = "•••";

    private final SutraRegistry sutras;
    private final Entitlements entitlements;
    private final Supplier<LocalDate> today;

    @Autowired
    public FormCheckController(SutraRegistry sutras, Entitlements entitlements, BusinessDates dates) {
        this(sutras, entitlements, dates::current);
    }

    FormCheckController(SutraRegistry sutras, Entitlements entitlements, Supplier<LocalDate> today) {
        this.sutras = sutras;
        this.entitlements = entitlements;
        this.today = today;
    }

    /** The Designs, for a page drawn with the person's own Design (absent in a test that makes the controller itself). */
    private com.ash.drishti.identity.design.DesignService designs;

    @Autowired(required = false)
    void designs(com.ash.drishti.identity.design.DesignService d) {
        this.designs = d;
    }

    @PostMapping("/forms/check")
    public Map<String, Object> check(@RequestBody JsonNode body, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        String name = body.path("sutra").asText("");
        String panelId = body.path("panel").asText("");
        if (name.isBlank() || panelId.isBlank() || !body.path("values").isObject()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a form check is { sutra, panel, values, checked, carried, step }");
        }
        String design = body.path("design").asText("");
        Sutra sutra;
        if (!design.isBlank() && designs != null) {                    // a page drawn with the person's own Design: its Sutra, as a draft save reads it
            var d = designs.get(p.user(), design);
            if (d.sutra == null || d.sutra.isBlank()) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "the design '" + design + "' has no Sutra yet");
            }
            sutra = sutras.check(d.sutra);
        } else {
            sutra = sutras.latest(name).orElseThrow(() -> new DrishtiException(ErrorCode.SUTRA_NOT_FOUND, "no Sutra " + name));
        }
        Panel panel = sutra.panels().stream().filter(x -> x.id().equals(panelId)).findFirst()
                .orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, name + " has no panel " + panelId));
        Form form = panel.form().orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, name + " # " + panelId + " has no form"));
        List<String> held = entitlements.formRoles(p);
        if (!form.roles().isEmpty() && !held.contains("*") && held.stream().noneMatch(form.roles()::contains)) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "the form " + name + " # " + panelId + " is for " + String.join(", ", form.roles()));
        }
        String kind = form.save() != null && form.save().kind() != null ? form.save().kind() : sutra.match() == null ? null : sutra.match().kind();
        if (kind != null) {
            entitlements.requireOpen(p, kind);
        }
        return answer(form, held, body, today.get());
    }

    /** The answer for one request body; no state, so it is also what the tests call. */
    static Map<String, Object> answer(Form form, List<String> held, JsonNode body, LocalDate today) {
        Map<String, Object> given = plain(body.path("values"));
        Set<String> auto = names(body.path("auto"));
        Set<String> checked = body.has("checked") ? names(body.path("checked")) : editable(form, held);
        for (String a : auto) {
            given.remove(a);                                           // a default the person did not type over is worked out again
        }
        Set<String> fill = new HashSet<>();
        for (Form.Field f : form.fields()) {
            if (f.defaultExpr() != null && blank(given.get(f.id()))) {
                fill.add(f.id());
            }
        }
        Map<String, Object> values = form.withDefaults(given, fill, today);
        Map<String, Object> computed = new LinkedHashMap<>();
        for (String id : fill) {
            if (!blank(values.get(id))) {
                computed.put(id, plainNumber(values.get(id)));
            }
        }
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Form.Issue is : form.check(values, checked, today)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", is.field());
            m.put("message", is.message());
            m.put("severity", is.severity());
            issues.add(m);
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Form.Field f : form.fields()) {
            typed.put(f.id(), typed(f, values.get(f.id())));
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("issues", issues);
        answer.put("computed", computed);
        answer.put("hints", hints(form, typed, carried(body.path("carried")), body.path("step").asText(""), today));
        answer.put("typed", typed);
        return answer;
    }

    private static Set<String> editable(Form form, List<String> held) {
        Set<String> out = new HashSet<>();
        form.fields().stream().filter(f -> form.editableBy(f, held)).forEach(f -> out.add(f.id()));
        return out;
    }

    // ---- hints --------------------------------------------------------------------------------------------------------------------

    private static List<Map<String, Object>> hints(Form form, Map<String, Object> typed, Map<String, Map<String, Object>> carried, String step,
            LocalDate today) {
        Map<String, Object> ctx = new LinkedHashMap<>(typed);
        ctx.putAll(carried);                                            // a lookup's carried record reads as counterparty.USED
        String last = form.steps().isEmpty() ? null : form.steps().get(form.steps().size() - 1).id();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Form.Hint h : form.hints()) {
            boolean on = step.isBlank() || (h.step() == null ? last == null || last.equals(step) : h.step().equals(step));
            if (!on) {
                continue;
            }
            Object v = eval(h.value(), ctx, today);
            Object of = h.of() == null ? null : eval(h.of(), ctx, today);
            boolean hidden = masked(v) || masked(of) || carried.values().stream().anyMatch(c -> c.containsValue(MASK))
                    && (v == null || h.of() != null && of == null);
            BigDecimal nv = FormExpr.number(v);
            BigDecimal no = FormExpr.number(of);
            Integer percent = nv != null && no != null && no.signum() != 0
                    ? nv.multiply(BigDecimal.valueOf(100)).divide(no, 10, RoundingMode.HALF_UP).add(new BigDecimal("0.5")).setScale(0, RoundingMode.FLOOR).intValue()
                    : null;
            String text;
            if (hidden) {
                text = h.label() + ": Hidden for you";
            } else if (v == null) {
                text = h.label() + ": fill in the fields above to see it";
            } else {
                text = h.label() + ": " + show(v) + (of != null ? " of " + show(of) + (percent != null ? " (" + percent + "%)" : "") : "");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", h.label());
            m.put("step", h.step());
            m.put("value", hidden ? null : plainNumber(v));
            m.put("of", hidden ? null : plainNumber(of));
            m.put("percent", percent);
            m.put("over", !hidden && nv != null && no != null && nv.compareTo(no) > 0);
            m.put("text", text);
            out.add(m);
        }
        return out;
    }

    /** A value that is the mask, or text that carries it (a masked number met a {@code +}: {@code "•••1"}). */
    private static boolean masked(Object v) {
        return v instanceof String s && s.contains(MASK);
    }

    private static Object eval(FormExpr e, Map<String, Object> ctx, LocalDate today) {
        try {
            return e.eval(ctx, today);
        } catch (RuntimeException ex) {
            return null;                                               // an expression that cannot be worked out yet says nothing
        }
    }

    /** A number as the hint line shows it: grouped, no decimals, a minus sign; anything else as text. */
    static String show(Object v) {
        BigDecimal n = FormExpr.number(v);
        if (n == null) {
            return String.valueOf(v);
        }
        BigDecimal r = n.setScale(0, RoundingMode.HALF_UP);
        String digits = String.format(java.util.Locale.ROOT, "%,d", r.abs().toBigInteger());
        return (r.signum() < 0 ? "-" : "") + digits;
    }

    // ---- values -------------------------------------------------------------------------------------------------------------------

    /** A value as the server reads it, as {@code Form.check} does: an amount for number and money, true or false for bool, else text. */
    private static Object typed(Form.Field f, Object v) {
        if (blank(v)) {
            return null;
        }
        return switch (f.type()) {
            case "number", "money" -> plainNumber(Form.amount(v));
            case "bool" -> v instanceof Boolean b ? b : Boolean.valueOf("true".equalsIgnoreCase(v.toString()));
            default -> v.toString();
        };
    }

    private static boolean blank(Object v) {
        return v == null || v.toString().isBlank();
    }

    /** A number without an exponent (1E+3 is 1000); anything else as it is. */
    private static Object plainNumber(Object v) {
        return v instanceof BigDecimal d && d.scale() < 0 ? d.setScale(0) : v;
    }

    private static Map<String, Object> plain(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            if (v.isNumber()) {
                out.put(e.getKey(), v.decimalValue());
            } else if (v.isBoolean()) {
                out.put(e.getKey(), v.booleanValue());
            } else if (v.isTextual()) {
                out.put(e.getKey(), v.asText());
            }
        });
        return out;
    }

    private static Map<String, Map<String, Object>> carried(JsonNode node) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> {
            if (e.getValue().isObject()) {
                out.put(e.getKey(), plain(e.getValue()));
            }
        });
        return out;
    }

    private static Set<String> names(JsonNode list) {
        Set<String> out = new HashSet<>();
        list.forEach(n -> out.add(n.asText()));
        return out;
    }
}
