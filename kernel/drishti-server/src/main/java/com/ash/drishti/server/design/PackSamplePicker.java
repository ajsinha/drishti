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
package com.ash.drishti.server.design;

import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.common.PackSamples;
import com.ash.drishti.rachana.SutraMatcher;
import com.ash.drishti.rachana.SutraRegistry;
import com.ash.drishti.rachana.model.Sutra;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * A sample for a Design that edits a pack's Sutra and has none: the first record of the pack's own samples
 * ({@code <pack>/samples/<kind>.jsonl}, or the older one-file-per-record layout) that the Sutra's {@code match} holds for (its kind,
 * then its {@code where}), so the designer draws at once instead of saying "Nothing to draw yet". The Sutra's pack is the folder its
 * file is in ({@code <pack>/sutras/...}); a Sutra of the site's own folder has no pack samples. Reads stop at the first match.
 * Stateless and thread-safe.
 */
@Service
public class PackSamplePicker {

    private static final Logger LOG = LoggerFactory.getLogger(PackSamplePicker.class);
    /** The name a picked sample is kept under: the workbench recognises it and says where it came from. */
    public static final String PREFIX = "pack sample ";

    /** The record picked: its kind, id and JSON text. */
    public record Picked(String kind, String id, String json) {

        /** The sample's name in the Design. */
        public String name() {
            return PREFIX + id;
        }
    }

    private final SutraRegistry sutras;
    private final SutraMatcher matcher;
    private final JsonCodec codec;

    public PackSamplePicker(SutraRegistry sutras, SutraMatcher matcher, JsonCodec codec) {
        this.sutras = sutras;
        this.matcher = matcher;
        this.codec = codec;
    }

    /** The first pack sample the base Sutra ({@code name@version}) matches; empty when there is none or the base is not a pack's. */
    public Optional<Picked> pick(String base) {
        if (base == null || base.lastIndexOf('@') <= 0) {
            return Optional.empty();
        }
        int at = base.lastIndexOf('@');
        Sutra s;
        try {
            s = sutras.get(base.substring(0, at), Integer.parseInt(base.substring(at + 1))).orElse(null);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        Path samples = sutras.fileOf(base).flatMap(PackSamplePicker::samplesOf).orElse(null);
        if (s == null || samples == null) {
            return Optional.empty();
        }
        String kind = s.match().kind();
        try {
            return PackSamples.first(samples, kind, doc -> {
                try {
                    return doc.isObject() && matcher.matches(s, codec.read(doc.toString()));
                } catch (RuntimeException e) {
                    return false;                          // an imperfect record is not a sample to show
                }
            }).map(e -> new Picked(kind, e.getKey(), e.getValue()));
        } catch (IOException e) {
            LOG.warn("cannot read the pack samples of {} in {}: {}", base, samples, e.getMessage());
            return Optional.empty();
        }
    }

    /** {@code <pack>/samples} of a Sutra file under {@code <pack>/sutras/}, when that folder exists. */
    static Optional<Path> samplesOf(Path sutraFile) {
        for (Path p = sutraFile.toAbsolutePath().normalize().getParent(); p != null && p.getParent() != null; p = p.getParent()) {
            if (p.getFileName() != null && "sutras".equals(p.getFileName().toString())) {
                Path samples = p.getParent().resolve("samples");
                return Files.isDirectory(samples) ? Optional.of(samples) : Optional.empty();
            }
        }
        return Optional.empty();
    }
}
