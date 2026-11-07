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
package com.ash.drishti.banking.frtb;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.frtb.FrtbChoices;
import com.ash.drishti.banking.compute.frtb.FrtbSa;
import com.ash.drishti.banking.compute.frtb.Sbm;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saved playground runs (SENSITIVITIES_AND_FRTB_SA.md section 12.1a, FRTB_SA_GUIDE.md section 8.3): a recipe records the scope and date,
 * the knowledge time the inputs were read at, the digest of the engine's input, the parameter files themselves (their bytes and hash),
 * every switch and override, the engine version and the figures. {@code PLAY FRTB RUN <id>} reads the inputs as known then, runs the
 * recorded parameters and choices and compares: equal digests give byte-equal figures, and a difference is named, never hidden.
 */
public final class FrtbRecipes {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** A recipe as stored. */
    public record Recipe(String id, String scope, String date, String knownAt, String currency, Map<String, String> switches,
            Map<String, String> overrides, String parametersHash, String sbmFile, String drcFile, String engine, String inputsDigest,
            Map<String, Double> figures, String user, String created) {}

    /** A replay: the recipe, the new run, whether the input and every figure are equal, and the differences named. */
    public record Replay(Recipe recipe, FrtbService.Run run, boolean inputsEqual, boolean figuresEqual, List<String> differences) {}

    private final Path dir;
    private final FrtbService svc;

    public FrtbRecipes(Path dir, FrtbService svc) {
        this.dir = dir;
        this.svc = svc;
    }

    /** The figures a recipe keeps (and a replay compares), by name. */
    static Map<String, Double> figures(FrtbSa.Result r) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int s = 0; s < 3; s++) {
            m.put("SBM " + Sbm.SCENARIOS[s], r.sbm().total[s]);
        }
        for (Sbm.ClassCharge c : r.sbm().charges) {
            for (int s = 0; s < 3; s++) {
                m.put(c.cls + " " + c.measure + " " + Sbm.SCENARIOS[s], c.charge[s]);
            }
        }
        m.put("SBM", r.sbm().sbm());
        m.put("DRC", r.drc().delivered() ? r.drc().total() : null);
        m.put("RRAO", r.rrao().delivered() ? r.rrao().total() : null);
        m.put("Total", r.total());
        return m;
    }

    /** Saves a run as a recipe; its id is returned. */
    public Recipe save(FrtbService.Run run, byte[] sbm, byte[] drc, Principal p) {
        FrtbSa.Result r = run.result();
        String id = "FR-" + run.inputsDigest().substring(0, 8).toUpperCase(java.util.Locale.ROOT) + "-"
                + Long.toString(System.currentTimeMillis(), 36).toUpperCase(java.util.Locale.ROOT);
        Instant knownAt = run.knownAt() != null ? run.knownAt() : Instant.now();
        Recipe rec = new Recipe(id, run.scope(), run.date() == null ? null : run.date().toString(), run.date() == null ? null : knownAt.toString(),
                run.reporting(), r.choices().asMap(), r.choices().overrides(), r.params().hash, Base64.getEncoder().encodeToString(sbm),
                Base64.getEncoder().encodeToString(drc), FrtbSa.ENGINE, run.inputsDigest(), figures(r), p == null ? "" : p.user(), Instant.now().toString());
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(id + ".json.part");
            JSON.writeValue(tmp.toFile(), rec);
            Files.move(tmp, dir.resolve(id + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rec;
    }

    /** A recipe by id (404 in words when there is none). */
    public Recipe get(String id) {
        if (id == null || !id.matches("FR-[A-Z0-9-]{4,40}")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a recipe id is FR-... (as PLAY FRTB shows it), not " + id);
        }
        Path f = dir.resolve(id + ".json");
        if (!Files.isRegularFile(f)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no recipe " + id + " on this server");
        }
        try {
            return JSON.readValue(f.toFile(), Recipe.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs a recipe again and compares it with what it recorded. */
    public Replay replay(String id, Principal p) {
        Recipe rec = get(id);
        byte[] sbm = Base64.getDecoder().decode(rec.sbmFile());
        byte[] drc = Base64.getDecoder().decode(rec.drcFile());
        FrtbChoices c = FrtbChoices.of(rec.switches(), rec.overrides());
        FrtbService.Run run = svc.run(p, rec.scope(), rec.date() == null ? null : LocalDate.parse(rec.date()),
                rec.knownAt() == null ? null : Instant.parse(rec.knownAt()), rec.currency(), c, sbm, drc);
        Map<String, Double> now = figures(run.result());
        List<String> diffs = new java.util.ArrayList<>();
        rec.figures().forEach((k, v) -> {
            Double w = now.get(k);
            boolean same = v == null ? w == null : w != null && Double.doubleToLongBits(v) == Double.doubleToLongBits(w);
            if (!same) {
                diffs.add(k + ": recorded " + v + ", now " + w);
            }
        });
        boolean inputs = rec.inputsDigest().equals(run.inputsDigest());
        if (!inputs) {
            diffs.add(0, "the inputs read now differ from the recorded ones (digest " + rec.inputsDigest().substring(0, 12) + " then, "
                    + run.inputsDigest().substring(0, 12) + " now): the connector could not read the data as known at " + rec.knownAt());
        }
        if (!FrtbSa.ENGINE.equals(rec.engine())) {
            diffs.add("the engine was " + rec.engine() + ", is " + FrtbSa.ENGINE);
        }
        return new Replay(rec, run, inputs, diffs.isEmpty(), List.copyOf(diffs));
    }
}
