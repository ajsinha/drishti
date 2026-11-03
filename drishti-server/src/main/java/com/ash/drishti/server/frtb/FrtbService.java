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
package com.ash.drishti.server.frtb;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.frtb.FactorDictionary;
import com.ash.drishti.compute.frtb.FrtbChoices;
import com.ash.drishti.compute.frtb.FrtbExamples;
import com.ash.drishti.compute.frtb.FrtbInput;
import com.ash.drishti.compute.frtb.FrtbParams;
import com.ash.drishti.compute.frtb.FrtbSa;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.var.VarService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Position sensitivities and FRTB-SA for any scope (FRTB_SA_GUIDE.md sections 3 and 7): the scope resolved as VaR resolves it (the
 * person's roles and masks apply), the scope's sensitivity records read for the date, converted to the reporting currency and netted, the
 * engine run with the parameter files and the method choices. Every answer carries its provenance; every run can be kept as a recipe and
 * replayed to the cent ({@link FrtbRecipes}).
 */
public final class FrtbService {

    /** The scope that runs the design's hand-built cases 1 to 5 together (no data needed). */
    public static final String EXAMPLE = "EXAMPLE";

    /** How a scope's members are found: the VaR engine's resolver as the person (null: the records' own attributes). */
    @FunctionalInterface
    public interface Scopes {
        VarService.ScopeMembers members(Principal p, String scope, LocalDate date);
    }

    /** One run and what it was made of. */
    public record Run(FrtbSa.Result result, String scope, String canonical, LocalDate date, Instant knownAt, String reporting,
            Map<String, Object> provenance, String inputsDigest, List<FrtbData.Record> records) {}

    private final FrtbData data;
    private final Scopes scopes;
    private final FrtbProperties props;
    private final byte[] sbmBytes;
    private final byte[] drcBytes;
    private final FrtbParams params;
    private final Map<String, Run> cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(64)
            .expireAfterWrite(Duration.ofMinutes(10)).<String, Run>build().asMap();

    public FrtbService(FrtbData data, Scopes scopes, FrtbProperties props) {
        this(data, scopes, props, Path.of(props.parametersDir()));
    }

    /**
     * The service with its parameter files in {@code dir}. Checked at start: a missing or refused file leaves the engine answering every
     * request with {@code DRS-5329} and the reason (the server itself starts).
     */
    public FrtbService(FrtbData data, Scopes scopes, FrtbProperties props, Path dir) {
        this.data = data;
        this.scopes = scopes;
        this.props = props;
        byte[] s = new byte[0];
        byte[] d = new byte[0];
        FrtbParams prm = null;
        String why = null;
        try {
            s = Files.readAllBytes(dir.resolve("frtb-sa-sbm.yaml"));
            d = Files.readAllBytes(dir.resolve("frtb-sa-drc-rrao.yaml"));
            prm = FrtbParams.parse(s, d, Map.of());
        } catch (IOException e) {
            why = "DRS-5329 the FRTB-SA parameter files are not in " + dir.toAbsolutePath() + " (drishti.frtb.parameters-dir)";
        } catch (IllegalArgumentException e) {
            why = "DRS-5329 " + e.getMessage();
        }
        if (why != null) {
            org.slf4j.LoggerFactory.getLogger(FrtbService.class).error("{}: FRTB-SA does not answer", why);
        }
        this.sbmBytes = s;
        this.drcBytes = d;
        this.params = prm;
        this.problem = why;
    }

    private final String problem;

    /** Why the engine does not answer (a missing or refused parameter file), or null. */
    public String problem() {
        return problem;
    }

    public FrtbProperties props() {
        return props;
    }

    public FrtbParams params() {
        return params;
    }

    public FrtbData data() {
        return data;
    }

    byte[] sbmBytes() {
        return sbmBytes;
    }

    byte[] drcBytes() {
        return drcBytes;
    }

    /** The engine registry's word on FRTB-SA and on the position sensitivities (COMPUTE_ENGINES.md); unset: the old switch, and on. */
    private volatile java.util.function.BooleanSupplier running;
    private volatile java.util.function.BooleanSupplier sensitivitiesRunning;

    public void running(java.util.function.BooleanSupplier frtb, java.util.function.BooleanSupplier sensitivities) {
        this.running = frtb;
        this.sensitivitiesRunning = sensitivities;
    }

    /** The old switch, drishti.frtb.enabled (an alias of drishti.engines.frtb-sa.enabled). */
    boolean switchedOn() {
        return props.enabled();
    }

    /** Whether FRTB-SA runs on this node now (config/engines/frtb-sa.yaml). */
    public boolean running() {
        java.util.function.BooleanSupplier r = running;
        return r == null ? props.enabled() : r.getAsBoolean();
    }

    /** The position sensitivities (SENS sums, the factor dictionary): 404 DRS-1020 when that engine does not run here. */
    void sensitivities() {
        java.util.function.BooleanSupplier r = sensitivitiesRunning;
        if (r != null && !r.getAsBoolean()) {
            throw new com.ash.drishti.server.engines.EngineOffException("sensitivities", "Sensitivities");
        }
    }

    void enabled() {
        if (!running()) {
            throw new com.ash.drishti.server.engines.EngineOffException("frtb-sa", "FRTB-SA");
        }
        if (problem != null) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, problem);
        }
    }

    /** The parameters with a run's overrides (refused in words when an override names no parameter or leaves its range). */
    FrtbParams params(FrtbChoices c, byte[] sbm, byte[] drc) {
        if (c.overrides().isEmpty() && sbm == sbmBytes && drc == drcBytes) {
            return params;
        }
        try {
            return FrtbParams.parse(sbm, drc, c.overrides());
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    /** The records of a scope on a date, as the person may see them. */
    Scoped scoped(Principal p, String scope, AsOf asOf) {
        sensitivities();                                                // FRTB-SA itself is checked by run()
        if (scope == null || scope.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give a scope (scope=DESK:RATES, BANK, POS:<id>, or EXAMPLE for the hand-built cases)");
        }
        VarService.ScopeMembers early = null;
        if (asOf.businessDate() == null && scopes != null) {
            early = scopes.members(p, scope.strip(), null);
            asOf = new AsOf(early.date(), asOf.knownAt());
        }
        FrtbData.Day day = data.day(asOf);
        if (!day.sensitivitiesRead()) {
            throw new DrishtiException(ErrorCode.NO_SOURCE_FOR_KIND, "no connector serves " + props.sensitivityKind() + ": load the position sensitivities first");
        }
        VarService.ScopeMembers members = null;
        Set<String> keep;
        String canonical;
        if (scopes != null) {
            members = early != null ? early : scopes.members(p, scope.strip(), asOf.businessDate());
            keep = new LinkedHashSet<>(members.positions());
            keep.addAll(members.trades());
            canonical = members.canonical();
        } else {
            keep = null;
            canonical = scope.strip().toUpperCase(Locale.ROOT);
        }
        List<FrtbData.Record> recs = new ArrayList<>();
        for (FrtbData.Record r : day.records()) {
            if (keep != null ? keep.contains(r.positionKey()) || r.tradeId() != null && keep.contains(r.tradeId())
                    || r.tradeIds().stream().anyMatch(keep::contains) : matches(scope, r)) {
                recs.add(r);
            }
        }
        return new Scoped(day, recs, canonical, members);
    }

    /** A scope's records with the date they were read on and the VaR resolver's answer (null: the records' attributes). */
    record Scoped(FrtbData.Day day, List<FrtbData.Record> records, String canonical, VarService.ScopeMembers members) {}

    /** The records' own scope words, when no VaR engine resolves scopes: BANK, DESK:, BOOK:, LE:, DIV:, LL:, FDESK:, POS:, TRD:, joined by + and −. */
    public static boolean matches(String scope, FrtbData.Record r) {
        boolean in = false;
        String s = scope.strip().replace(" - ", " -").replace(" + ", " +");
        for (String term : s.split("\\s+")) {
            boolean minus = term.startsWith("-");
            String t = term.replaceFirst("^[+-]", "");
            if (t.isEmpty()) {
                continue;
            }
            boolean hit = termMatches(t, r);
            if (minus && hit) {
                return false;
            }
            in |= !minus && hit;
        }
        return in;
    }

    private static boolean termMatches(String t, FrtbData.Record r) {
        String u = t.toUpperCase(Locale.ROOT);
        if (u.equals("BANK")) {
            return true;
        }
        int c = t.indexOf(':');
        if (c < 0) {
            return false;
        }
        String word = u.substring(0, c);
        List<String> vals = List.of(t.substring(c + 1).split(","));
        String field = switch (word) {
            case "DESK" -> "DESK";
            case "BOOK" -> "TT_BOOK";
            case "LE" -> "LEGAL_ENTITY";
            case "DIV" -> "DIVISION";
            case "LL" -> "LIMIT_LETTER";
            case "FDESK" -> "FRTB_DESK";
            case "POS" -> "#POS";
            case "TRD" -> "#TRD";
            default -> null;
        };
        if (field == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the scope word " + word + " needs the VaR engine on this server; without it a scope is "
                    + "BANK, DESK:, BOOK:, LE:, DIV:, LL:, FDESK:, POS: or TRD:");
        }
        if (field.equals("#POS")) {
            return vals.contains(r.positionKey());
        }
        if (field.equals("#TRD")) {
            return vals.contains(r.tradeId()) || r.tradeIds().stream().anyMatch(vals::contains);
        }
        String v = r.attributes().get(field);
        return v != null && vals.stream().anyMatch(x -> x.equalsIgnoreCase(v));
    }

    /** FRTB-SA of a scope with the choices (cached per scope, date, viewer, choices and parameter bytes). */
    public Run run(Principal p, String scope, LocalDate date, Instant knownAt, String ccy, FrtbChoices choices) {
        return run(p, scope, date, knownAt, ccy, choices, sbmBytes, drcBytes);
    }

    Run run(Principal p, String scope, LocalDate date, Instant knownAt, String ccy, FrtbChoices choices, byte[] sbm, byte[] drc) {
        enabled();
        String reporting = ccy == null || ccy.isBlank() ? "USD" : ccy.strip().toUpperCase(Locale.ROOT);
        FrtbParams prm = params(choices, sbm, drc);
        if (scope != null && scope.strip().equalsIgnoreCase(EXAMPLE)) {
            FrtbInput in = FrtbExamples.together();
            FrtbSa.Result r = FrtbSa.run(in, prm, choices);
            Map<String, Object> prov = provenance(null, List.of(), prm, choices, in, r);
            prov.put("Inputs", "the design's hand-built cases 1 to 5 (SENSITIVITIES_AND_FRTB_SA.md section 13.1), no data read");
            return new Run(r, EXAMPLE, EXAMPLE, null, null, "USD", prov, digest(in), List.of());
        }
        AsOf asOf = new AsOf(date, knownAt);
        String key = date + "|" + knownAt + "|" + scope + "|" + reporting + "|" + choices.key() + "|" + (p == null ? "" : p.user() + p.roles()) + "|"
                + java.util.Arrays.hashCode(sbm) + java.util.Arrays.hashCode(drc);
        Run hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        Scoped s = scoped(p, scope, asOf);
        Map<String, Integer> convRefusals = new TreeMap<>();
        FrtbInput in = input(s, reporting, prm, convRefusals);
        FrtbSa.Result r = FrtbSa.run(in, prm, choices);
        Map<String, Object> prov = provenance(s, s.records(), prm, choices, in, r);
        if (!convRefusals.isEmpty()) {
            prov.put("Refused when converted", convRefusals);
        }
        Run run = new Run(r, scope.strip(), s.canonical(), s.day().date(), knownAt, reporting, prov, digest(in), s.records());
        cache.put(key, run);
        return run;
    }

    /** The engine's input of a scope: positions, elements in the reporting currency, the DRC and RRAO inputs. */
    FrtbInput input(Scoped s, String reporting, FrtbParams prm, Map<String, Integer> refused) {
        FrtbData.Day day = s.day();
        Double rep = day.usdPer().get(reporting);
        if (rep == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no FX for the reporting currency " + reporting + " on " + day.date());
        }
        List<FrtbInput.Position> positions = new ArrayList<>();
        List<FrtbInput.Element> elements = new ArrayList<>();
        Map<String, Integer> byTrade = new HashMap<>();
        Map<String, Integer> byPosition = new HashMap<>();
        for (FrtbData.Record rec : s.records()) {
            int idx = positions.size();
            positions.add(new FrtbInput.Position(rec.positionKey(), rec.attributes().get("PRODUCT_TYPE"), rec.attributes()));
            if (rec.tradeId() != null) {
                byTrade.put(rec.tradeId(), idx);
            }
            byPosition.putIfAbsent(rec.positionKey(), idx);
            rec.tradeIds().forEach(t -> byTrade.putIfAbsent(t, idx));
            for (FrtbData.Element e : rec.elements()) {
                String ccy = e.currency() == null ? reporting : e.currency().toUpperCase(Locale.ROOT);
                Double per = day.usdPer().get(ccy);
                if (per == null) {
                    refused.merge("DRS-5324 no FX rate for " + ccy + " on the date", 1, Integer::sum);
                    continue;
                }
                FactorDictionary.Factor f = day.dictionary().resolve(e.factor());
                elements.add(new FrtbInput.Element(idx, f, e.measure(), e.value() * per / rep, e.shockSize(), e.impliedVol()));
            }
        }
        List<FrtbInput.DrcPosition> drc = null;
        if (day.defaultRisk() != null) {
            drc = new ArrayList<>();
            for (DataNode n : day.defaultRisk()) {
                String trade = text(n, "TRADE_ID");
                Integer idx = trade == null ? null : byTrade.get(trade);
                if (idx == null && text(n, "POSITION_ID") != null) {
                    idx = byPosition.get(text(n, "POSITION_ID"));
                }
                if (idx == null) {
                    continue;                                         // not in the scope
                }
                Double per = day.usdPer().get(text(n, "CURRENCY") == null ? "USD" : text(n, "CURRENCY").toUpperCase(Locale.ROOT));
                if (per == null) {
                    refused.merge("DRS-5324 no FX rate for a default-risk row's currency", 1, Integer::sum);
                    continue;
                }
                double fx = per / rep;
                String sen = text(n, "SENIORITY");
                boolean cash = "EQUITY".equalsIgnoreCase(sen) && text(n, "MATURITY_DATE") == null;
                String id = text(n, "DEFAULT_RISK_ID");
                drc.add(new FrtbInput.DrcPosition(id != null ? id : trade + "~" + text(n, "OBLIGOR") + "~" + sen, idx, text(n, "OBLIGOR"),
                        bucketOf(text(n, "DRC_BUCKET")), sen, text(n, "CREDIT_QUALITY"), num(n, "NOTIONAL") * fx, num(n, "MARKET_VALUE") * fx,
                        cash ? num(n, "CASH_EQUITY_MATURITY") : years(day.date(), text(n, "MATURITY_DATE")),
                        "true".equalsIgnoreCase(text(n, "RECOVERY_INDEPENDENT")) ? prm.drcRecoveryIndependentLgd : Double.NaN, cash));
            }
        }
        List<FrtbInput.RraoItem> rrao = null;
        if (day.residualRisk() != null) {
            rrao = new ArrayList<>();
            for (DataNode n : day.residualRisk()) {
                String trade = text(n, "TRADE_ID");
                Integer idx = trade == null ? null : byTrade.get(trade);
                Double per = day.usdPer().get(text(n, "CURRENCY") == null ? "USD" : text(n, "CURRENCY").toUpperCase(Locale.ROOT));
                if (idx != null && per != null) {
                    rrao.add(new FrtbInput.RraoItem(trade, idx, positions.get(idx).productType(), text(n, "RRAO_CLASS"), num(n, "GROSS_NOTIONAL") * per / rep,
                            text(n, "BACK_TO_BACK_OF") != null, "true".equalsIgnoreCase(text(n, "LISTED_OR_CLEARABLE"))));
                }
            }
        } else if (props.rraoFromPositions() && s.members() != null) {
            rrao = new ArrayList<>();
            for (int i = 0; i < positions.size(); i++) {
                FrtbInput.Position pos = positions.get(i);
                String type = pos.productType() == null ? "" : pos.productType().toUpperCase(Locale.ROOT);
                Double notional = s.members().notionals().get(pos.id());
                if (prm.rraoTypes.containsKey(type) && notional != null) {
                    rrao.add(new FrtbInput.RraoItem(pos.id(), i, type, null, Math.abs(notional) / rep, false, false));
                }
            }
        }
        return new FrtbInput(positions, elements, reporting, drc, rrao);
    }

    private static String bucketOf(String b) {
        if (b == null) {
            return "CORPORATE";
        }
        String u = b.strip().toUpperCase(Locale.ROOT);
        return u.startsWith("SOV") ? "SOVEREIGN" : u.startsWith("LOCAL") ? "LOCAL_GOVERNMENT_MUNICIPALITY" : u;
    }

    private static double years(LocalDate d, String maturity) {
        if (maturity == null || d == null) {
            return 1;
        }
        try {
            return Math.max(0, (LocalDate.parse(maturity.substring(0, 10)).toEpochDay() - d.toEpochDay()) / 365.25);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static String text(DataNode n, String f) {
        DataNode v = n.get(f);
        return v == null || v.isMissing() || v.isNull() ? null : v.asText();
    }

    private static double num(DataNode n, String f) {
        DataNode v = n.get(f);
        return v == null || v.isMissing() || v.isNull() ? Double.NaN : v.asDouble();
    }

    /** The answer's provenance: the inputs, the parameters, the choices, what was refused (VAR_GUIDE's form). */
    Map<String, Object> provenance(Scoped s, List<FrtbData.Record> recs, FrtbParams prm, FrtbChoices choices, FrtbInput in, FrtbSa.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (s != null) {
            m.put("Business date", String.valueOf(s.day().date()));
            m.put("Known at", s.day().asOf().knownAt() == null ? "latest" : s.day().asOf().knownAt().toString());
            m.put("Sensitivity batches", new ArrayList<>(new java.util.TreeSet<>(s.day().batches())));
            m.put("Records in scope", recs.size());
            m.put("Proxied records on the date", s.day().proxied());
            m.put("Factor dictionary", s.day().dictionary().version() + " (" + s.day().dictionary().size() + " factors)");
            m.put("Refused on load (with DRS codes)", s.day().refusals());
            if (s.members() != null && s.members().hidden() > 0) {
                m.put("Trades your role hides", s.members().hidden());
            }
            if (!s.day().warnings().isEmpty()) {
                m.put("Warnings", s.day().warnings());
            }
        }
        m.put("Parameters", prm.describe());
        m.put("Parameter files SHA-256", prm.hash);
        m.put("Choices", choices.asMap());
        if (!prm.overrides.isEmpty()) {
            m.put("Overrides (not the regulatory figure)", prm.overrides);
        }
        List<String> refused = r.sbm().model.refusals.stream().map(x -> x.factor() + " " + x.measure() + ": " + x.reason()).distinct().limit(50).toList();
        m.put("Refused by the method", r.sbm().model.refusals.size() + (refused.isEmpty() ? "" : " (" + String.join("; ", refused) + ")"));
        if (!r.sbm().model.warnings.isEmpty()) {
            m.put("Method warnings", r.sbm().model.warnings.stream().map(w -> w.factor() + ": " + w.text()).distinct().limit(20).toList());
        }
        m.put("DRC input", r.drc().delivered() ? r.drc().lines().size() + " positions" : "DRC not computed: no default-risk inputs");
        m.put("RRAO input", !r.rrao().delivered() ? "RRAO not computed: no residual-risk inputs" : r.rrao().lines().size() + " instruments"
                + (s != null && s.day().residualRisk() == null ? " (from the positions' product types and notionals)" : ""));
        m.put("Engine", FrtbSa.ENGINE);
        return m;
    }

    /** The SHA-256 of the engine's input (every element, in order): equal digests, equal figures. */
    static String digest(FrtbInput in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder b = new StringBuilder(in.reporting()).append('\n');
            for (FrtbInput.Position p : in.positions()) {
                b.append(p.id()).append('|').append(p.productType()).append('\n');
            }
            for (FrtbInput.Element e : in.elements()) {
                b.append(e.position()).append('|').append(e.factor() == null ? "?" : e.factor().name()).append('|').append(e.measure()).append('|')
                        .append(Double.doubleToLongBits(e.value())).append('|').append(Double.doubleToLongBits(e.shockSize())).append('|')
                        .append(Double.doubleToLongBits(e.impliedVol())).append('\n');
                if (b.length() > 1 << 16) {
                    md.update(b.toString().getBytes(StandardCharsets.UTF_8));
                    b.setLength(0);
                }
            }
            b.append(in.drc() == null ? "nodrc" : in.drc().toString()).append(in.rrao() == null ? "norrao" : in.rrao().toString());
            md.update(b.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sensitivities of a scope summed by {@code by} (RISK_FACTOR, BUCKET, RISK_CLASS, MEASURE, TENOR or a record attribute), in the reporting currency. */
    public Map<String, Object> sums(Principal p, String scope, LocalDate date, String by, String ccy) {
        String reporting = ccy == null || ccy.isBlank() ? "USD" : ccy.strip().toUpperCase(Locale.ROOT);
        Scoped s = scoped(p, scope, new AsOf(date, null));
        Double rep = s.day().usdPer().get(reporting);
        if (rep == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no FX for the reporting currency " + reporting);
        }
        List<String> keys = List.of((by == null || by.isBlank() ? "RISK_FACTOR" : by).toUpperCase(Locale.ROOT).split("\\s*,\\s*"));
        Map<String, double[]> sums = new TreeMap<>();
        Map<String, Set<String>> positions = new HashMap<>();
        for (FrtbData.Record r : s.records()) {
            for (FrtbData.Element e : r.elements()) {
                Double per = s.day().usdPer().get(e.currency() == null ? reporting : e.currency().toUpperCase(Locale.ROOT));
                if (per == null) {
                    continue;
                }
                FactorDictionary.Factor f = s.day().dictionary().resolve(e.factor());
                List<String> parts = new ArrayList<>();
                for (String k : keys) {
                    parts.add(switch (k) {
                        case "RISK_FACTOR", "FACTOR" -> f.name();
                        case "BUCKET" -> f.riskClass() + " " + f.frtbBucket();
                        case "RISK_CLASS", "CLASS" -> f.riskClass();
                        case "TENOR" -> f.tenor() == null ? "—" : f.tenor();
                        case "MEASURE" -> e.measure();
                        default -> r.attributes().getOrDefault(k, "(none)");
                    });
                }
                String key = String.join(" · ", parts) + "\u0000" + e.measure();
                double[] v = sums.computeIfAbsent(key, x -> new double[2]);
                v[0] += e.value() * per / rep;
                v[1]++;
                positions.computeIfAbsent(key, x -> new LinkedHashSet<>()).add(r.positionKey());
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        sums.forEach((k, v) -> {
            String[] kk = k.split("\u0000");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", kk[0]);
            row.put("measure", kk[1]);
            row.put("value", v[0]);
            row.put("elements", (int) v[1]);
            row.put("positions", positions.get(k).size());
            rows.add(row);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", scope);
        out.put("canonical", s.canonical());
        out.put("date", String.valueOf(s.day().date()));
        out.put("by", keys);
        out.put("currency", reporting);
        out.put("records", s.records().size());
        out.put("rows", rows);
        out.put("refusals", s.day().refusals());
        out.put("proxied", s.day().proxied());
        return out;
    }
}
