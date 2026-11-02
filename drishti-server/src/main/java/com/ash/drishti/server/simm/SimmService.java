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
package com.ash.drishti.server.simm;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.simm.Simm;
import com.ash.drishti.compute.simm.SimmChoices;
import com.ash.drishti.compute.simm.SimmExamples;
import com.ash.drishti.compute.simm.SimmInput;
import com.ash.drishti.compute.simm.SimmParams;
import com.ash.drishti.server.frtb.FrtbData;
import com.ash.drishti.server.frtb.FrtbService;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * ISDA SIMM per netting set and regime, collect and post (docs/guides/SIMM_GUIDE.md sections 3 to 7): the netting set's trades from the
 * netting-set master, their sensitivity records from the shared position sensitivities (mapped to the CRIF terms by the dictionary), the
 * CSA's version, regimes, calculation currency and add-ons, the licensed parameter file of the version (installed by the bank, one file a
 * version in {@code drishti.simm.parameters-dir}; none ships). Any other scope is an analytic, labelled so. Every answer carries its
 * provenance and can be kept as a recipe and replayed to the cent ({@link SimmRecipes}).
 */
public final class SimmService {

    /** The scope that runs the hand-built examples (SIMM.md section 15, the swaption, case 11) with the version asked for. */
    public static final String EXAMPLE = "EXAMPLE";
    public static final String NETTING_SET = "netting set";
    public static final String ANALYTIC = "analytic, not a margin";

    /** An installed parameter file: its version, bytes and parameters, or why it is refused. */
    public record Version(String version, String file, byte[] bytes, SimmParams params, String problem) {
        public boolean ok() {
            return params != null;
        }
    }

    /**
     * One computation of a netting set (or scope): both directions, the regimes, and what it was made of.
     *
     * @param kind {@link #NETTING_SET}, {@link #ANALYTIC} or {@link #EXAMPLE}
     */
    public record Run(String scope, String kind, SimmData.NettingSet nettingSet, LocalDate date, Instant knownAt, String calculationCurrency, Version version,
            Simm.Result collect, Simm.Result post, String collectRegime, String postRegime, List<String> collectRegimes, List<String> postRegimes,
            Map<String, Object> provenance, String inputsDigest, int records, int missingTrades) {

        public double imCollect() {
            return collect.total();
        }

        public double imPost() {
            return post.total();
        }
    }

    private final SimmData data;
    private final FrtbService.Scopes scopes;
    private final SimmProperties props;
    private final Map<String, Version> versions = new LinkedHashMap<>();
    private final String defaultVersion;
    private final Map<String, Run> cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(512)
            .expireAfterWrite(Duration.ofMinutes(10)).<String, Run>build().asMap();
    private final Map<String, SimmParams> overridden = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(32)
            .<String, SimmParams>build().asMap();

    /** The service with its licensed parameter files in {@code dir}, read and checked at start (a refused file is listed, the server runs). */
    public SimmService(SimmData data, FrtbService.Scopes scopes, SimmProperties props, Path dir) {
        this.data = data;
        this.scopes = scopes;
        this.props = props;
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(f -> f.toString().endsWith(".yaml") || f.toString().endsWith(".yml")).sorted().forEach(files::add);
            } catch (IOException e) {
                org.slf4j.LoggerFactory.getLogger(SimmService.class).warn("SIMM parameter folder {} not read: {}", dir, e.getMessage());
            }
        }
        for (Path f : files) {
            install(f);
        }
        String dv = props.defaultVersion();
        if (dv.isBlank()) {
            dv = versions.values().stream().filter(Version::ok).max(java.util.Comparator.comparing(v -> v.params().effectiveFrom)).map(Version::version).orElse("");
        }
        this.defaultVersion = dv;
        org.slf4j.LoggerFactory.getLogger(SimmService.class).info("SIMM: {} parameter file(s) in {}, {} installed{}", files.size(), dir.toAbsolutePath(),
                versions.values().stream().filter(Version::ok).count(), dv.isBlank() ? " (no SIMM figure is computed until a licensed file is installed)" : ", default " + dv);
    }

    private void install(Path f) {
        try {
            byte[] b = Files.readAllBytes(f);
            try {
                SimmParams p = SimmParams.parse(b, Map.of());
                versions.put(p.version, new Version(p.version, f.getFileName().toString(), b, p, null));
            } catch (IllegalArgumentException e) {
                SimmParams l;
                try {
                    l = SimmParams.lenient(b);
                } catch (IllegalArgumentException x) {
                    l = null;
                }
                String v = l == null || l.version.isBlank() ? f.getFileName().toString() : l.version;
                versions.put(v, new Version(v, f.getFileName().toString(), b, null, e.getMessage()));
                org.slf4j.LoggerFactory.getLogger(SimmService.class).warn("SIMM parameter file {} refused: {}", f, e.getMessage());
            }
        } catch (IOException e) {
            versions.put(f.getFileName().toString(), new Version(f.getFileName().toString(), f.getFileName().toString(), new byte[0], null, "DRS-5333 not read: "
                    + e.getMessage()));
        }
    }

    /** Forgets every date and every computed run (a load of one of the kinds). */
    public void clear() {
        data.clear();
        cache.clear();
    }

    /**
     * The shipped template's run: the hand-built examples on the empty structure (playground/simm/template/parameters.yaml, the same file as
     * docs/architecture/capital-parameters/simm.yaml), every parameter NaN, so the workbook has its formulas and empty parameter cells.
     */
    Run template() {
        byte[] b;
        try (java.io.InputStream in = SimmService.class.getClassLoader().getResourceAsStream("playground/simm/template/parameters.yaml")) {
            if (in == null) {
                throw new IllegalStateException("the built-in playground/simm/template/parameters.yaml is missing");
            }
            b = in.readAllBytes();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        SimmParams empty = SimmParams.lenient(b);
        Version v = new Version("(empty: licensed)", "parameters.yaml", b, empty, null);
        return assemble(EXAMPLE, EXAMPLE, null, null, null, null, v, empty, SimmChoices.defaults(), SimmExamples.together(), List.of("NONE"), List.of("NONE"), null, 0,
                0);
    }

    public SimmProperties props() {
        return props;
    }

    public SimmData data() {
        return data;
    }

    /** Every parameter file found, installed or refused. */
    public List<Version> versions() {
        return List.copyOf(versions.values());
    }

    public String defaultVersion() {
        return defaultVersion;
    }

    void enabled() {
        if (!props.enabled()) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "SIMM is switched off on this server (drishti.simm.enabled)");
        }
    }

    /** The installed version asked for (blank: the default); DRS-5333 when it is not installed or was refused. */
    Version version(String asked, String nettingSet) {
        String v = asked == null || asked.isBlank() ? defaultVersion : asked.strip();
        String who = nettingSet == null ? "" : "; " + nettingSet + " is not computed";
        if (v.isBlank()) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "DRS-5333 no SIMM version is installed (the licensed parameter files go in "
                    + Path.of(props.parametersDir()).toAbsolutePath() + ", one a version: docs/guides/SIMM_GUIDE.md section 4)" + who);
        }
        Version x = versions.get(v);
        if (x == null) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "DRS-5333 SIMM " + v + " is not installed" + who);
        }
        if (!x.ok()) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, x.problem() + who);
        }
        return x;
    }

    /** The version of a run: a recipe's own file (its bytes, whether or not still installed), else the installed one asked for. */
    Version resolve(String asked, String nettingSet, byte[] bytes) {
        if (bytes == null) {
            return version(asked, nettingSet);
        }
        try {
            SimmParams p = SimmParams.parse(bytes, Map.of());
            return new Version(p.version, "the recipe's parameter file", bytes, p, null);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, e.getMessage());
        }
    }

    /** The parameters of a version with a run's overrides (or of a recipe's own bytes). */
    SimmParams params(Version v, SimmChoices c, byte[] bytes) {
        if (bytes == null && c.overrides().isEmpty()) {
            return v.params();
        }
        byte[] b = bytes != null ? bytes : v.bytes();
        String key = java.util.Arrays.hashCode(b) + "|" + c.overrides();
        try {
            return overridden.computeIfAbsent(key, k -> SimmParams.parse(b, c.overrides()));
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    /** The trades and positions the person may see on the date, or null when no VaR engine resolves scopes here. */
    private Set<String> visible(Principal p, LocalDate date) {
        if (scopes == null) {
            return null;
        }
        VarService.ScopeMembers m = scopes.members(p, "BANK", date);
        Set<String> keep = new LinkedHashSet<>(m.positions());
        keep.addAll(m.trades());
        return keep;
    }

    private static boolean seen(Set<String> keep, FrtbData.Record r) {
        return keep == null || keep.contains(r.positionKey()) || r.tradeId() != null && keep.contains(r.tradeId()) || r.tradeIds().stream().anyMatch(keep::contains);
    }

    /**
     * SIMM of a netting set, an analytic scope or {@code EXAMPLE}: both directions with the choices; {@code bytes} a recipe's parameter file
     * (null: the installed version's).
     */
    public Run run(Principal p, String scope, LocalDate date, Instant knownAt, String asked, SimmChoices ch, byte[] bytes) {
        enabled();
        if (scope == null || scope.isBlank()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "give a netting set (nettingSet=NS-...), a scope (scope=DESK:DESK-RATES, an analytic) or EXAMPLE");
        }
        String s = scope.strip();
        if (s.toUpperCase(Locale.ROOT).startsWith("NS:")) {
            s = s.substring(3).strip();
        }
        String key = date + "|" + knownAt + "|" + s + "|" + asked + "|" + ch.key() + "|" + (p == null ? "" : p.user() + p.roles()) + "|"
                + (bytes == null ? "" : java.util.Arrays.hashCode(bytes));
        Run hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        Run run;
        if (s.equalsIgnoreCase(EXAMPLE)) {
            Version v = resolve(asked, null, bytes);
            SimmParams prm = params(v, ch, bytes);
            SimmInput in = SimmExamples.together();
            run = assemble(EXAMPLE, EXAMPLE, null, null, null, null, v, prm, ch, in, List.of("NONE"), List.of("NONE"), null, 0, 0);
            run.provenance().put("Inputs", "the hand-built examples (SIMM.md section 15, the swaption of the explanation, case 11), no data read");
        } else {
            SimmData.Day day = data.day(new AsOf(date, knownAt));
            if (!day.sens().sensitivitiesRead()) {
                throw new DrishtiException(ErrorCode.NO_SOURCE_FOR_KIND, "no connector serves position-sensitivity: load the position sensitivities first");
            }
            Set<String> keep = visible(p, day.date());
            SimmData.NettingSet ns = day.nettingSets().get(s);
            run = ns != null ? nettingSet(day, ns, keep, asked, ch, bytes, knownAt) : analytic(p, day, s, asked, ch, bytes, knownAt);
        }
        cache.put(key, run);
        return run;
    }

    private Run nettingSet(SimmData.Day day, SimmData.NettingSet ns, Set<String> keep, String asked, SimmChoices ch, byte[] bytes, Instant knownAt) {
        if (keep != null && ns.trades().stream().noneMatch(keep::contains) && day.recordsBySet().getOrDefault(ns.id(), List.of()).stream().noneMatch(r -> seen(keep, r))) {
            throw new DrishtiException(ErrorCode.FORBIDDEN, "your role does not see the trades of " + ns.id());
        }
        SimmData.Csa csa = ns.csa() == null ? null : day.csas().get(ns.csa());
        if (csa != null && csa.model().equals("SCHEDULE")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, ns.id() + " is margined by schedule IM under " + csa.id() + ": not a SIMM netting set");
        }
        String v = asked != null && !asked.isBlank() ? asked : csa == null ? null : csa.version();
        Version ver = resolve(v, ns.id(), bytes);
        SimmParams prm = params(ver, ch, bytes);
        List<FrtbData.Record> recs = new ArrayList<>();
        int hidden = 0;
        for (FrtbData.Record r : day.recordsBySet().getOrDefault(ns.id(), List.of())) {
            if (seen(keep, r)) {
                recs.add(r);
            } else {
                hidden++;
            }
        }
        String calc = csa == null ? "USD" : csa.calculationCurrency();
        Map<String, Integer> refused = new TreeMap<>();
        SimmInput.AddOns addOns = addOns(day, ns, csa, calc, refused);
        SimmInput in = input(day, recs, calc, prm, addOns, refused);
        Set<String> covered = day.tradesWithRecords().getOrDefault(ns.id(), Set.of());
        int missing = (int) ns.trades().stream().filter(t -> !covered.contains(t)).count();
        List<String> collect = csa == null || csa.collect().isEmpty() ? List.of("NONE") : csa.collect();
        List<String> post = csa == null || csa.post().isEmpty() ? List.of("NONE") : csa.post();
        Run run = assemble(ns.id(), NETTING_SET, ns, day.date(), knownAt, day, ver, prm, ch, in, collect, post, csa, recs.size(), missing);
        Map<String, Object> pv = run.provenance();
        if (hidden > 0) {
            pv.put("Records your role hides", hidden);
        }
        if (!refused.isEmpty()) {
            pv.put("Refused when converted", refused);
        }
        List<String> two = new ArrayList<>();
        day.tradeInTwo().forEach((t, sets) -> {
            if (sets.contains(ns.id()) && two.size() < 20) {
                two.add("DRS-5332 trade " + t + " is in netting sets " + String.join(" and ", sets) + " on " + day.date());
            }
        });
        if (!two.isEmpty()) {
            pv.put("Trades in two netting sets (kept in both)", two);
        }
        return run;
    }

    private Run analytic(Principal p, SimmData.Day day, String scope, String asked, SimmChoices ch, byte[] bytes, Instant knownAt) {
        Version ver = resolve(asked, null, bytes);
        SimmParams prm = params(ver, ch, bytes);
        Set<String> keep;
        String canonical;
        if (scopes != null) {
            VarService.ScopeMembers m = scopes.members(p, scope, day.date());
            keep = new LinkedHashSet<>(m.positions());
            keep.addAll(m.trades());
            canonical = m.canonical();
        } else {
            keep = null;
            canonical = scope.toUpperCase(Locale.ROOT);
        }
        List<FrtbData.Record> recs = new ArrayList<>();
        for (FrtbData.Record r : day.sens().records()) {
            if (keep != null ? seen(keep, r) : FrtbService.matches(scope, r)) {
                recs.add(r);
            }
        }
        if (recs.isEmpty() && !scope.contains(":") && !scope.equalsIgnoreCase("BANK")) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no netting set " + scope + " on " + day.date() + " (and no record in it as a scope)");
        }
        Map<String, Integer> refused = new TreeMap<>();
        SimmInput in = input(day, recs, "USD", prm, SimmInput.AddOns.NONE, refused);
        Run run = assemble(scope, ANALYTIC, null, day.date(), knownAt, day, ver, prm, ch, in, List.of("NONE"), List.of("NONE"), null, recs.size(), 0);
        run.provenance().put("Scope", scope + " (" + canonical + "): " + ANALYTIC + ", the default version, no regime");
        if (!refused.isEmpty()) {
            run.provenance().put("Refused when converted", refused);
        }
        return run;
    }

    /** The engine's input of a set of records in the calculation currency. */
    SimmInput input(SimmData.Day day, List<FrtbData.Record> recs, String calc, SimmParams prm, SimmInput.AddOns addOns, Map<String, Integer> refused) {
        Map<String, Double> fx = day.sens().usdPer();
        Double rep = fx.get(calc);
        if (rep == null) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "no FX for the calculation currency " + calc + " on " + day.date());
        }
        Double file = fx.get(prm.calculationCurrency);
        List<SimmInput.Trade> trades = new ArrayList<>();
        List<SimmInput.Element> els = new ArrayList<>();
        for (FrtbData.Record r : recs) {
            int idx = trades.size();
            String id = "TRADE".equalsIgnoreCase(r.grain()) && r.tradeId() != null ? r.tradeId() : r.positionKey();
            trades.add(new SimmInput.Trade(id, props.productClass(r.attributes().get("PRODUCT_GROUP")), r.attributes()));
            for (FrtbData.Element e : r.elements()) {
                String ccy = e.currency() == null ? calc : e.currency().toUpperCase(Locale.ROOT);
                Double per = fx.get(ccy);
                if (per == null) {
                    refused.merge("DRS-5324 no FX rate for " + ccy + " on the date", 1, Integer::sum);
                    continue;
                }
                els.add(new SimmInput.Element(idx, day.factors().get(e.factor()), e.measure(), e.value() * per / rep, e.impliedVol()));
            }
        }
        return new SimmInput(trades, els, calc, addOns, file == null ? 1 : file / rep);
    }

    private SimmInput.AddOns addOns(SimmData.Day day, SimmData.NettingSet ns, SimmData.Csa csa, String calc, Map<String, Integer> refused) {
        if (csa == null) {
            return SimmInput.AddOns.NONE;
        }
        Map<String, Double> fx = day.sens().usdPer();
        double rep = fx.getOrDefault(calc, 1.0);
        List<SimmInput.Line> lines = new ArrayList<>();
        for (DataNode n : day.addOnLines().getOrDefault(ns.id(), List.of())) {
            String ccy = SimmData.text(n, "CURRENCY");
            Double per = fx.get(ccy == null ? "USD" : ccy.toUpperCase(Locale.ROOT));
            if (per == null) {
                refused.merge("DRS-5324 no FX rate for an add-on line's currency", 1, Integer::sum);
                continue;
            }
            lines.add(new SimmInput.Line(SimmData.text(n, "TRADE_ID"), SimmData.text(n, "PRODUCT"), SimmData.num(n, "NOTIONAL", 0) * per / rep));
        }
        return new SimmInput.AddOns(csa.fixed(), csa.multipliers(), csa.factors(), lines);
    }

    private Run assemble(String scope, String kind, SimmData.NettingSet ns, LocalDate date, Instant knownAt, SimmData.Day day, Version ver, SimmParams prm,
            SimmChoices ch, SimmInput in, List<String> collect, List<String> post, SimmData.Csa csa, int records, int missing) {
        Simm.Result c;
        Simm.Result po;
        try {
            c = Simm.run(in, prm, ch, false);
            po = Simm.run(in, prm, ch, true);
        } catch (IllegalArgumentException e) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        List<String> unknown = new ArrayList<>();
        for (String r : Stream.concat(collect.stream(), post.stream()).distinct().toList()) {
            if (!prm.regimes.isEmpty() && !prm.regimes.contains(r)) {
                unknown.add("DRS-5334 regime " + r + " is not recognised by SIMM " + prm.version);
            }
        }
        String cr = collect.stream().filter(r -> prm.regimes.isEmpty() || prm.regimes.contains(r)).findFirst().orElse("NONE");
        String pr = post.stream().filter(r -> prm.regimes.isEmpty() || prm.regimes.contains(r)).findFirst().orElse("NONE");
        Map<String, Object> pv = new LinkedHashMap<>();
        if (day != null) {
            pv.put("Business date", String.valueOf(day.date()));
            pv.put("Known at", knownAt == null ? "latest" : knownAt.toString());
            pv.put("Sensitivity batches", new ArrayList<>(new java.util.TreeSet<>(day.sens().batches())));
            pv.put("Records in scope", records);
            pv.put("Factor dictionary", day.sens().dictionary().version() + " (" + day.sens().dictionary().size() + " factors)");
            pv.put("Refused on load (with DRS codes)", day.sens().refusals());
            if (day.spanning() > 0) {
                pv.put("Records whose trades span netting sets (DRS-5335, left out of SIMM)", day.spanning() + " " + day.spanningIds());
            }
        }
        if (ns != null) {
            pv.put("Netting set", ns.id() + " · counterparty " + ns.counterparty() + " · CSA " + ns.csa() + " · " + ns.trades().size() + " trades");
            pv.put("Trades without sensitivities", missing + (missing > 0 ? " (incomplete: SIMM of the trades that have them)" : ""));
            pv.put("CSA terms", csa == null ? "no CSA record: the default version, regime NONE, USD, no add-on"
                    : "version " + (csa.version() == null ? "(none: the default)" : csa.version()) + ", collect " + collect + ", post " + post + ", calculation currency "
                    + csa.calculationCurrency() + ", add-ons: fixed " + csa.fixed() + ", multipliers " + csa.multipliers() + ", notional factors " + csa.factors());
            pv.put("Regimes", "every regime's trades are the netting set's SIMM trades (no per-regime eligibility on the trade master yet); binding: collect "
                    + cr + ", post " + pr);
        }
        if (!unknown.isEmpty()) {
            pv.put("Regimes not recognised (ignored)", unknown);
        }
        pv.put("SIMM version", prm.describe());
        pv.put("Parameter file", ver.file() + " · SHA-256 " + prm.hash);
        pv.put("Calculation currency", in.calculationCurrency() + (in.thresholdFx() == 1 ? "" : " (thresholds × " + in.thresholdFx() + " from "
                + prm.calculationCurrency + ")"));
        pv.put("Choices", ch.asMap());
        if (!prm.overrides.isEmpty()) {
            pv.put("Overrides (not the regulatory figure)", prm.overrides);
        }
        Map<String, Integer> byCode = new TreeMap<>();
        c.refusals().forEach(x -> byCode.merge(x.reason().length() > 8 ? x.reason().substring(0, 8) : x.reason(), 1, Integer::sum));
        pv.put("Left out by the method", c.refusals().size() + (byCode.isEmpty() ? "" : " " + byCode + ": "
                + String.join("; ", c.refusals().stream().map(Simm.Refusal::reason).distinct().limit(12).toList())));
        if (!c.notes().isEmpty()) {
            pv.put("Notes", c.notes());
        }
        pv.put("Engine", Simm.ENGINE);
        return new Run(scope, kind, ns, date, knownAt, in.calculationCurrency(), ver, c, po, cr, pr, collect, post, pv, digest(in), records, missing);
    }

    /** The SHA-256 of the engine's input (every element, in order): equal digests, equal figures. */
    static String digest(SimmInput in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder b = new StringBuilder(in.calculationCurrency()).append('|').append(Double.doubleToLongBits(in.thresholdFx())).append('\n');
            for (SimmInput.Trade t : in.trades()) {
                b.append(t.id()).append('|').append(t.productClass()).append('\n');
            }
            for (SimmInput.Element e : in.elements()) {
                b.append(e.trade()).append('|').append(e.factor() == null ? "?" : e.factor().toString()).append('|').append(e.measure()).append('|')
                        .append(Double.doubleToLongBits(e.value())).append('|').append(Double.doubleToLongBits(e.impliedVol())).append('\n');
                if (b.length() > 1 << 16) {
                    md.update(b.toString().getBytes(StandardCharsets.UTF_8));
                    b.setLength(0);
                }
            }
            b.append(in.addOns());
            md.update(b.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Every netting set of the date the person may see (mock-up A): computed where it has sensitivities, the stored figure (labelled) where
     * it has none, the reason where it cannot be computed; {@code counterparty} narrows the list.
     */
    public Map<String, Object> nettingSets(Principal p, LocalDate date, String counterparty, String asked, SimmChoices ch) {
        enabled();
        SimmData.Day day = data.day(new AsOf(date, null));
        if (!day.nettingSetsRead()) {
            throw new DrishtiException(ErrorCode.NO_SOURCE_FOR_KIND, "no connector serves " + props.nettingSetKind() + ": load the netting sets first");
        }
        Set<String> keep = visible(p, day.date());
        List<Map<String, Object>> rows = new ArrayList<>();
        double[] sum = new double[2];
        int computed = 0;
        for (SimmData.NettingSet ns : day.nettingSets().values()) {
            if (counterparty != null && !counterparty.isBlank() && !counterparty.strip().equalsIgnoreCase(ns.counterparty())) {
                continue;
            }
            if (keep != null && ns.trades().stream().noneMatch(keep::contains)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nettingSet", ns.id());
            row.put("counterparty", ns.counterparty());
            row.put("csa", ns.csa());
            row.put("trades", ns.trades().size());
            List<FrtbData.Record> recs = day.recordsBySet().getOrDefault(ns.id(), List.of());
            if (!recs.isEmpty()) {
                try {
                    Run r = run(p, ns.id(), day.date(), null, asked, ch, null);
                    row.put("status", r.missingTrades() > 0 ? "computed, incomplete" : "computed");
                    row.put("version", r.version().version());
                    row.put("imCollect", r.imCollect());
                    row.put("imPost", r.imPost());
                    row.put("collectRegime", r.collectRegime());
                    row.put("postRegime", r.postRegime());
                    row.put("missingTrades", r.missingTrades());
                    row.put("currency", r.calculationCurrency());
                    sum[0] += r.imCollect();
                    sum[1] += r.imPost();
                    computed++;
                } catch (DrishtiException e) {
                    row.put("status", "not computed: " + e.getMessage());
                }
            } else {
                DataNode st = day.stored().get(ns.id());
                if (st != null) {
                    row.put("status", "stored, not computed");
                    row.put("version", SimmData.text(st, "VERSION"));
                    row.put("imCollect", SimmData.num(st, "RECEIVED", Double.NaN));
                    row.put("imPost", SimmData.num(st, "POSTED", Double.NaN));
                } else {
                    row.put("status", "no sensitivities");
                }
            }
            List<SimmData.Call> calls = day.calls().getOrDefault(ns.id(), List.of());
            calls.stream().filter(c -> c.direction().equals("COLLECT")).findFirst().ifPresent(c -> row.put("theirCall", c.total()));
            rows.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", String.valueOf(day.date()));
        m.put("defaultVersion", defaultVersion.isBlank() ? null : defaultVersion);
        m.put("installed", versions.values().stream().filter(Version::ok).map(Version::version).toList());
        m.put("counterparty", counterparty);
        m.put("rows", rows);
        m.put("computed", computed);
        m.put("sumCollect", sum[0]);
        m.put("sumPost", sum[1]);
        m.put("note", "SIMM does not net across netting sets: the sums are the netting sets' margins added");
        if (defaultVersion.isBlank()) {
            m.put("notice", "no SIMM version is installed: the netting sets show the stored figures until the bank's licensed parameter file is installed");
        }
        return m;
    }
}
