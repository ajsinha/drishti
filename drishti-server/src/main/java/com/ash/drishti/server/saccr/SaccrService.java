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
package com.ash.drishti.server.saccr;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.saccr.Saccr;
import com.ash.drishti.compute.saccr.SaccrExamples;
import com.ash.drishti.compute.saccr.SaccrInput;
import com.ash.drishti.compute.saccr.SaccrParams;
import com.ash.drishti.compute.saccr.SaccrRows;
import com.ash.drishti.server.frtb.FrtbService;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.var.VarService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SA-CCR per netting set and for any scope that sums whole sets (docs/guides/SACCR_GUIDE.md sections 3 to 7): the classification rows
 * ({@code saccr-trade}), each set's live members with their MTM, its CSA terms and collateral, the parameter file of the regulation. EAD
 * adds over netting sets, so a counterparty's or a legal entity's EAD is the sum of its sets'; nothing below a set adds. Every answer is
 * computed when asked, from the inputs of the date, and names the parameter file it used; nothing is stored.
 */
public final class SaccrService {

    public static final String EXAMPLE = "EXAMPLE";

    /**
     * One netting set's computation and what it was made of.
     *
     * @param status {@code computed}, {@code incomplete} (live derivatives without a row), {@code no derivatives}
     * @param excluded live members that are not derivatives (SFTs, cash products): left out, counted
     * @param unclassified live derivatives without a DRIVER 0 row: left out, listed, the set incomplete
     * @param flags MARGIN_TERMS_MISSING, STANDALONE (netting not enforceable), SPECIFIC_WWR, PARTIAL
     */
    public record SetRun(String id, String counterparty, String legalEntity, String csa, Saccr.Result result, String status, int members, int excluded,
            List<String> unclassified, List<String> flags, String collateralNote) {

        public double ead() {
            return result == null ? 0 : result.ead();
        }
    }

    /** A scope's computation: its netting sets (EAD adds over them) and its provenance. */
    public record Run(String scope, String kind, LocalDate date, List<SetRun> sets, Map<String, Object> provenance) {

        public double ead() {
            double s = 0;
            for (SetRun x : sets) {
                s += x.ead();
            }
            return s;
        }

        public SetRun set(String id) {
            return sets.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
        }
    }

    private final SaccrData data;
    private final FrtbService.Scopes scopes;
    private final SaccrProperties props;
    private final SaccrParams params;
    private final String paramsFile;
    private final Map<String, Run> cache;
    private volatile java.util.function.BooleanSupplier running;

    public SaccrService(SaccrData data, FrtbService.Scopes scopes, SaccrProperties props) {
        this.data = data;
        this.scopes = scopes;
        this.props = props;
        if (props.parametersFile().isBlank()) {
            this.params = SaccrParams.bcbs();
            this.paramsFile = "saccr/saccr-bcbs.yaml (built in)";
        } else {
            Path f = Path.of(props.parametersFile());
            try {
                this.params = SaccrParams.parse(Files.readAllBytes(f));
            } catch (IOException e) {
                throw new IllegalStateException("DRS-5413 the SA-CCR parameter file " + f.toAbsolutePath() + " is not readable: " + e.getMessage(), e);
            }
            this.paramsFile = f.toAbsolutePath().toString();
        }
        this.cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(256).expireAfterWrite(Duration.ofMinutes(props.cacheMinutes()))
                .<String, Run>build().asMap();
        org.slf4j.LoggerFactory.getLogger(SaccrService.class).info("SA-CCR: {} from {}", params.describe(), paramsFile);
    }

    public SaccrProperties props() {
        return props;
    }

    public SaccrParams params() {
        return params;
    }

    public String paramsFile() {
        return paramsFile;
    }

    public SaccrData data() {
        return data;
    }

    /** Forgets every date and every run (a load of one of the kinds). */
    public void clear() {
        data.clear();
        cache.clear();
    }

    /** The engine registry's word on whether SA-CCR runs on this node (COMPUTE_ENGINES.md); unset (a test of the service alone): on. */
    public void running(java.util.function.BooleanSupplier on) {
        this.running = on;
    }

    void enabled() {
        java.util.function.BooleanSupplier r = running;
        if (r != null && !r.getAsBoolean()) {
            throw new com.ash.drishti.server.engines.EngineOffException("sa-ccr", "SA-CCR");
        }
    }

    // ---- scopes -------------------------------------------------------------------------------------------------------------------

    /** The trades the person may see on the date, or null when no VaR engine resolves scopes here (every trade). */
    private Set<String> visible(Principal p, LocalDate date) {
        if (scopes == null || p == null) {
            return null;
        }
        VarService.ScopeMembers m = scopes.members(p, "BANK", date);
        Set<String> keep = new LinkedHashSet<>(m.trades());
        keep.addAll(m.positions());
        return keep;
    }

    /**
     * SA-CCR of a scope: {@code NSET:<id>} (or a bare netting-set id), {@code CPTY:<id>}, {@code LE:<id>}, {@code BANK}, {@code EXAMPLE}
     * (the hand-built cases, each its own set) or {@code EXAMPLE:<case>}.
     */
    public Run run(Principal p, String scope, LocalDate date, Saccr.Choices ch, boolean strict) {
        enabled();
        if (scope == null || scope.isBlank()) {
            throw new DrishtiException(ErrorCode.SACCR_BAD_REQUEST, "DRS-5410 give a scope: NSET:<netting set>, CPTY:<counterparty>, LE:<legal entity>, BANK or EXAMPLE");
        }
        String s = scope.strip();
        String up = s.toUpperCase(Locale.ROOT);
        String key = date + "|" + up + "|" + ch + "|" + strict + "|" + (p == null ? "" : p.user() + p.roles());
        Run hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        Run run = up.equals(EXAMPLE) || up.startsWith(EXAMPLE + ":") ? examples(up, ch) : data(p, s, up, date, ch, strict);
        cache.put(key, run);
        return run;
    }

    private Run examples(String up, Saccr.Choices ch) {
        String only = up.contains(":") ? up.substring(up.indexOf(':') + 1).strip() : null;
        List<SetRun> sets = new ArrayList<>();
        for (SaccrExamples.Case c : SaccrExamples.design()) {
            if (only == null || c.name().equalsIgnoreCase(only)) {
                Saccr.Result r = Saccr.run(c.input(), params, ch);
                sets.add(new SetRun("EX-" + c.name(), "EXAMPLE", null, null, r, "computed", c.input().trades().size(), 0, List.of(), List.of(),
                        "the case's own V and C (" + c.source() + "; published EAD " + Math.round(c.publishedEad()) + ")"));
            }
        }
        if (sets.isEmpty()) {
            throw new DrishtiException(ErrorCode.SACCR_BAD_REQUEST, "DRS-5410 no hand-built case " + only + "; the cases are "
                    + SaccrExamples.design().stream().map(SaccrExamples.Case::name).toList());
        }
        Map<String, Object> pv = provenance(null, ch);
        pv.put("Inputs", "the hand-built cases of COUNTERPARTY_ENGINES.md section 9 and SACCR.md section 15 (years given directly), no data read");
        return new Run(only == null ? EXAMPLE : EXAMPLE + ":" + only, "example", null, sets, pv);
    }

    private Run data(Principal p, String s, String up, LocalDate date, Saccr.Choices ch, boolean strict) {
        SaccrData.Day day = data.day(new AsOf(date, null));
        if (!day.rowsRead()) {
            throw new DrishtiException(ErrorCode.NO_SOURCE_FOR_KIND, "no connector serves " + props.tradeKind() + ": load the SA-CCR classification rows first");
        }
        if (!day.setsRead()) {
            throw new DrishtiException(ErrorCode.NO_SOURCE_FOR_KIND, "no connector serves " + props.nettingSetKind() + ": load the netting sets first");
        }
        List<String> ids = new ArrayList<>();
        String kind;
        String body = up.contains(":") ? s.substring(s.indexOf(':') + 1).strip() : s;
        if (up.startsWith("CPTY:") || up.startsWith("LE:")) {
            boolean cp = up.startsWith("CPTY:");
            kind = cp ? "counterparty" : "legal entity";
            day.sets().values().stream().filter(x -> body.equalsIgnoreCase(cp ? x.counterparty() : x.legalEntity())).forEach(x -> ids.add(x.id()));
            if (ids.isEmpty()) {
                throw new DrishtiException(ErrorCode.SACCR_UNKNOWN_SET, "DRS-5412 no netting set of " + kind + " " + body + " on " + date);
            }
        } else if (up.equals("BANK")) {
            kind = "bank";
            ids.addAll(day.sets().keySet());
        } else if (up.startsWith("NSET:") || !up.contains(":")) {
            kind = "netting set";
            if (!day.sets().containsKey(body)) {
                throw new DrishtiException(ErrorCode.SACCR_UNKNOWN_SET, "DRS-5412 no netting set " + body + " on " + date);
            }
            ids.add(body);
        } else {
            throw new DrishtiException(ErrorCode.SACCR_BAD_REQUEST, "DRS-5410 SA-CCR adds over whole netting sets: the scopes are NSET:, CPTY:, LE:, BANK and "
                    + "EXAMPLE, not " + s);
        }
        Set<String> keep = visible(p, day.date());
        List<SetRun> sets = new ArrayList<>();
        int hidden = 0;
        for (String id : ids) {
            List<SaccrData.Member> ms = day.members().getOrDefault(id, List.of());
            if (keep != null && !ms.isEmpty() && ms.stream().noneMatch(m -> keep.contains(m.tradeId()))) {
                hidden++;
                continue;
            }
            sets.addAll(nettingSet(day, day.sets().get(id), ch));
        }
        if (sets.isEmpty() && hidden > 0) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "your role does not see the trades of " + s);
        }
        if (strict) {
            List<String> incomplete = sets.stream().filter(x -> !x.unclassified().isEmpty()).map(SetRun::id).toList();
            if (!incomplete.isEmpty()) {
                throw new DrishtiException(ErrorCode.SACCR_INCOMPLETE, "DRS-5411 live derivatives without a classification row in " + incomplete
                        + " (strict=true)");
            }
        }
        Map<String, Object> pv = provenance(day, ch);
        pv.put("Scope", s + " (" + kind + ": " + sets.size() + " netting set" + (sets.size() == 1 ? "" : "s") + ", EAD summed over them)");
        if (hidden > 0) {
            pv.put("Netting sets your role hides", hidden);
        }
        return new Run(s, kind, day.date(), sets, pv);
    }

    /** One netting set's runs: the set itself, or each member alone (netting not enforceable), with any SPECIFIC_WWR row on its own. */
    List<SetRun> nettingSet(SaccrData.Day day, SaccrData.SetInfo info, Saccr.Choices ch) {
        List<Map<String, Object>> rows = day.rows().getOrDefault(info.id(), List.of());
        List<SaccrData.Member> members = day.members().getOrDefault(info.id(), List.of());
        Map<String, Double> mtm = new LinkedHashMap<>();
        members.forEach(m -> mtm.put(m.tradeId(), m.mtm()));
        Set<String> classified = new LinkedHashSet<>();
        List<Map<String, Object>> refusedComputed = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (SaccrRows.computedField(r) != null) {
                refusedComputed.add(r);
            } else if (driver(r) == 0) {
                classified.add(String.valueOf(r.get("TRADE_ID")));
            }
        }
        int excluded = 0;
        List<String> unclassified = new ArrayList<>();
        for (SaccrData.Member m : members) {
            if (!classified.contains(m.tradeId())) {
                if (props.excluded(m.productGroup(), m.productType())) {
                    excluded++;
                } else {
                    unclassified.add(m.tradeId());
                }
            }
        }
        List<String> flags = new ArrayList<>();
        if (info.marginTermsMissing()) {
            flags.add("MARGIN_TERMS_MISSING");
        }
        if (!refusedComputed.isEmpty()) {
            flags.add("ROWS_WITH_COMPUTED_FIGURES_REFUSED:" + refusedComputed.size());
        }
        LocalDate cob = day.date();
        List<SaccrInput.Trade> trades = new ArrayList<>();
        List<SaccrInput.Trade> wwr = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            if (refusedComputed.contains(r)) {
                continue;
            }
            String tid = String.valueOf(r.get("TRADE_ID"));
            SaccrInput.Trade t = SaccrRows.trade(r, cob, mtm.getOrDefault(tid, Double.NaN));
            if (Boolean.TRUE.equals(r.get("SPECIFIC_WWR")) || "true".equalsIgnoreCase(String.valueOf(r.get("SPECIFIC_WWR")))) {
                wwr.add(t);
            } else {
                trades.add(t);
            }
        }
        String status = !unclassified.isEmpty() ? "incomplete" : trades.isEmpty() && wwr.isEmpty() ? "no derivatives" : "computed";
        List<SetRun> out = new ArrayList<>();
        if (!info.enforceable()) {
            flags.add("STANDALONE");
            for (SaccrInput.Trade t : trades) {
                out.add(alone("NS-STANDALONE~" + t.tradeId(), info, t, ch, status, flags));
            }
        } else if (trades.isEmpty() && wwr.isEmpty()) {
            out.add(new SetRun(info.id(), info.counterparty(), info.legalEntity(), info.csa(), null, status, members.size(), excluded, List.copyOf(unclassified),
                    List.copyOf(flags), "no derivative: SA-CCR has nothing to measure (the set's collateral is held against its other trades)"));
        } else {
            double v = 0;
            for (SaccrInput.Trade t : trades) {
                if (t.driver() == 0 && Double.isFinite(t.mtm())) {
                    v += t.mtm();
                }
            }
            SaccrInput.NettingSet ns = new SaccrInput.NettingSet(info.id(), info.counterparty(), info.legalEntity(), v, info.c(), info.nica(), info.th(),
                    info.mta(), info.margined(), info.cleared(), info.mporDays(), info.frequencyDays(), info.disputes(), info.illiquid(), members.size());
            Saccr.Result r = Saccr.run(new SaccrInput(ns, trades), params, ch);
            out.add(new SetRun(info.id(), info.counterparty(), info.legalEntity(), info.csa(), r, status, members.size(), excluded, List.copyOf(unclassified),
                    List.copyOf(flags), info.collateralNote()));
        }
        for (SaccrInput.Trade t : wwr) {
            List<String> f = new ArrayList<>(flags);
            f.add("SPECIFIC_WWR");
            out.add(alone(info.id() + "~WWR~" + t.tradeId(), info, t, ch, "computed", f));
        }
        return out;
    }

    private SetRun alone(String id, SaccrData.SetInfo info, SaccrInput.Trade t, Saccr.Choices ch, String status, List<String> flags) {
        double v = Double.isFinite(t.mtm()) ? t.mtm() : 0;
        Saccr.Result r = Saccr.run(new SaccrInput(SaccrInput.NettingSet.unmargined(id, v, 0), List.of(t)), params, ch);
        return new SetRun(id, info.counterparty(), info.legalEntity(), info.csa(), r, status, 1, 0, List.of(), List.copyOf(flags),
                "a trade alone: unmargined, V its MTM, no collateral");
    }

    /** A trade's standalone EAD: its own unmargined set, V its MTM, C = 0 (never a share of its set's EAD). */
    double standalone(SaccrInput.Trade t, Saccr.Choices ch) {
        double v = Double.isFinite(t.mtm()) ? t.mtm() : 0;
        return Saccr.run(new SaccrInput(SaccrInput.NettingSet.unmargined("STANDALONE~" + t.tradeId(), v, 0), List.of(t)), params, ch).ead();
    }

    /** The answer's provenance: the date, the parameter file, the choices and the inputs read. */
    Map<String, Object> provenance(SaccrData.Day day, Saccr.Choices ch) {
        Map<String, Object> pv = new LinkedHashMap<>();
        if (day != null) {
            pv.put("Business date", String.valueOf(day.date()));
            pv.put("Classification rows", day.rows().values().stream().mapToInt(List::size).sum() + " (" + props.tradeKind() + ")");
            if (day.rowsWithoutSet() > 0) {
                pv.put("Rows without a netting set (left out)", day.rowsWithoutSet());
            }
            pv.put("Members and MTM", props.valuationKind() + " (live statuses NEW, VALIDATED, AMENDED; not past maturity)");
            pv.put("Netting sets, CSAs, collateral", props.nettingSetKind() + ", " + props.csaKind() + ", " + props.collateralKind());
        }
        pv.put("Regulation", params.describe());
        pv.put("Parameter file", paramsFile + " · SHA-256 " + params.hash);
        pv.put("Choices", Map.of("ir-bucket-offset", ch.irBucketOffset(), "cap-at-unmargined", ch.cap()));
        if (!ch.regulatory()) {
            pv.put("Not the regulatory figure", "a method choice differs from the regulation's");
        }
        pv.put("Engine", Saccr.ENGINE);
        return pv;
    }

    static int driver(Map<String, Object> r) {
        Object d = r.get("DRIVER");
        if (d instanceof Number n) {
            return n.intValue();
        }
        try {
            return d == null ? 0 : (int) Double.parseDouble(String.valueOf(d).strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The netting set and row of a trade on the date (null when it has none). */
    record Located(SaccrData.SetInfo set, List<Map<String, Object>> rows) {}

    Located locate(SaccrData.Day day, String tradeId) {
        for (Map.Entry<String, List<Map<String, Object>>> e : day.rows().entrySet()) {
            List<Map<String, Object>> mine = e.getValue().stream().filter(r -> tradeId.equals(String.valueOf(r.get("TRADE_ID")))).toList();
            if (!mine.isEmpty()) {
                return new Located(day.sets().get(e.getKey()), mine);
            }
        }
        return null;
    }
}
