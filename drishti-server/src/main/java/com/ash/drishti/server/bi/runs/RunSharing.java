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

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.mq.ModelQuery;
import com.ash.drishti.server.bi.mq.MqParser;
import com.ash.drishti.server.bi.mq.MqService;
import com.ash.drishti.server.bi.plan.SecuritySignature;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Sharing a run (docs/architecture/BI_RUNS.md section 9.3). Sharing never hands over the runner's result as such: for each recipient it decides
 *
 * <ul>
 *   <li><b>same</b>: the recipient's security signature on the model equals the runner's; they read the stored result;
 *   <li><b>re-masked</b>: the row part is equal (rules, denied layers, hidden columns, denied kinds), the recipient masks every column the runner masks and
 *       more, the question validates as the recipient, and masking the extra columns on output gives exactly what the engine would give them (a masked
 *       grouping field collapses its groups, so re-masking one is allowed only when every measure adds up and there is no limit, top filter or totals);
 *   <li><b>recomputed</b>: anything else; a new run in the recipient's name, as the recipient, {@code shared_from} the original.
 * </ul>
 *
 * The decision is taken again at every read, as the recipient is <em>now</em>: a recipient whose rights narrowed since the share never reads the wider
 * answer. A recipient never sees more than their own rights allow.
 */
public final class RunSharing {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** How a recipient reads a shared run. */
    public enum Mode {
        SAME("same"), RE_MASKED("re-masked"), RECOMPUTED("recomputed");

        private final String wire;

        Mode(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /** The decision for one recipient: the mode, the columns re-masked ({@code alias.column}), and why, in words without values. */
    public record Decision(Mode mode, Set<String> extraMasked, String why) {}

    /** Binds a model as a person (their row rules and masks); throws when they may not read it. */
    @FunctionalInterface
    public interface Binder {
        MqService.Bound bind(Principal p, String model);
    }

    /** The question of a run asked again as another person (a recomputation). */
    @FunctionalInterface
    public interface Replayer {
        Optional<RunService.Question> of(Run run, Principal as);
    }

    private final RunService runs;
    private final RunShares shares;
    private final Binder binder;
    private final MqService mq;
    private final Function<String, Principal> principals;
    private final Function<String, List<String>> roleMembers;
    private final Replayer replay;
    private final Clock clock;

    public RunSharing(RunService runs, RunShares shares, Binder binder, MqService mq, Function<String, Principal> principals,
            Function<String, List<String>> roleMembers, Replayer replay, Clock clock) {
        this.runs = runs;
        this.shares = shares;
        this.binder = binder;
        this.mq = mq;
        this.principals = principals;
        this.roleMembers = roleMembers == null ? r -> List.of() : roleMembers;
        this.replay = replay;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public RunShares shares() {
        return shares;
    }

    // ---- the decision (pure) ------------------------------------------------------------------------------------------

    /**
     * Decides how {@code recipient} reads a result computed as {@code runner}.
     *
     * @param q      the canonical model query, or null (a SQL statement or a report visual: only {@code same} or {@code recomputed})
     * @param answer the stored answer (its columns and rows decide whether re-masking is exact)
     */
    public static Decision decide(MqService.Bound runner, MqService.Bound recipient, ModelQuery q, Map<String, Object> answer, MqService mq) {
        if (runner.signature().equals(recipient.signature())) {
            return new Decision(Mode.SAME, Set.of(), "the same rights on the model");
        }
        if (q == null) {
            return new Decision(Mode.RECOMPUTED, Set.of(), "other rights, and the question is not a model query");
        }
        String rowsA = SecuritySignature.rowPart(runner.view().flat(), runner.security(), runner.personal());
        String rowsB = SecuritySignature.rowPart(recipient.view().flat(), recipient.security(), recipient.personal());
        if (!rowsA.equals(rowsB)) {
            return new Decision(Mode.RECOMPUTED, Set.of(), "other row rules");
        }
        Set<String> masksA = SecuritySignature.maskPart(runner.view().flat(), runner.security());
        Set<String> masksB = SecuritySignature.maskPart(recipient.view().flat(), recipient.security());
        if (!masksB.containsAll(masksA)) {
            return new Decision(Mode.RECOMPUTED, Set.of(), "the recipient may see columns masked in this result");
        }
        try {
            mq.validate(recipient, q);
        } catch (RuntimeException e) {
            String code = e instanceof DrishtiException d ? d.errorCode().code() : e instanceof com.ash.drishti.server.bi.mq.MqException m && !m.problems().isEmpty()
                    ? m.problems().get(0).code() : "invalid";
            return new Decision(Mode.RECOMPUTED, Set.of(), "the question does not validate as the recipient (" + code + ")");
        }
        Set<String> extra = new TreeSet<>(masksB);
        extra.removeAll(masksA);
        boolean limited = q.limit() != null || q.totals() != null || q.where().stream().anyMatch(w -> "top".equals(w.op()));
        if (remask(answer, extra, limited).isEmpty()) {
            return new Decision(Mode.RECOMPUTED, extra, "a column masked for the recipient groups this answer");
        }
        return new Decision(Mode.RE_MASKED, extra, "the same rows, more columns masked");
    }

    /**
     * The answer with {@code extra} columns masked as the engine masks them: the value is the mask and a grouping field that is masked collapses its
     * groups (additive measures summed). Empty when that cannot be done exactly (a measure that does not add up, a limit or top filter, totals, a field
     * whose name does not name a column): the recipient's answer must then be recomputed.
     */
    @SuppressWarnings("unchecked")
    public static Optional<Map<String, Object>> remask(Map<String, Object> answer, Set<String> extra, boolean limited) {
        Map<String, Object> out = new LinkedHashMap<>(answer);
        if (extra.isEmpty()) {
            return Optional.of(out);
        }
        List<Map<String, Object>> cols = answer.get("columns") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        List<List<Object>> rows = answer.get("rows") instanceof List<?> l ? (List<List<Object>>) l : List.of();
        List<Integer> masked = new ArrayList<>();
        List<Integer> measures = new ArrayList<>();
        boolean additive = true;
        for (int i = 0; i < cols.size(); i++) {
            Map<String, Object> c = cols.get(i);
            String name = String.valueOf(c.get("name"));
            if ("measure".equals(c.get("kind"))) {
                measures.add(i);
                additive &= Boolean.TRUE.equals(c.get("additive"));
                continue;
            }
            if (!name.contains(".") && !Boolean.TRUE.equals(c.get("masked"))) {
                return Optional.empty();                                        // a field that does not name alias.column: cannot tell, recompute
            }
            if (covered(name, extra) && !Boolean.TRUE.equals(c.get("masked"))) {
                masked.add(i);
            }
        }
        if (masked.isEmpty()) {
            return Optional.of(withMeta(out, cols, masked));
        }
        if (limited || !additive || answer.get("totals") instanceof List<?> t && !t.isEmpty()) {
            return Optional.empty();
        }
        Map<List<Object>, Object[]> groups = new LinkedHashMap<>();
        for (List<Object> row : rows) {
            List<Object> key = new ArrayList<>(row.size());
            for (int i = 0; i < row.size(); i++) {
                key.add(measures.contains(i) ? null : masked.contains(i) ? DataNode.MASK : row.get(i));
            }
            Object[] acc = groups.get(key);
            if (acc == null) {
                groups.put(key, key.toArray());
                acc = groups.get(key);
                for (int m : measures) {
                    acc[m] = row.get(m);
                }
            } else {
                for (int m : measures) {
                    acc[m] = add(acc[m], row.get(m));
                }
            }
        }
        List<List<Object>> collapsed = new ArrayList<>(groups.size());
        groups.values().forEach(a -> collapsed.add(java.util.Arrays.asList(a)));
        out.put("rows", collapsed);
        out.put("rowCount", collapsed.size());
        return Optional.of(withMeta(out, cols, masked));
    }

    private static boolean covered(String field, Set<String> extra) {
        for (String e : extra) {
            if (field.equals(e) || field.startsWith(e + ".")) {
                return true;
            }
        }
        return false;
    }

    private static Object add(Object a, Object b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a instanceof Number x && b instanceof Number y) {
            if ((x instanceof Long || x instanceof Integer) && (y instanceof Long || y instanceof Integer)) {
                return x.longValue() + y.longValue();
            }
            return x.doubleValue() + y.doubleValue();
        }
        return DataNode.MASK;                                                   // never a value that cannot be added
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withMeta(Map<String, Object> out, List<Map<String, Object>> cols, List<Integer> masked) {
        List<Map<String, Object>> c2 = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            Map<String, Object> c = new LinkedHashMap<>(cols.get(i));
            if (masked.contains(i)) {
                c.put("masked", true);
                names.add(String.valueOf(c.get("name")));
            }
            c2.add(c);
        }
        out.put("columns", c2);
        if (out.get("meta") instanceof Map<?, ?> m) {
            Map<String, Object> meta = new LinkedHashMap<>((Map<String, Object>) m);
            List<Object> all = new ArrayList<>(meta.get("masked") instanceof List<?> l ? l : List.of());
            names.stream().filter(n -> !all.contains(n)).forEach(all::add);
            meta.put("masked", all);
            meta.put("cached", true);
            out.put("meta", meta);
        }
        return out;
    }

    // ---- sharing ------------------------------------------------------------------------------------------------------

    /** The model and the model query of a run, when it is one (else the model of a SQL run with a null query, or nulls). */
    record Source(String type, String model, ModelQuery query) {
        static Source of(Run r) {
            Object q = RunJson.parse(r.question());
            if (!(q instanceof Map<?, ?> m)) {
                return new Source(r.sourceType(), null, null);
            }
            String model = m.get("model") == null ? null : String.valueOf(m.get("model"));
            ModelQuery query = null;
            if ("mq".equals(m.get("type")) && m.get("mq") != null) {
                try {
                    query = MqParser.parse(JSON.valueToTree(m.get("mq")));
                    if (model != null) {
                        query = query.withModel(model);
                    }
                } catch (RuntimeException e) {
                    query = null;
                }
            }
            return new Source(String.valueOf(m.get("type")), model, query);
        }
    }

    /** The people a share reaches: names as given, {@code role:x} expanded to its members now, without the sharer and the runner. */
    List<String> expand(List<String> to, String by, String runner) {
        Set<String> out = new LinkedHashSet<>();
        for (String x : to) {
            String t = x == null ? "" : x.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("role:")) {
                out.addAll(roleMembers.apply(t.substring(5)));
            } else {
                out.add(t);
            }
        }
        out.remove(by);
        out.remove(runner);
        return new ArrayList<>(out);
    }

    /**
     * Shares a finished run with people and roles (14.1 {@code POST /runs/{id}/share}): one share row per person, each with its own mode; a recipient who
     * may not read the model at all is answered with the code and gets no share. The answer lists {@code {recipient, mode, recipientRun}} per person.
     */
    public List<Map<String, Object>> share(Run r, Principal by, List<String> to) {
        if (r.state() != RunState.DONE && r.state() != RunState.REUSED) {
            throw new DrishtiException(ErrorCode.RUN_NOT_DONE, "only a finished run can be shared; this one is " + r.state().wire());
        }
        Source src = Source.of(r);
        Map<String, Object> answer = runs.result(r, r.signature());
        MqService.Bound runnerBound = null;
        if (src.model() != null && !"report".equals(src.type())) {
            runnerBound = binder.bind(principals.apply(r.runner()), src.model());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String who : expand(to, by.user(), r.runner())) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("recipient", who);
            try {
                Principal rp = principals.apply(who);
                Decision d;
                if (runnerBound == null) {
                    d = new Decision(Mode.RECOMPUTED, Set.of(), "a report visual is computed for each reader");
                } else {
                    MqService.Bound rb = binder.bind(rp, src.model());
                    d = decide(runnerBound, rb, src.query(), answer, mq);
                }
                String recipientRun = null;
                if (d.mode() == Mode.RECOMPUTED) {
                    recipientRun = recompute(r, rp);
                }
                shares.save(new RunShares.Share(r.id(), who, d.mode().wire(), recipientRun, by.user(), clock.instant()));
                entry.put("mode", d.mode().wire());
                entry.put("recipientRun", recipientRun);
                runs.tellAbout(who, r, by.user() + " shared a run with you: " + RunService.title(r) + (r.asOf() == null ? "" : ", " + asOfWords(r)));
            } catch (DrishtiException e) {
                entry.put("mode", null);
                entry.put("recipientRun", null);
                entry.put("code", e.errorCode().code());
                entry.put("reason", "not shared: " + who + " may not read this (" + e.errorCode().code() + ")");
            } catch (RuntimeException e) {
                entry.put("mode", null);
                entry.put("recipientRun", null);
                entry.put("code", ErrorCode.BAD_REQUEST.code());
                entry.put("reason", "not shared with " + who + ": " + e.getMessage());
            }
            out.add(entry);
        }
        return out;
    }

    private static String asOfWords(Run r) {
        Object a = RunJson.parse(r.asOf());
        if (a instanceof Map<?, ?> m && m.get("to") != null) {
            return String.valueOf(m.get("to"));
        }
        return "";
    }

    /** A new run of the same question in the recipient's name, as the recipient, in the background class; its id. Equal signatures join one computation. */
    private String recompute(Run r, Principal as) {
        RunService.Question q = replay.of(r, as).orElseThrow(() -> new DrishtiException(ErrorCode.BAD_REQUEST, "this run cannot be asked again as " + as.user()));
        RunService.Outcome o = runs.submit(q, new RunService.Options(Duration.ZERO, true, true, r.id()));
        Run rr = o.run();
        if (o.inline()) {
            runs.records().save(rr);                                            // reused at once: the recipient still gets their own row
        }
        return rr.id();
    }

    /** Removes a share (the recipient's own recomputed run stays theirs). */
    public boolean unshare(String runId, String recipient) {
        return shares.remove(runId, recipient);
    }

    /** The share of a run with a person, if any. */
    public Optional<RunShares.Share> shareOf(String runId, String user) {
        return shares.get(runId, user);
    }

    /**
     * The result of a shared run as the recipient reads it <b>now</b>: decided again with their current rights; their own run when it was recomputed.
     * A recipient whose rights no longer allow the shared mode is refused ({@code DRS-9021}) and told to re-run it, never shown the wider answer.
     */
    public Map<String, Object> read(Run r, Principal reader) {
        RunShares.Share s = shares.get(r.id(), reader.user())
                .orElseThrow(() -> new DrishtiException(ErrorCode.RUN_NOT_FOUND, "no run '" + r.id() + "' that you may see"));
        if (s.recipientRun() != null) {
            Run mine = runs.get(s.recipientRun()).orElseThrow(() -> new DrishtiException(ErrorCode.RUN_EXPIRED, "your recomputed run was removed: re-run it"));
            if (!reader.user().equals(mine.runner())) {
                throw new DrishtiException(ErrorCode.RUN_NOT_FOUND, "no run '" + r.id() + "' that you may see");
            }
            return runs.result(mine, mine.signature());
        }
        Source src = Source.of(r);
        if (src.model() == null) {
            throw new DrishtiException(ErrorCode.QUERY_MASKED, "this result was computed with other rights than yours: re-run it to see your own");
        }
        MqService.Bound runnerBound = binder.bind(principals.apply(r.runner()), src.model());
        MqService.Bound mine = binder.bind(reader, src.model());
        Map<String, Object> answer = runs.result(r, r.signature());
        Decision d = decide(runnerBound, mine, src.query(), answer, mq);
        if (d.mode() == Mode.SAME) {
            return answer;
        }
        if (d.mode() == Mode.RE_MASKED) {
            boolean limited = src.query() == null || src.query().limit() != null || src.query().totals() != null
                    || src.query().where().stream().anyMatch(w -> "top".equals(w.op()));
            return remask(answer, d.extraMasked(), limited).orElseThrow(() -> new DrishtiException(ErrorCode.QUERY_MASKED,
                    "this result was computed with other rights than yours: re-run it to see your own"));
        }
        throw new DrishtiException(ErrorCode.QUERY_MASKED, "your rights on this model differ from when it was shared (" + d.why() + "): re-run it to see your own");
    }
}
