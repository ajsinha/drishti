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
package com.ash.drishti.bicompute.custom;

import com.ash.drishti.bicompute.custom.FunctionDef.Input;
import com.ash.drishti.bicompute.custom.FunctionDef.Language;
import com.ash.drishti.bicompute.formula.FormulaFunction;
import com.ash.drishti.bicompute.formula.FormulaLowering;
import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.bicompute.harness.FunctionCheck;
import com.ash.drishti.bicompute.python.PythonFunction;
import com.ash.drishti.bicompute.python.PythonFunctionClient;
import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.bicompute.spi.Batch;
import com.ash.drishti.bicompute.spi.Kind;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom aggregation functions (PIVOT_ENGINE.md section 8): defined in one of four languages, proven by the harness, saved as versions,
 * private to their author until someone else approves them, audited at every step, labelled on every figure, never in an official run.
 *
 * <p>A view uses one as {@code fn:owner/name@version(FIELD, FIELD)}: the fields are bound by position to the function's declared inputs.
 * Without {@code @version} the latest version the reader may use is taken, and the answer names it.
 */
public final class CustomFunctions {

    /** {@code fn:owner/name[@v](args)}. */
    public static final Pattern CALL = Pattern.compile("\\s*fn:([A-Za-z0-9._@-]+?)/([a-z][a-z0-9_]{0,63})(?:@(\\d+))?\\s*\\((.*)\\)\\s*");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    /** Names a custom function never takes: the official measures (decision 14) and the built-ins. */
    public static final Set<String> RESERVED = Set.of("var", "es", "svar", "stressed_var", "expected_shortfall", "frtb", "frtb_sa", "simm", "saccr",
            "sa_ccr", "ead", "pfe", "ee", "epe", "eepe", "exposure", "cva", "irc", "drc", "rrao", "ima", "official");

    /**
     * The settings ({@code drishti.pivot.functions}).
     *
     * @param formula formulas (Rachana) on
     * @param sql SQL macros on
     * @param python Python functions on (off as shipped)
     * @param java Java plug-ins on (off as shipped)
     * @param requireApproval sharing needs an approver who is not the author
     * @param harness the harness's options
     * @param holisticMaxRows the most rows of one cell a holistic custom function receives
     * @param holisticMaxMb the most megabytes of one cell's rows
     * @param batchRows rows in each batch a function is fed
     */
    public record Settings(boolean formula, boolean sql, boolean python, boolean java, boolean requireApproval, FunctionCheck.Options harness,
            long holisticMaxRows, long holisticMaxMb, int batchRows) {
        public static Settings defaults() {
            return new Settings(true, true, false, false, true, FunctionCheck.Options.defaults(), 5_000_000L, 512, 65_536);
        }
    }

    /** What the host knows about people: groups for sharing, approvers, administrators. */
    public interface Access {
        Set<String> groups(Viewer v);

        /** May approve custom functions (an administrator or a delegated approver). */
        boolean approver(Viewer v);

        /** An administrator (Java plug-ins are approved by one). */
        boolean admin(Viewer v);
    }

    /** The audit trail: every save, test, submission, approval, withdrawal and use. */
    @FunctionalInterface
    public interface Audit {
        void record(String user, String action, String target, String detail);
    }

    /** A function resolved for a view: its canonical id, the function and the fields bound to its inputs. */
    public record Resolved(String id, AggFunction<Object> fn, List<String> fields, FunctionDef def) {}

    private final FunctionStore store;
    private final Access access;
    private final Audit audit;
    private volatile Settings settings;
    private final PythonFunctionClient python;
    private final SqlMacroFunction.Runner sqlRunner;
    private final JavaPlugins plugins;

    public CustomFunctions(FunctionStore store, Access access, Audit audit, Settings settings, PythonFunctionClient python,
            SqlMacroFunction.Runner sqlRunner, JavaPlugins plugins) {
        this.store = store;
        this.access = access;
        this.audit = audit == null ? (u, a, t, d) -> { } : audit;
        this.settings = settings == null ? Settings.defaults() : settings;
        this.python = python;
        this.sqlRunner = sqlRunner;
        this.plugins = plugins;
    }

    public Settings settings() {
        return settings;
    }

    public void settings(Settings s) {
        this.settings = s;
    }

    static DrishtiException invalid(String why) {
        return new DrishtiException(ErrorCode.PIVOT_FUNCTION_INVALID, why);
    }

    // ---- definitions ----------------------------------------------------------------------------------------------------------------

    /** A definition from a request body ({@code name, language, code, entry, inputs, kind, params, tolerance, stateBytes, ...}), unsaved. */
    @SuppressWarnings("unchecked")
    public FunctionDef define(Viewer v, Map<String, Object> body, int version) {
        String name = String.valueOf(body.getOrDefault("name", "")).strip().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            throw invalid("a function's name is a lower-case letter, then letters, digits or underscores (at most 64): not '" + name + "'");
        }
        if (RESERVED.contains(name) || Functions.builtIn(name, List.of("x"), List.of(0.5)).isPresent()) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_OFFICIAL, "'" + name + "' is " + (RESERVED.contains(name)
                    ? "an official measure's name: a custom function never takes it" : "a built-in's name") + "; pick another");
        }
        Language lang = Language.of(String.valueOf(body.getOrDefault("language", "formula")));
        String code = body.get("code") == null ? "" : String.valueOf(body.get("code"));
        List<Input> inputs = new ArrayList<>();
        if (body.get("inputs") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    String type = String.valueOf(m.get("type") == null ? "number" : m.get("type"));
                    inputs.add(new Input(String.valueOf(m.get("name")), "text".equals(type) ? "text" : "number"));
                } else {
                    inputs.add(new Input(String.valueOf(o), "number"));
                }
            }
        }
        if (lang == Language.FORMULA && inputs.isEmpty()) {
            FormulaFunction f = formula(name, code, null);
            f.fields().forEach(x -> inputs.add(new Input(x, "number")));
        }
        if (lang == Language.SQL && inputs.isEmpty()) {
            SqlMacroFunction.parse(code).params().forEach(x -> inputs.add(new Input(x, "number")));
        }
        for (Input i : inputs) {
            if (!i.name().matches("[A-Za-z_][A-Za-z0-9_.]*")) {
                throw invalid("an input's name is a field-like name, not '" + i.name() + "'");
            }
        }
        Kind declared = FunctionDef.kind(body.get("kind") == null ? null : String.valueOf(body.get("kind")));
        Map<String, Object> params = body.get("params") instanceof Map<?, ?> pm ? (Map<String, Object>) pm : Map.of();
        String entry = body.get("entry") == null ? null : String.valueOf(body.get("entry"));
        FunctionDef draft = new FunctionDef(v.user(), name, version, lang, declared, code, entry, null, inputs, params,
                FunctionDef.num(body.get("tolerance"), 1e-12).doubleValue(), FunctionDef.num(body.get("stateBytes"), 0).longValue(),
                FunctionDef.num(body.get("sketchError"), 0).doubleValue(), body.get("description") == null ? "" : String.valueOf(body.get("description")),
                Instant.now().toString(), Map.of(), "private", List.of(), null);
        Kind actual = make(draft, false).kind();
        if (lang == Language.FORMULA || lang == Language.SQL) {
            declared = actual;                                  // the engine finds the kind of a formula or a macro
        } else if (actual != declared) {
            throw invalid(draft.ref() + " is declared " + declared.word() + " but its code is " + actual.word());
        }
        return new FunctionDef(draft.owner(), name, version, lang, declared, code, entry, null, inputs, params, draft.tolerance(),
                draft.stateBytes(), draft.sketchError(), draft.description(), draft.createdAt(), Map.of(), "private", List.of(), null);
    }

    private static FormulaFunction formula(String ref, String code, List<String> inputs) {
        try {
            return FormulaFunction.of(ref, code, inputs);
        } catch (FormulaLowering.FormulaException e) {
            throw invalid("the formula: " + e.getMessage());
        }
    }

    /** The definition as a function the engine runs ({@code checkOn}: refuse a language switched off). */
    public AggFunction<Object> make(FunctionDef d, boolean checkOn) {
        Settings s = settings;
        String ref = d.ref();
        boolean on = switch (d.language()) {
            case FORMULA -> s.formula();
            case SQL -> s.sql();
            case PYTHON -> s.python();
            case JAVA -> s.java();
        };
        if (checkOn && !on || d.language() == Language.PYTHON && python == null || d.language() == Language.JAVA && plugins == null) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_OFF, d.language().word() + " custom functions are switched off on this server "
                    + "(drishti.pivot.functions." + (d.language() == Language.FORMULA ? "formula" : d.language() == Language.SQL ? "sql-macros"
                            : d.language().word() + ".enabled") + ")");
        }
        return switch (d.language()) {
            case FORMULA -> formula(ref, d.code(), d.inputNames());
            case SQL -> SqlMacroFunction.of(ref, d.code(), d.inputs(), sqlRunner);
            case PYTHON -> {
                if (d.entry() == null || d.entry().isBlank()) {
                    throw invalid("a Python function names its class: entry: WeightedReturn (the class decorated with @function)");
                }
                PythonFunctionClient.Spec spec = new PythonFunctionClient.Spec(ref, d.code(), d.entry(), d.inputNames(), d.params(),
                        sha(d.code()), d.stateBytes() > 0 ? d.stateBytes() : 1L << 20, false);
                yield new PythonFunction(python, spec, d.kind(), d.inputs().stream().noneMatch(Input::text));
            }
            case JAVA -> {
                AggFunction<Object> f = plugins.load(d.entry(), d.code(), d.sha256());
                if (f.inputs() != d.inputs().size()) {
                    throw invalid(d.code() + " takes " + f.inputs() + " inputs; the definition declares " + d.inputs().size());
                }
                yield new Renamed(ref, f);
            }
        };
    }

    static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- the harness and saving -----------------------------------------------------------------------------------------------------

    /** The harness on a definition and its sample rows (the playground's check; nothing saved). Audited. */
    public FunctionCheck.Verdict test(Viewer v, FunctionDef d, Batch sample) {
        FunctionCheck.Verdict verdict = check(d, sample);
        audit.record(v.user(), "pivot.function.test", d.ref(), "language=" + d.language().word() + "; rows=" + sample.rows() + "; passed="
                + verdict.passed());
        return verdict;
    }

    private FunctionCheck.Verdict check(FunctionDef d, Batch sample) {
        FunctionCheck.Options h = settings.harness();
        FunctionCheck.Options o = new FunctionCheck.Options(d.tolerance() > 0 ? d.tolerance() : h.tolerance(), h.seeds(), h.maxNsPerRow(),
                d.stateBytes(), d.sketchError());
        return FunctionCheck.run(make(d, true), sample, o);
    }

    /** Saves a new version of the caller's function after its harness passes on {@code sample}; refused with the first failure otherwise. */
    public FunctionDef save(Viewer v, Map<String, Object> body, Batch sample) {
        String name = String.valueOf(body.getOrDefault("name", "")).strip().toLowerCase(Locale.ROOT);
        List<FunctionDef> old = store.versions(v.user(), name);
        int version = old.isEmpty() ? 1 : old.get(old.size() - 1).version() + 1;
        FunctionDef d = define(v, body, version);
        FunctionCheck.Verdict verdict = check(d, sample);
        if (!verdict.passed()) {
            audit.record(v.user(), "pivot.function.refused", d.ref(), verdict.firstFailure());
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_HARNESS, d.ref() + " is not saved: its harness did not pass (" + verdict.firstFailure()
                    + "); a function is saved, shared or used only after split-and-merge equals one pass");
        }
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("passed", true);
        h.put("rows", verdict.rows());
        h.put("nsPerRow", Math.round(verdict.nsPerRow()));
        h.put("stateBytes", verdict.stateBytes());
        h.put("worst", verdict.splits() == null || verdict.splits().worst() == null ? null : verdict.splits().worst().diff());
        h.put("at", Instant.now().toString());
        FunctionDef saved = new FunctionDef(d.owner(), d.name(), d.version(), d.language(), d.kind(), d.code(), d.entry(), d.sha256(), d.inputs(),
                d.params(), d.tolerance(), d.stateBytes(), d.sketchError(), d.description(), d.createdAt(), h, "private", List.of(), null);
        store.put(saved);
        audit.record(v.user(), "pivot.function.save", saved.ref(), "language=" + saved.language().word() + "; kind=" + saved.kind().word()
                + "; sha256=" + sha(saved.code()).substring(0, 16));
        return saved;
    }

    // ---- sharing --------------------------------------------------------------------------------------------------------------------

    private FunctionDef version(String owner, String name, int version) {
        return store.get(owner, name, version).orElseThrow(() -> new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "no custom function fn:"
                + owner + "/" + name + "@" + version));
    }

    /** The author asks for a version to be shared (it stays private until approved). */
    public FunctionDef submit(Viewer v, String owner, String name, int version, String note) {
        FunctionDef d = version(owner, name, version);
        if (!d.owner().equals(v.user())) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_APPROVAL, "only the author submits " + d.ref());
        }
        FunctionDef s = d.sharing("approved".equals(d.status()) ? d.status() : "submitted", d.approvals(), d.withdrawn());
        store.put(s);
        audit.record(v.user(), "pivot.function.submit", d.ref(), note == null ? "" : note);
        return s;
    }

    /**
     * Approves a version for {@code scope} ({@code everyone} or {@code group:<name>}): by an approver who is not its author (Java plug-ins:
     * an administrator, the jar's SHA-256 pinned). Audited with the reason.
     */
    public FunctionDef approve(Viewer v, String owner, String name, int version, String scope, String reason) {
        FunctionDef d = version(owner, name, version);
        if (settings.requireApproval() && d.owner().equals(v.user())) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_APPROVAL, "an author never approves their own function (" + d.ref()
                    + "): another approver must");
        }
        boolean may = d.language() == Language.JAVA ? access.admin(v) : access.approver(v) || access.admin(v);
        if (!may) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_APPROVAL, "you may not approve custom functions" + (d.language() == Language.JAVA
                    ? " (a Java plug-in is approved by an administrator)" : " (an administrator or a delegated approver does)"));
        }
        if (reason == null || reason.isBlank()) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_APPROVAL, "an approval gives its reason");
        }
        if (!Boolean.TRUE.equals(d.harness().get("passed"))) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_HARNESS, d.ref() + " has no passed harness report");
        }
        String sc = scope == null || scope.isBlank() ? "everyone" : scope.strip();
        if (!sc.equals("everyone") && !sc.matches("group:[A-Za-z0-9._-]+")) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_APPROVAL, "an approval is for everyone or group:<name>, not " + sc);
        }
        List<Map<String, Object>> appr = new ArrayList<>(d.approvals());
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("scope", sc);
        a.put("by", v.user());
        a.put("at", Instant.now().toString());
        a.put("reason", reason.strip());
        appr.add(a);
        FunctionDef approved = d.sharing("approved", appr, null);
        if (d.language() == Language.JAVA) {
            String digest = plugins.digest(d.entry());
            approved = new FunctionDef(d.owner(), d.name(), d.version(), d.language(), d.kind(), d.code(), d.entry(), digest, d.inputs(), d.params(),
                    d.tolerance(), d.stateBytes(), d.sketchError(), d.description(), d.createdAt(), d.harness(), "approved", appr, null);
        }
        store.put(approved);
        audit.record(v.user(), "pivot.function.approve", d.ref(), "scope=" + sc + "; reason=" + reason.strip());
        return approved;
    }

    /** Withdraws a version (an approver, an administrator or its author): views that use it say "function withdrawn" in its cells. */
    public FunctionDef revoke(Viewer v, String owner, String name, int version, String reason) {
        FunctionDef d = version(owner, name, version);
        if (!d.owner().equals(v.user()) && !access.approver(v) && !access.admin(v)) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_APPROVAL, "only an approver, an administrator or the author withdraws " + d.ref());
        }
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("by", v.user());
        w.put("at", Instant.now().toString());
        w.put("reason", reason == null ? "" : reason.strip());
        FunctionDef out = d.sharing("withdrawn", d.approvals(), w);
        store.put(out);
        audit.record(v.user(), "pivot.function.revoke", d.ref(), "reason=" + w.get("reason"));
        return out;
    }

    /** True when the reader may use this version: their own, or approved for everyone or one of their groups (withdrawn: still resolves). */
    boolean usable(Viewer v, FunctionDef d) {
        if (d.owner().equals(v.user())) {
            return true;
        }
        if (!"approved".equals(d.status()) && !d.isWithdrawn()) {
            return false;
        }
        Set<String> groups = access.groups(v);
        for (Map<String, Object> a : d.approvals()) {
            String sc = String.valueOf(a.get("scope"));
            if (sc.equals("everyone") || sc.startsWith("group:") && groups.contains(sc.substring(6))) {
                return true;
            }
        }
        return false;
    }

    /** The functions the caller may see: their own (every version) and those approved for them, latest version first. */
    public List<Map<String, Object>> list(Viewer v) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FunctionDef latest : store.latest()) {
            List<FunctionDef> vs = store.versions(latest.owner(), latest.name()).stream().filter(d -> usable(v, d)).toList();
            if (vs.isEmpty()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>(vs.get(vs.size() - 1).toMap());
            m.put("versions", vs.stream().map(d -> Map.of("version", d.version(), "status", d.status())).toList());
            m.put("mine", latest.owner().equals(v.user()));
            out.add(m);
        }
        return out;
    }

    /** One version as the caller may see it (code included: approvers review it; readers see what computes their figures). */
    public FunctionDef get(Viewer v, String owner, String name, Integer version) {
        List<FunctionDef> vs = store.versions(owner, name).stream().filter(d -> usable(v, d)).toList();
        Optional<FunctionDef> d = version == null ? vs.stream().reduce((a, b) -> b) : vs.stream().filter(x -> x.version() == version).findFirst();
        if (d.isEmpty() && version != null && !store.versions(owner, name).isEmpty() && access.approver(v)) {
            d = store.get(owner, name, version);                           // an approver reviews a submitted version
        }
        return d.orElseThrow(() -> new DrishtiException(ErrorCode.PIVOT_NOT_FOUND, "no custom function fn:" + owner + "/" + name
                + (version == null ? "" : "@" + version) + " that you may use (a function is its author's until it is approved for you)"));
    }

    // ---- use in a view --------------------------------------------------------------------------------------------------------------

    /** True when a measure's text calls a custom function. */
    public static boolean isCall(String text) {
        return text != null && text.strip().startsWith("fn:");
    }

    /**
     * A view's call {@code fn:owner/name[@v](FIELD, ...)} as a function for this reader: refused in an official run, when the reader may not
     * use it, or when its language is off; a withdrawn version answers "function withdrawn" in its cells. Audited (who, which version).
     */
    public Resolved resolve(Viewer v, String text) {
        Matcher m = CALL.matcher(text == null ? "" : text);
        if (!m.matches()) {
            throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, "'" + text + "' is not a custom function call: fn:owner/name@version(FIELD, ...)");
        }
        String official = OfficialRun.current();
        if (official != null) {
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_OFFICIAL, text.strip() + ": custom functions never run in " + official
                    + "; official measures are the Java engines'");
        }
        Integer version = m.group(3) == null ? null : Integer.parseInt(m.group(3));
        List<FunctionDef> asked = store.versions(m.group(1), m.group(2)).stream().filter(x -> version == null || x.version() == version).toList();
        if (!asked.isEmpty() && asked.stream().noneMatch(x -> usable(v, x))) {
            // it exists but is not approved for this reader (PIVOT_GUIDE.md, codes: DRS-5712 is 403, "a function not approved for you")
            throw new DrishtiException(ErrorCode.PIVOT_FUNCTION_APPROVAL, "fn:" + m.group(1) + "/" + m.group(2) + (version == null ? "" : "@" + version)
                    + " is not approved for you: a function is its author's until an approver who is not the author approves it");
        }
        FunctionDef d = get(v, m.group(1), m.group(2), version);
        List<String> fields = new ArrayList<>();
        for (String a : m.group(4).split(",")) {
            if (!a.isBlank()) {
                fields.add(a.strip());
            }
        }
        if (fields.size() != d.inputs().size()) {
            throw new DrishtiException(ErrorCode.PIVOT_BAD_VIEW, d.ref() + " takes " + d.inputs().size() + " field(s) " + d.inputNames() + ", not "
                    + fields.size());
        }
        String id = d.ref() + "(" + String.join(", ", fields) + ")";
        AggFunction<Object> fn;
        if (d.isWithdrawn()) {
            fn = new Withdrawn(d.ref(), d.inputs().size(), "function withdrawn by " + d.withdrawn().get("by") + " on "
                    + String.valueOf(d.withdrawn().get("at")).substring(0, 10) + (String.valueOf(d.withdrawn().getOrDefault("reason", "")).isBlank()
                            ? "" : ": " + d.withdrawn().get("reason")));
        } else {
            fn = make(d, true);
        }
        audit.record(v.user(), "pivot.function.use", d.ref(), "fields=" + fields + "; status=" + d.status());
        return new Resolved(id, fn, fields, d);
    }

    /** A plug-in's function under its custom name (its figures labelled {@code fn:owner/name@v}). */
    static final class Renamed implements AggFunction<Object> {
        private final String ref;
        private final AggFunction<Object> f;

        Renamed(String ref, AggFunction<Object> f) {
            this.ref = ref;
            this.f = f;
        }

        @Override
        public String name() {
            return ref;
        }

        @Override
        public Kind kind() {
            return f.kind();
        }

        @Override
        public int inputs() {
            return f.inputs();
        }

        @Override
        public boolean numeric() {
            return f.numeric();
        }

        @Override
        public Object start() {
            return f.start();
        }

        @Override
        public Object add(Object s, Batch b) {
            return f.add(s, b);
        }

        @Override
        public Object merge(Object a, Object b) {
            return f.merge(a, b);
        }

        @Override
        public Double finish(Object s) {
            return f.finish(s);
        }

        @Override
        public String emptyNote() {
            return f.emptyNote();
        }

        @Override
        public byte[] encode(Object s) {
            return f.encode(s);
        }

        @Override
        public Object decode(byte[] b) {
            return f.decode(b);
        }

        @Override
        public long bytes(Object s) {
            return f.bytes(s);
        }

        @Override
        public boolean plainSum() {
            return f.plainSum();
        }

        @Override
        public List<String> sqlState(List<String> in) {
            return f.sqlState(in);
        }

        @Override
        public Object fromSql(Object[] p) {
            return f.fromSql(p);
        }

        @Override
        public boolean sqlStateMergeable() {
            return f.sqlStateMergeable();
        }
    }

    // ---- sample rows ----------------------------------------------------------------------------------------------------------------

    /**
     * Sample rows as a batch of the function's inputs: {@code {"columns": [..], "rows": [[..], ..]}} or a list of objects
     * ({@code [{"r": 0.01, "w": 3}, ..]}); a number input reads numbers (absent: NaN), a text input text.
     */
    @SuppressWarnings("unchecked")
    public static Batch sample(List<Input> inputs, Object sample) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (sample instanceof Map<?, ?> m && m.get("rows") instanceof List<?> rs) {
            List<String> cols = m.get("columns") instanceof List<?> c ? c.stream().map(String::valueOf).toList() : List.of();
            for (Object r : rs) {
                Map<String, Object> row = new LinkedHashMap<>();
                if (r instanceof List<?> l) {
                    for (int i = 0; i < Math.min(l.size(), cols.size()); i++) {
                        row.put(cols.get(i), l.get(i));
                    }
                } else if (r instanceof Map<?, ?> rm) {
                    rm.forEach((k, x) -> row.put(String.valueOf(k), x));
                }
                rows.add(row);
            }
        } else if (sample instanceof List<?> l) {
            for (Object r : l) {
                if (r instanceof Map<?, ?> rm) {
                    rows.add((Map<String, Object>) rm);
                }
            }
        }
        if (rows.size() > 1_000_000) {
            throw invalid("sample rows are at most 1,000,000");
        }
        Object[] cols = new Object[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            Input in = inputs.get(i);
            if (in.text()) {
                String[] s = new String[rows.size()];
                for (int r = 0; r < s.length; r++) {
                    Object x = rows.get(r).get(in.name());
                    s[r] = x == null ? null : String.valueOf(x);
                }
                cols[i] = s;
            } else {
                double[] d = new double[rows.size()];
                for (int r = 0; r < d.length; r++) {
                    Object x = rows.get(r).get(in.name());
                    d[r] = x instanceof Number n ? n.doubleValue() : x == null ? Double.NaN : parse(String.valueOf(x));
                }
                cols[i] = d;
            }
        }
        return new Batch(rows.size(), cols);
    }

    private static double parse(String s) {
        try {
            return Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
