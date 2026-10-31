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
package com.ash.drishti.server.bi.runs;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.server.bi.plan.SecuritySignature;
import com.ash.drishti.server.bi.report.RelativeDates;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Normalise a question before hashing it (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 8.3a, "Fewer duplicate runs"): two questions that read the same rows
 * hash alike, and two that do not, do not. Pure: it works on the MQ as parsed JSON ({@code Map}/{@code List}/scalars) and a {@link Context}.
 *
 * <ol>
 *   <li><b>Sorted:</b> the values of a set comparison ({@code in}, {@code not-in}) are sorted and de-duplicated; the conjuncts of {@code where} and
 *       {@code having} are sorted; the grain ({@code select.fields}) and the measures are sorted and de-duplicated. The order of a
 *       {@code between}'s bounds, of {@code sort} and of {@code totals.levels} is kept: it changes the answer.</li>
 *   <li><b>Defaults filled:</b> {@code op} {@code eq}; {@code value} as {@code values}; a top filter's {@code dir} {@code desc}; {@code shape}
 *       {@code grouped}; {@code options.layer} {@code auto}; a sort's {@code dir} as {@code desc: true|false}; every parameter the model declares
 *       with a default and the question leaves out.</li>
 *   <li><b>Relative dates resolved:</b> a {@code relative} filter becomes {@code between [from, to]} exactly as {@code MqCompiler} resolves it
 *       (RelativeDates over the as-of date and the business calendar; {@code all} adds no filter); {@code time.as-of: latest} becomes the date.</li>
 *   <li><b>Cosmetic properties dropped:</b> keys that change no row (a title, labels, formats, colours; {@code options.format} and
 *       {@code options.explain}), the language version {@code mq}, and any key MQ does not know.</li>
 * </ol>
 *
 * Numbers that are whole are written as whole numbers ({@code 5.0} is {@code 5}); text and numbers stay distinct ({@code "1"} is not {@code 1}).
 */
public final class QuestionNormaliser {

    /** The top-level keys of an MQ (MqParser.TOP): everything else is visual-only. */
    static final Set<String> TOP = Set.of("mq", "model", "define", "select", "where", "having", "time", "sort", "limit", "totals", "drill", "shape", "params",
            "calc", "perspective", "options");
    /** Options that change the rows; the wire format and explain do not. */
    static final Set<String> ROW_OPTIONS = Set.of("layer", "budget");
    static final Set<String> SET_OPS = Set.of("in", "not-in");

    private static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /**
     * What normalising needs besides the question.
     *
     * @param asOf          the as-of date relative dates count from, and what {@code latest} resolves to
     * @param calendar      the pack's business calendar (for {@code last-N-business-days})
     * @param paramDefaults the model's parameters with their defaults
     */
    public record Context(LocalDate asOf, BusinessCalendar calendar, Map<String, Object> paramDefaults) {
        public Context {
            Objects.requireNonNull(asOf, "asOf");
            paramDefaults = paramDefaults == null ? Map.of() : paramDefaults;
        }
    }

    private QuestionNormaliser() {}

    /** The normalised question: a sorted tree. */
    public static Map<String, Object> normalise(Map<String, ?> mq, Context ctx) {
        Map<String, Object> out = new TreeMap<>();
        mq.forEach((k, v) -> {
            if (TOP.contains(k) && v != null) {
                out.put(k, deep(v));
            }
        });
        out.remove("mq");                                   // the language version names no rows (a 2 only says params or calc are used)
        out.putIfAbsent("shape", "grouped");
        Map<String, Object> select = map(out.get("select"));
        select.keySet().removeIf(k -> !Set.of("fields", "measures").contains(k));
        select.replaceAll((k, v) -> sortedSet(v));
        out.put("select", select);
        List<Object> where = new ArrayList<>();
        for (Object w : list(out.get("where"))) {
            Map<String, Object> f = filter(map(w), ctx);
            if (f != null) {
                where.add(f);
            }
        }
        putSorted(out, "where", where);
        List<Object> having = new ArrayList<>();
        for (Object h : list(out.get("having"))) {
            Map<String, Object> f = map(h);
            f.put("op", String.valueOf(f.getOrDefault("op", "eq")).toLowerCase(Locale.ROOT));
            values(f);
            having.add(f);
        }
        putSorted(out, "having", having);
        if (out.get("sort") instanceof List<?> sort) {
            out.put("sort", sort.stream().map(s -> sortKey(map(s))).toList());
        }
        if (out.get("time") instanceof Map<?, ?> time) {
            Map<String, Object> t = map(time);
            if ("latest".equals(t.get("as-of"))) {
                t.put("as-of", ctx.asOf().toString());
            }
            out.put("time", t);
        }
        Map<String, Object> params = map(out.get("params"));
        ctx.paramDefaults().forEach((k, v) -> params.putIfAbsent(k, deep(v)));
        if (params.isEmpty()) {
            out.remove("params");
        } else {
            out.put("params", params);
        }
        Map<String, Object> options = map(out.get("options"));
        options.keySet().retainAll(ROW_OPTIONS);
        options.putIfAbsent("layer", "auto");
        out.put("options", options);
        return out;
    }

    /** The normalised question as text: sorted keys, no spaces. */
    public static String text(Map<String, ?> mq, Context ctx) {
        try {
            return JSON.writeValueAsString(normalise(mq, ctx));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("the question cannot be written as JSON", e);
        }
    }

    /** The question's hash for the run's fingerprint: equal for equivalent questions (section 8.3a). */
    public static String hash(Map<String, ?> mq, Context ctx) {
        return "q_" + SecuritySignature.sha(text(mq, ctx)).substring(0, 16);
    }

    // ---- parts --------------------------------------------------------------------------------------------------------

    private static Map<String, Object> filter(Map<String, Object> f, Context ctx) {
        String op = String.valueOf(f.getOrDefault("op", "eq")).toLowerCase(Locale.ROOT);
        f.put("op", op);
        if ("relative".equals(op)) {
            RelativeDates.Window win = RelativeDates.resolve(String.valueOf(f.get("window")), ctx.asOf(), ctx.calendar());
            if (win == null) {
                return null;                                 // "all": no restriction, as MqCompiler adds no filter
            }
            Map<String, Object> b = new TreeMap<>();
            b.put("field", f.get("field"));
            b.put("op", "between");
            b.put("values", List.of(win.from().toString(), win.to().toString()));
            return b;
        }
        values(f);
        if (SET_OPS.contains(op) && f.get("values") instanceof List<?> l) {
            f.put("values", sortedSet(l));
        }
        if (f.containsKey("n")) {
            f.put("dir", String.valueOf(f.getOrDefault("dir", "desc")).toLowerCase(Locale.ROOT));
        } else {
            f.remove("dir");
        }
        return f;
    }

    /** {@code value: x} and {@code values: [x]} are the same; no values at all is no key. */
    private static void values(Map<String, Object> f) {
        Object v = f.containsKey("values") ? f.remove("values") : f.remove("value");
        f.remove("value");
        if (v == null) {
            return;
        }
        List<Object> l = v instanceof List<?> x ? new ArrayList<>(x) : new ArrayList<>(List.of(v));
        if (!l.isEmpty()) {
            f.put("values", l);
        }
    }

    private static Map<String, Object> sortKey(Map<String, Object> s) {
        Map<String, Object> o = new TreeMap<>();
        o.put("by", s.get("by"));
        Object desc = s.get("desc");
        o.put("desc", Boolean.TRUE.equals(desc) || "desc".equalsIgnoreCase(String.valueOf(s.get("dir"))));
        return o;
    }

    private static void putSorted(Map<String, Object> out, String key, List<Object> items) {
        if (items.isEmpty()) {
            out.remove(key);
            return;
        }
        items.sort(Comparator.comparing(QuestionNormaliser::canonical));
        out.put(key, items);
    }

    /** Sorted and de-duplicated, values compared by kind then by order (numbers by value, text by text). */
    private static List<Object> sortedSet(Object v) {
        List<Object> l = new ArrayList<>(new LinkedHashSet<>(list(v)));
        l.sort(QuestionNormaliser::compare);
        return l;
    }

    private static int compare(Object a, Object b) {
        int ka = kind(a);
        int kb = kind(b);
        if (ka != kb) {
            return Integer.compare(ka, kb);
        }
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        return canonical(a).compareTo(canonical(b));
    }

    private static int kind(Object o) {
        return o == null ? 0 : o instanceof Boolean ? 1 : o instanceof Number ? 2 : o instanceof String ? 3 : 4;
    }

    /** A copy with sorted maps and whole numbers as longs. */
    private static Object deep(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new TreeMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), deep(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(QuestionNormaliser::deep).toList();
        }
        if (v instanceof Number n && !(n instanceof Long)) {
            double d = n.doubleValue();
            return d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.0e15 ? (Object) (long) d : (Object) d;
        }
        return v;
    }

    private static Map<String, Object> map(Object v) {
        Map<String, Object> out = new TreeMap<>();
        if (v instanceof Map<?, ?> m) {
            m.forEach((k, x) -> out.put(String.valueOf(k), x));
        }
        return out;
    }

    private static List<Object> list(Object v) {
        return v instanceof List<?> l ? new ArrayList<>(l) : v == null ? new ArrayList<>() : new ArrayList<>(List.of(v));
    }

    private static String canonical(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return String.valueOf(o);
        }
    }
}
