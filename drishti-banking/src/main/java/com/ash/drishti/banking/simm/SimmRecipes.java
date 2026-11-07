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
package com.ash.drishti.banking.simm;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.banking.compute.simm.Simm;
import com.ash.drishti.banking.compute.simm.SimmChoices;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Saved SIMM playground runs (docs/guides/SIMM_GUIDE.md section 8.3; the structure of FRTB-SA's recipes with the netting set and the SIMM
 * version in place of the filer and regime): the netting set and date, the knowledge time the inputs were read at, the input digest, the
 * parameter file itself (its bytes: the bank's licensed file stays on the bank's server), the version asked, the switches and overrides,
 * the engine and the figures. {@code PLAY SIMM RUN <id>} reads the inputs as known then and compares to the cent.
 */
public final class SimmRecipes {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** A recipe as stored. */
    public record Recipe(String id, String scope, String date, String knownAt, String version, Map<String, String> switches, Map<String, String> overrides,
            String parametersHash, String parametersFile, String engine, String inputsDigest, Map<String, Double> figures, String user, String created) {}

    /** A replay: the recipe, the new run, whether the input and every figure are equal, and the differences named. */
    public record Replay(Recipe recipe, SimmService.Run run, boolean inputsEqual, boolean figuresEqual, List<String> differences) {}

    private final Path dir;
    private final SimmService svc;

    public SimmRecipes(Path dir, SimmService svc) {
        this.dir = dir;
        this.svc = svc;
    }

    /** The figures a recipe keeps (and a replay compares), by name. */
    static Map<String, Double> figures(SimmService.Run run) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (Simm.Result r : List.of(run.collect(), run.post())) {
            String d = r.post() ? "post" : "collect";
            for (Simm.Charge c : r.charges()) {
                m.put(d + " " + c.productClass + " " + c.riskClass + " " + c.measure, c.margin);
            }
            for (Simm.ProductIm p : r.products()) {
                m.put(d + " SIMM " + p.productClass(), p.simm());
            }
            m.put(d + " SIMM", r.simm());
            m.put(d + " add-ons", r.addOn().total());
            m.put(d + " IM", r.total());
        }
        return m;
    }

    /** Saves a run as a recipe. */
    public Recipe save(SimmService.Run run, Principal p) {
        String id = "SM-" + run.inputsDigest().substring(0, 8).toUpperCase(Locale.ROOT) + "-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT);
        Instant knownAt = run.knownAt() != null ? run.knownAt() : Instant.now();
        Recipe rec = new Recipe(id, run.scope(), run.date() == null ? null : run.date().toString(), run.date() == null ? null : knownAt.toString(),
                run.version().version(), run.collect().choices().asMap(), run.collect().choices().overrides(), run.collect().params().hash,
                Base64.getEncoder().encodeToString(run.version().bytes()), Simm.ENGINE, run.inputsDigest(), figures(run), p == null ? "" : p.user(),
                Instant.now().toString());
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

    /** A recipe by id. */
    public Recipe get(String id) {
        if (id == null || !id.matches("SM-[A-Z0-9-]{4,40}")) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "a SIMM recipe id is SM-... (as PLAY SIMM shows it), not " + id);
        }
        Path f = dir.resolve(id + ".json");
        if (!Files.isRegularFile(f)) {
            throw new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "no SIMM recipe " + id + " on this server");
        }
        try {
            return JSON.readValue(f.toFile(), Recipe.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs a recipe again with its own parameter file and compares it with what it recorded. */
    public Replay replay(String id, Principal p) {
        Recipe rec = get(id);
        byte[] file = Base64.getDecoder().decode(rec.parametersFile());
        SimmChoices c = SimmChoices.of(rec.switches(), rec.overrides());
        SimmService.Run run = svc.run(p, rec.scope(), rec.date() == null ? null : LocalDate.parse(rec.date()), rec.knownAt() == null ? null
                : Instant.parse(rec.knownAt()), rec.version(), c, file);
        Map<String, Double> now = figures(run);
        List<String> diffs = new ArrayList<>();
        rec.figures().forEach((k, v) -> {
            Double w = now.get(k);
            boolean same = v == null ? w == null : w != null && Double.doubleToLongBits(v) == Double.doubleToLongBits(w);
            if (!same) {
                diffs.add(k + ": recorded " + v + ", now " + w);
            }
        });
        now.keySet().stream().filter(k -> !rec.figures().containsKey(k)).forEach(k -> diffs.add(k + ": not in the recipe, now " + now.get(k)));
        boolean inputs = rec.inputsDigest().equals(run.inputsDigest());
        if (!inputs) {
            diffs.add(0, "the inputs read now differ from the recorded ones (digest " + rec.inputsDigest().substring(0, 12) + " then, "
                    + run.inputsDigest().substring(0, 12) + " now): the connector could not read the data as known at " + rec.knownAt());
        }
        if (!Simm.ENGINE.equals(rec.engine())) {
            diffs.add("the engine was " + rec.engine() + ", is " + Simm.ENGINE);
        }
        return new Replay(rec, run, inputs, diffs.isEmpty(), List.copyOf(diffs));
    }
}
