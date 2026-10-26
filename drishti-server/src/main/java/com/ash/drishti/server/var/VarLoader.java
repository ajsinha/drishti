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
package com.ash.drishti.server.var;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.var.PositionRules;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.compute.vector.ParallelSums;
import com.ash.drishti.engine.source.SourceRouter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Reads one business date of the VaR engine's inputs through the connectors the kinds are routed to (the market-risk pack's
 * {@code risk-store} in the demo; the bank's lake in production) and builds the {@link VarBook}: the trades' vectors streamed from the
 * vector column straight into the store (no document is parsed for them), each row checked and given its position by the configured
 * rules ({@link VarIntake}), then the hierarchies with their limits, the scenario set, and the portfolios' definitions. Positions are
 * Drishti's: derived here, from the trades and the rules, each time the date is loaded (VAR_GUIDE.md section 15.6). A date whose inputs are
 * not one run (several batch ids, another scenario set, two scenario sets) is refused ({@code DRS-5203}). Reads as the server; what a
 * viewer may see is applied per request.
 *
 * <p>With the tiered memory ({@code drishti.var.tier.mode: mapped}, VAR_GUIDE.md section 26) the rows stream to a spill file and the date is
 * written to its hot file and mapped; a date whose hot file was built from the same inputs (the same data versions of its vectors,
 * hierarchy and scenario set, the same position rules) is opened from that file, without reading its vectors again.
 */
final class VarLoader {

    private final SourceRouter router;
    private final VarProperties props;
    private final ParallelSums sums;
    private final Supplier<PositionRules> rules;
    private final VarTier tier;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    VarLoader(SourceRouter router, VarProperties props, ParallelSums sums, Supplier<PositionRules> rules) {
        this(router, props, sums, rules, new VarTier(props.tier()));
    }

    VarLoader(SourceRouter router, VarProperties props, ParallelSums sums, Supplier<PositionRules> rules, VarTier tier) {
        this.router = router;
        this.props = props;
        this.sums = sums;
        this.rules = rules;
        this.tier = tier;
    }

    VarTier tier() {
        return tier;
    }

    /** The newest business date that has vectors, or null. */
    LocalDate latest() {
        return router.latestDate(props.kinds().vector()).orElse(null);
    }

    /** The newest date with vectors before {@code date}, or null (a date that cannot be loaded is answered by the one before). */
    LocalDate previous(LocalDate date) {
        return router.heldDates(props.kinds().vector()).map(s -> s.lower(date)).orElse(null);
    }

    /** The vector table's data version of a date (a refused date is not read again while it is unchanged). */
    String version(LocalDate date) {
        return router.dataVersion(props.kinds().vector(), AsOf.of(date)).orElse("");
    }

    /** The position rules a load uses now (a pack's section, else the server's setting). */
    PositionRules rules() {
        return rules.get();
    }

    VarDate load(LocalDate date) {
        long t0 = System.nanoTime();
        VarProperties.Kinds k = props.kinds();
        AsOf asOf = AsOf.of(date);
        Map<String, String> versions = new LinkedHashMap<>();
        for (String kind : k.inputs()) {
            versions.put(kind, router.dataVersion(kind, asOf).orElse(""));
        }
        PositionRules pr = rules.get();
        String digest = tier.mapped() ? VarTier.digest(date, versions.get(k.vector()), versions.get(k.hierarchy()), versions.get(k.scenarioSet()),
                pr.digest()) : "";
        java.util.Optional<java.nio.file.Path> hot = tier.mapped() ? tier.existing(date, digest) : java.util.Optional.empty();
        if (hot.isPresent()) {
            VarDate reopened = reopen(date, hot.get(), versions, t0);
            if (reopened != null) {
                return reopened;
            }
        }
        Map<String, DataNode> sets = documents(k.scenarioSet(), asOf);
        DataNode set = sets.values().stream().findFirst().orElse(null);
        if (set == null) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no scenario set for " + date + " (kind " + k.scenarioSet() + ")");
        }
        if (sets.size() > 1) {
            throw inconsistent(date, sets.size() + " scenario sets (" + String.join(", ", sets.keySet()) + "): one per date");
        }
        String setId = text(set, "SCENARIO_SET_ID");
        List<LocalDate> scenarioDates = scenarioDates(date, setId, set);
        long budget = props.memoryBudgetMb() * 1024 * 1024;
        VarBook.Builder b = tier.mapped() ? new VarBook.Builder(date, setId, scenarioDates, budget, tier.target(date, digest))
                : new VarBook.Builder(date, setId, scenarioDates, props.rowsPerChunk(), budget);
        try {
            VarIntake.Summary sum = intake(date, asOf, b, pr, setId);
            long tVectors = System.nanoTime();
            for (DataNode n : documents(k.hierarchy(), asOf).values()) {
                List<String> books = new ArrayList<>();
                DataNode bs = n.get("BOOKS");
                for (int i = 0; bs != null && i < bs.size(); i++) {
                    books.add(bs.get(i).asText());
                }
                b.node(text(n, "NODE_ID"), text(n, "HIERARCHY"), text(n, "LEVEL"), text(n, "NAME"), text(n, "PARENT_ID"), books,
                        number(text(n, "LIMIT_VAR")));
            }
            Map<String, VarDate.Portfolio> portfolios = new LinkedHashMap<>();
            documents(k.portfolio(), asOf).forEach((id, p) -> portfolios.put(id, VarDate.Portfolio.of(id, p)));
            versions.put("scenarioSet", setId);
            b.batchId(sum.batchIds().isEmpty() ? "" : sum.batchIds().get(0)).meta(meta(sum, versions));
            VarBook book = b.build(sums);
            tier.opened(book);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            return new VarDate(date, book, portfolios, versions.get(k.vector()), java.time.Instant.now(), ms, (tVectors - t0) / 1_000_000, sum,
                    versions, List.of());
        } catch (RuntimeException e) {
            b.discard();
            throw e;
        }
    }

    /** Streams the date's vectors through the intake into the builder, then checks the date is one run. */
    private VarIntake.Summary intake(LocalDate date, AsOf asOf, VarBook.Builder b, PositionRules pr, String setId) {
        VarProperties.Kinds k = props.kinds();
        List<String> columns = VarIntake.columns(router.columnar(k.vector()), pr);
        VarIntake intake = new VarIntake(b, pr, date, columns.subList(1, columns.size()));
        boolean read;
        try {
            read = router.vectors(k.vector(), "PNL", columns, asOf, (id, values, len, row) -> intake.row(id, values, len, row));
        } catch (com.ash.drishti.compute.vector.VectorStore.BudgetExceeded e) {
            throw new DrishtiException(ErrorCode.VAR_CAPACITY, e.getMessage() + ": the engine has no room for this date now; raise "
                    + "drishti.var.memory-budget-mb or the server's heap, or keep fewer dates loaded (drishti.var.hot-dates)");
        } catch (VarBook.DuplicateTrade e) {
            throw new DrishtiException(ErrorCode.VAR_DUPLICATE_TRADE, "the P&L vectors of " + date + " are not loaded: " + e.getMessage());
        } catch (DrishtiException e) {
            throw e;
        } catch (Exception e) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "the P&L vectors of " + date + " could not be read: " + e.getMessage());
        }
        if (!read) {
            throw new DrishtiException(ErrorCode.SOURCE_FAILED, "no connector keeps " + k.vector() + ".PNL as a vector column with the columns "
                    + columns + " (the pack's layout." + k.vector() + ".columns and .vectors)");
        }
        VarIntake.Summary sum = intake.summary();
        if (sum.batchIds().size() > 1) {
            throw inconsistent(date, "the vectors come from " + sum.batchIds().size() + " runs (BATCH_ID " + String.join(", ", sum.batchIds())
                    + "): one run per date");
        }
        List<String> otherSets = sum.scenarioSets().stream().filter(s -> !s.equals(setId)).toList();
        if (!otherSets.isEmpty()) {
            throw inconsistent(date, "vectors of the scenario set " + String.join(", ", otherSets) + " beside the date's set " + setId);
        }
        return sum;
    }

    /** The load's own record kept in the hot file: what the intake counted and the inputs' versions (read back by {@link #reopen}). */
    private static String meta(VarIntake.Summary sum, Map<String, String> versions) {
        try {
            return JSON.writeValueAsString(Map.of("intake", sum, "versions", versions));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{}";
        }
    }

    /**
     * A date opened from its hot file (built from the same inputs): mapped, its intake and versions from the file, its portfolios read
     * again (they are not in the file's digest). Null when the file cannot be used (then the date is built from the lake).
     */
    private VarDate reopen(LocalDate date, java.nio.file.Path file, Map<String, String> versions, long t0) {
        VarBook book;
        try {
            book = tier.open(file, t0);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(VarLoader.class).warn("VaR: the hot file {} is not usable ({}); the date is read from the lake", file,
                    e.getMessage());
            return null;
        }
        VarIntake.Summary sum;
        Map<String, String> v = new LinkedHashMap<>(versions);
        try {
            com.fasterxml.jackson.databind.JsonNode m = JSON.readTree(book.hot().meta());
            sum = JSON.treeToValue(m.get("intake"), VarIntake.Summary.class);
            m.path("versions").fields().forEachRemaining(e -> v.putIfAbsent(e.getKey(), e.getValue().asText()));
        } catch (Exception e) {
            book.close();
            return null;
        }
        v.put("scenarioSet", book.scenarioSet());
        Map<String, VarDate.Portfolio> portfolios = new LinkedHashMap<>();
        documents(props.kinds().portfolio(), AsOf.of(date)).forEach((id, p) -> portfolios.put(id, VarDate.Portfolio.of(id, p)));
        tier.opened(book);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new VarDate(date, book, portfolios, v.get(props.kinds().vector()), java.time.Instant.now(), ms, 0, sum, v, List.of());
    }

    /** The scenario days, checked: strictly increasing and all before the business date (else the date is not one run). */
    private static List<LocalDate> scenarioDates(LocalDate date, String setId, DataNode set) {
        List<LocalDate> out = new ArrayList<>();
        DataNode sd = set.get("SCENARIO_DATES");
        for (int i = 0; sd != null && i < sd.size(); i++) {
            LocalDate d = LocalDate.parse(sd.get(i).asText());
            if (!out.isEmpty() && !d.isAfter(out.get(out.size() - 1))) {
                throw inconsistent(date, "the scenario set " + setId + " is not in date order at " + d);
            }
            if (!d.isBefore(date)) {
                throw inconsistent(date, "the scenario set " + setId + " has the scenario day " + d + ", not before the business date");
            }
            out.add(d);
        }
        if (out.isEmpty()) {
            throw inconsistent(date, "the scenario set " + setId + " has no SCENARIO_DATES");
        }
        return out;
    }

    private static DrishtiException inconsistent(LocalDate date, String why) {
        return new DrishtiException(ErrorCode.VAR_INCONSISTENT_DATE, "the VaR inputs of " + date + " are not loaded: " + why);
    }

    private Map<String, DataNode> documents(String kind, AsOf asOf) {
        Map<String, DataNode> out = new LinkedHashMap<>();
        boolean whole = router.documents(kind, asOf, (EntityDocument d) -> out.put(d.ref().id(), d.data()));
        if (!whole) {
            var listing = router.listAcross(kind, 100_000, java.time.Duration.ofSeconds(30), asOf, new com.ash.drishti.engine.source.SourceFailures());
            var refs = listing.hits().stream().map(h -> h.ref()).toList();
            router.fetchAll(refs, java.time.Duration.ofSeconds(30), asOf).forEach((r, d) -> out.put(r.id(), d.data()));
        }
        return out;
    }

    static String text(DataNode n, String field) {
        DataNode v = n.get(field);
        return v == null || v.isMissing() || v.isNull() ? null : v.asText();
    }

    static double number(String s) {
        if (s == null || s.isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
